#!/usr/bin/env bash
# Upload a run bundle and submit one benchmark through AWS CLI.
set -euo pipefail
export AWS_PAGER=""

usage() {
  cat <<'HELP'
Usage: bash benchmarks/scripts/cluster.sh --cluster-id ID --region REGION \
  --s3-prefix s3://BUCKET/PREFIX --benchmark cholesky|cg --case CASE [options]

  --inventory FILE       CSV (default: src/main/resources/emr-scaling.csv)
  --jar FILE             Prebuilt assembly; otherwise build with sbt
  --partitions N         Input and shuffle partitions (default: 64)
  --executors N          Fixed executor count (default: 4)
  --executor-cores N     Cores per executor (default: 4)
  --executor-heap-gib N  Heap per executor (default: 16)
  --overhead-gib N       Memory overhead per executor (default: 4)
  --repetitions N        Measured repetitions (default: 5)
  --warmups 0|1          Warmup before measured repetitions (default: 1)
  --dry-run             Write local bundle/step JSON without calling AWS
  --help                Show usage

Driver heap comes from the CSV. Standard AWS CLI credentials/profile settings
are honored locally; EMR uses its instance role. Returns a step ID without
waiting. Requires an idle cluster with step concurrency 1.
HELP
}
die() { printf '%s\n' "$*" >&2; exit 1; }
repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
inventory="$repo_dir/benchmarks/src/main/resources/emr-scaling.csv"
cluster_id= region= s3_prefix= benchmark= case_id= jar_file=
partitions=64 executors=4 executor_cores=4 executor_heap=16 overhead=4 repetitions=5 warmups=1 dry_run=false
while (($#)); do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --dry-run) dry_run=true; shift; continue ;;
  esac
  (($# >= 2)) || die "Missing value for $1"
  case "$1" in
    --cluster-id) cluster_id=$2 ;; --region) region=$2 ;; --s3-prefix) s3_prefix=${2%/} ;;
    --benchmark) benchmark=$2 ;; --case) case_id=$2 ;; --inventory) inventory=$2 ;; --jar) jar_file=$2 ;;
    --partitions) partitions=$2 ;; --executors) executors=$2 ;; --executor-cores) executor_cores=$2 ;;
    --executor-heap-gib) executor_heap=$2 ;; --overhead-gib) overhead=$2 ;;
    --repetitions) repetitions=$2 ;; --warmups) warmups=$2 ;;
    *) die "Unknown option: $1" ;;
  esac
  shift 2
