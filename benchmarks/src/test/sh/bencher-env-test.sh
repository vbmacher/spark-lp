#!/bin/sh
# Exercise the launcher in an isolated checkout with synthetic credentials only.
set -eu
repository=$(CDPATH= cd -- "$(dirname -- "$0")/../../../.." && pwd)
fixture=$(mktemp -d "${TMPDIR:-/tmp}/spark-lp-bencher-env.XXXXXXXX")
trap 'rm -rf "$fixture"' EXIT HUP INT TERM
mkdir "$fixture/benchmarks" "$fixture/bin"
cp "$repository/benchmarks/bench" "$fixture/benchmarks/bench"
printf '%s\n' '#!/bin/sh' \
  'test -z "${BENCHER_TESTBED+x}" || exit 99' \
  'printf "%s|%s|%s|%s\n" "${BENCHER_PROJECT-unset}" "${BENCHER_BRANCH-unset}" "${BENCHER_API_KEY-unset}" "$*"' \
  > "$fixture/bin/bencher"
printf '%s\n' '#!/bin/sh' \
  'case "$SPARK_LP_BENCH_ARGS" in' \
  '  _testbed*)' \
  '    if [ "${FAKE_TESTBED_FAILURE-}" = 1 ]; then exit 1; fi' \
  '    destination=$(printf "%s\n" "$SPARK_LP_BENCH_ARGS" | tail -n 1)' \
  '    printf "%s\n" "automatic-testbed" > "$destination" ;;' \
  '  *) printf "%s\n" "${BENCHER_API_KEY-unset}" ;;' \
  'esac' > "$fixture/bin/sbt"
chmod +x "$fixture/bin/bencher" "$fixture/bin/sbt"
PATH="$fixture/bin:$PATH"
export PATH
unset BENCHER_PROJECT BENCHER_TESTBED BENCHER_BRANCH BENCHER_API_KEY FAKE_TESTBED_FAILURE
cd "$fixture/bin"
launcher="$fixture/benchmarks/bench"
check() {
  expected=$1
  shift
  actual=$("$@")
  if [ "$actual" != "$expected" ]; then printf 'Launcher check failed\n' >&2; exit 1; fi
}
reject() {
  status=0
  sh "$launcher" bencher run > "$fixture/stdout" 2> "$fixture/stderr" || status=$?
  test "$status" = 2
  test ! -s "$fixture/stdout"
  # Error messages must not echo even synthetic secret-bearing lines.
  if grep -q 'synthetic-secret' "$fixture/stderr"; then exit 1; fi
}

check 'unset|unset|unset|--version' sh "$launcher" bencher --version
BENCHER_PROJECT=environment
export BENCHER_PROJECT
check 'environment|unset|unset|run --testbed automatic-testbed --dry-run' sh "$launcher" bencher run --dry-run
unset BENCHER_PROJECT

printf '%s\r\n' '# comment' '' ' export BENCHER_PROJECT = "file project" ' \
  "BENCHER_BRANCH='file branch'" > "$fixture/.bencher.env"
# Deliberately omit the final newline and include shell syntax that must remain literal.
printf '%s' 'BENCHER_API_KEY=$(touch injected);synthetic-secret=x' >> "$fixture/.bencher.env"
check 'file project|file branch|$(touch injected);synthetic-secret=x|run --testbed automatic-testbed --dry-run' sh "$launcher" bencher run --dry-run
test ! -e "$fixture/injected"
BENCHER_PROJECT=environment
BENCHER_API_KEY=
export BENCHER_PROJECT BENCHER_API_KEY
check 'environment|file branch||run --testbed automatic-testbed' sh "$launcher" bencher run
unset BENCHER_PROJECT BENCHER_API_KEY

printf '%s\n' 'BENCHER_API_KEY="synthetic-secret' > "$fixture/.bencher.env"
reject
printf '%s\n' 'PATH=synthetic-secret' > "$fixture/.bencher.env"
reject
printf '%s\n' 'BENCHER_BAD-NAME=synthetic-secret' > "$fixture/.bencher.env"
reject
printf '%s\n' 'synthetic-secret' > "$fixture/.bencher.env"
reject
# Non-publication commands never parse the credential file, even when malformed.
check unset sh "$launcher" list
printf '%s\n' 'BENCHER_TESTBED=workstation' > "$fixture/.bencher.env"
reject
printf '%s\n' '# no manual testbed' > "$fixture/.bencher.env"
BENCHER_TESTBED=ci-runner
export BENCHER_TESTBED
check 'unset|unset|unset|run --testbed automatic-testbed' sh "$launcher" bencher run
for override in '--testbed' '--testbed=manual'; do
  status=0
  sh "$launcher" bencher run "$override" > "$fixture/stdout" 2> "$fixture/stderr" || status=$?
  test "$status" = 2
  test ! -s "$fixture/stdout"
done
FAKE_TESTBED_FAILURE=1
export FAKE_TESTBED_FAILURE
status=0
sh "$launcher" bencher run > "$fixture/stdout" 2> "$fixture/stderr" || status=$?
test "$status" != 0
test ! -s "$fixture/stdout"
check 'unset|unset|unset|--version' sh "$launcher" bencher --version
printf '%s\n' 'Bencher launcher checks passed'
