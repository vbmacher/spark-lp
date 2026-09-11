#!/usr/bin/env bash
# Package once, then print a launch command for an existing cluster. Does not provision or submit.
set -euo pipefail
sbt 'spark-lpSpark_3_52_12/Test/packageBin' 'spark-lpSpark_3_52_12/assembly'
cat <<'COMMAND'
# Replace placeholders with the paths printed by sbt and shared artifact storage.
# Run in client mode on the fixed driver host; cases.csv must be present there.
spark-submit --master <existing-cluster-master> --deploy-mode client \
  --driver-cores 4 --driver-memory 8g --num-executors 4 \
  --executor-cores 4 --executor-memory 8g \
  --conf spark.dynamicAllocation.enabled=false --conf spark.speculation=false \
  --conf spark.eventLog.enabled=true --conf spark.eventLog.dir=<shared-event-log-uri> \
  --driver-java-options '-Dbenchmark.sha=<candidate-sha> -Dbenchmark.sourceHash=<manifest-digest>' \
  --jars <main-assembly.jar> \
  --class com.github.vbmacher.spark_lp.benchmarks.MatrixFreeBenchmark \
  <test-package.jar> <absolute-existing-output-directory> <absolute-cases.csv> <case-id> <cholesky-or-cg> 64
COMMAND