done
[[ $cluster_id =~ ^j-[A-Za-z0-9]+$ && $region =~ ^[a-z0-9-]+$ ]] || die 'Supply --cluster-id and --region'
[[ $s3_prefix =~ ^s3://[a-z0-9.-]+/[A-Za-z0-9/_-]+$ ]] || die 'Supply --s3-prefix s3://BUCKET/PREFIX (letters, digits, /, _ and - in prefix)'
[[ $benchmark == cholesky || $benchmark == cg ]] || die 'Choose --benchmark cholesky or cg'
[[ $case_id =~ ^[A-Za-z0-9_-]+$ && -f $inventory ]] || die 'Supply a case ID and readable inventory'
inventory_name=$(basename "$inventory")
[[ $inventory_name =~ ^[a-z0-9-]+\.csv$ ]] || die 'Inventory filename must be a lowercase campaign slug ending in .csv'
for numeric_value in "$partitions" "$executors" "$executor_cores" "$executor_heap" "$overhead" "$repetitions"; do
  [[ $numeric_value =~ ^[1-9][0-9]*$ ]] || die 'Resource counts and repetitions must be positive integers'
done
[[ $warmups == 0 || $warmups == 1 ]] || die 'Warmups must be 0 or 1'
command -v jq >/dev/null || die 'Install jq'
if ! $dry_run; then command -v aws >/dev/null || die 'Install AWS CLI'; fi
case_row=$(awk -F, -v id="$case_id" '
  NR == 1 && $0 != "id,m,n,nonzeros_per_row,family,seed,tolerance,heap_gib" { exit 1 }
  NR > 1 && $1 == id { row=$0; count++ }
  END { if (count != 1) exit 1; print row }
' "$inventory") || die 'Expected shared CSV schema and exactly one matching case'
IFS=, read -r selected_case rows variables width family seed tolerance driver_heap <<< "$case_row"
[[ $driver_heap =~ ^[1-9][0-9]*$ && $rows =~ ^[1-9][0-9]*$ && $variables =~ ^[1-9][0-9]*$ && $width =~ ^[1-9][0-9]*$ ]] || die 'Invalid case dimensions/heap'
awk -v m="$rows" -v n="$variables" -v w="$width" 'BEGIN { exit !(m < n && w <= n) }' || die 'Invalid case dimensions'
[[ $family =~ ^(well|wide|dependent|degenerate|dense)$ && $seed =~ ^-?[0-9]+$ ]] || die 'Invalid case family/seed'
awk -v t="$tolerance" 'BEGIN { exit !(t ~ /^[0-9]+([.][0-9]+)?([eE][-+]?[0-9]+)?$/ && t+0 > 0) }' || die 'Invalid case tolerance'
if [[ $benchmark == cholesky ]]; then
  awk -v m="$rows" -v n="$variables" -v w="$width" -v family="$family" -v e="$executors" -v q="$executor_cores" -v dh="$driver_heap" -v eh="$executor_heap" 'BEGIN {
    z = family == "dense" ? m*n : m*(w < n-m+1 ? w : n-m+1)
    if (family == "dependent") z *= 2
    driver = 16*m*m+64*m; executor = (12*z+104*n)/e+8*q*m*(m+1)+8*q*m
    exit !(driver <= dh*1073741824/2 && executor <= eh*1073741824/2)
  }' || die 'ResourceExcluded: Cholesky numeric payload exceeds half the driver or executor heap; no step submitted'
fi

bundle=$(mktemp -d "${TMPDIR:-/tmp}/spark-lp-emr.XXXXXXXX")
run_id="$(date -u +%Y%m%dT%H%M%SZ)-${benchmark}-${case_id}-${bundle##*.}"
run_uri="$s3_prefix/$run_id"
source_hash=unrecorded implementation_sha=unrecorded
if [[ -z $jar_file ]]; then
  implementation_sha=$(git -C "$repo_dir" rev-parse HEAD)
  (cd "$repo_dir" && sbt 'benchmarksSpark_3_52_12/assembly') >"$bundle/build.log" 2>&1 || die "Build failed: $bundle/build.log"
  shopt -s nullglob
  jars=("$repo_dir"/benchmarks/target/spark_3.5-jvm-2.12/benchmarks-assembly-*.jar)
  ((${#jars[@]} == 1)) || die 'Expected one assembly; supply --jar explicitly'
  jar_file=${jars[0]}
  tar --exclude=target --exclude=__pycache__ -czf "$bundle/sources.tar.gz" -C "$repo_dir" \
    build.sbt project spark-lp/src benchmarks/src/main benchmarks/scripts
  source_hash=$(shasum -a 256 "$bundle/sources.tar.gz" | awk '{print $1}')
fi
[[ -f $jar_file ]] || die "Missing assembly: $jar_file"
cp "$jar_file" "$bundle/benchmarks.jar"
cp "$inventory" "$bundle/$inventory_name"
cp "$repo_dir/benchmarks/scripts/reconcile.py" "$bundle/reconcile.py"
jar_hash=$(shasum -a 256 "$bundle/benchmarks.jar" | awk '{print $1}')
inventory_hash=$(shasum -a 256 "$bundle/$inventory_name" | awk '{print $1}')
cluster_name=DRY_RUN computer=DRY_RUN
if ! $dry_run; then
  aws --region "$region" emr describe-cluster --cluster-id "$cluster_id" --output json > "$bundle/cluster.json"
  aws --region "$region" emr list-instances --cluster-id "$cluster_id" --output json > "$bundle/instances.json"
  jq -e '.Cluster.Status.State == "WAITING" and (.Cluster.StepConcurrencyLevel // 1) == 1' "$bundle/cluster.json" >/dev/null || die 'Use an idle WAITING cluster with step concurrency 1'
  cluster_name=$(jq -r '.Cluster.Name' "$bundle/cluster.json")
  computer=$(jq -r '[.Instances[].InstanceType] | unique | join(", ")' "$bundle/instances.json")
fi
jq -n --arg run_id "$run_id" --arg cluster_id "$cluster_id" --arg region "$region" --arg uri "$run_uri" \
  --arg jar_sha256 "$jar_hash" --arg source_archive_sha256 "$source_hash" --arg inventory_sha256 "$inventory_hash" \
  --arg implementation_sha "$implementation_sha" \
  --arg case_id "$case_id" --arg benchmark "$benchmark" --arg inventory "$inventory_name" \
  --argjson repetitions "$repetitions" --argjson warmups "$warmups" \
  '{run_id:$run_id,cluster_id:$cluster_id,region:$region,artifact_uri:$uri,jar_sha256:$jar_sha256,
    source_archive_sha256:$source_archive_sha256,inventory_sha256:$inventory_sha256,implementation_sha:$implementation_sha,
    expected_measured:$repetitions,
    case:$case_id,benchmark:$benchmark,inventory:$inventory,repetitions:$repetitions,warmups:$warmups}' > "$bundle/manifest.json"

# The step receives data as positional arguments, never interpolated shell code.
cat > "$bundle/execute.sh" <<'REMOTE'
#!/usr/bin/env bash
set -euo pipefail
region=$1 run_uri=$2 inventory_name=$3 benchmark=$4 case_id=$5 partitions=$6
executors=$7 executor_cores=$8 executor_heap=$9
shift 9
overhead=$1 driver_heap=$2 repetitions=$3 warmups=$4 cluster_name=$5 computer=$6 source_hash=$7 jar_hash=$8 inventory_hash=$9
shift 9
implementation_sha=$1
work=$(mktemp -d /tmp/spark-lp-benchmark.XXXXXXXX)
output="$work/results"
mkdir "$output"
finish() {
  code=$?
  trap - EXIT
  if ! python3 "$work/reconcile.py" "$output" "$work/manifest.json" "$work/$inventory_name" "$code"; then
    printf 'Record reconciliation failed; retain step logs and inputs.\n' >&2
    if ((code == 0)); then code=1; fi
  fi
  printf '{"exit_code":%s}\n' "$code" > "$output/exit.json"
  if ! aws --region "$region" s3 cp "$output/" "$run_uri/results/" --recursive --only-show-errors; then
    printf 'Artifact upload failed; retained local output: %s\n' "$output" >&2
    if ((code == 0)); then code=1; fi
  fi
  exit "$code"
}
trap finish EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
aws --region "$region" s3 cp "$run_uri/input/reconcile.py" "$work/reconcile.py" --only-show-errors
aws --region "$region" s3 cp "$run_uri/input/manifest.json" "$work/manifest.json" --only-show-errors
aws --region "$region" s3 cp "$run_uri/input/benchmarks.jar" "$work/benchmarks.jar" --only-show-errors
aws --region "$region" s3 cp "$run_uri/input/$inventory_name" "$work/$inventory_name" --only-show-errors
printf '%s  %s\n' "$jar_hash" "$work/benchmarks.jar" | sha256sum -c -
printf '%s  %s\n' "$inventory_hash" "$work/$inventory_name" | sha256sum -c -
# Spark tokenizes this string separately, so quote individual Java options.
quote_java_option() {
  local value=${1//\\/\\\\}
  value=${value//\"/\\\"}
  printf '"%s"' "$value"
}
java_options="$(quote_java_option "-Dbenchmark.emrName=$cluster_name") $(quote_java_option "-Dbenchmark.computer=$computer") -Dbenchmark.sourceHash=$source_hash -Dbenchmark.jarHash=$jar_hash -Dbenchmark.sha=$implementation_sha"
export OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 MKL_NUM_THREADS=1
command=(spark-submit --master yarn --deploy-mode client --driver-memory "${driver_heap}g"
  --num-executors "$executors" --executor-cores "$executor_cores" --executor-memory "${executor_heap}g"
  --conf "spark.executor.memoryOverhead=${overhead}g" --conf spark.task.cpus=1
  --conf spark.driver.maxResultSize=0
  --conf spark.eventLog.logStageExecutorMetrics=true
  --conf spark.executor.processTreeMetrics.enabled=true
  --conf spark.executor.metrics.pollingInterval=1000
  --conf spark.dynamicAllocation.enabled=false --conf spark.speculation=false
  --conf "spark.sql.shuffle.partitions=$partitions" --conf "spark.eventLog.dir=$run_uri/events/"
  --conf spark.eventLog.enabled=true --conf spark.executorEnv.OPENBLAS_NUM_THREADS=1
  --conf spark.executorEnv.OMP_NUM_THREADS=1 --conf spark.executorEnv.MKL_NUM_THREADS=1
  --driver-java-options "$java_options" --class com.github.vbmacher.spark_lp.BenchmarkRunner
  "$work/benchmarks.jar" "$benchmark" "$output" "$work/$inventory_name" "$case_id" "$partitions" "$repetitions" "$warmups")
printf '%q ' "${command[@]}" > "$output/command.sh"
printf '\n' >> "$output/command.sh"
timeout --signal=TERM --kill-after=60 "$(((repetitions+warmups)*1800+600))s" "${command[@]}" 2>&1 | tee "$output/application.log"
REMOTE
bootstrap='set -euo pipefail; script=$(mktemp /tmp/spark-lp-step.XXXXXXXX); aws --region "$1" s3 cp "$2/input/execute.sh" "$script" --only-show-errors; exec bash "$script" "$@"'
jq -n --arg name "spark-lp $benchmark $case_id" --args \
  '[{Type:"CUSTOM_JAR",Name:$name,ActionOnFailure:"CONTINUE",Jar:"command-runner.jar",Args:$ARGS.positional}]' \
  -- bash -c "$bootstrap" spark-lp-step "$region" "$run_uri" "$inventory_name" "$benchmark" "$case_id" "$partitions" \
  "$executors" "$executor_cores" "$executor_heap" "$overhead" "$driver_heap" "$repetitions" "$warmups" \
  "$cluster_name" "$computer" "$source_hash" "$jar_hash" "$inventory_hash" "$implementation_sha" > "$bundle/steps.json"
printf 'Run artifacts: %s\nLocal bundle: %s\n' "$run_uri" "$bundle"
if $dry_run; then
  printf 'Dry run: no AWS calls made. Step specification: %s/steps.json\n' "$bundle"
  exit 0
fi
aws --region "$region" s3 cp "$bundle/" "$run_uri/input/" --recursive --only-show-errors
touch "$bundle/events.keep"
aws --region "$region" s3 cp "$bundle/events.keep" "$run_uri/events/.keep" --only-show-errors
aws --region "$region" emr add-steps --cluster-id "$cluster_id" --steps "file://$bundle/steps.json" --output json > "$bundle/submission.json"
cat "$bundle/submission.json"
aws --region "$region" s3 cp "$bundle/submission.json" "$run_uri/submission.json" --only-show-errors
