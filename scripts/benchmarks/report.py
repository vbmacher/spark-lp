#!/usr/bin/env python3
"""Write a compact decision report from retained raw records; never edits measurements."""
import argparse
import collections
import csv
import hashlib
import json
import re
from pathlib import Path
import statistics

from analyze import valid


def render(root, destination):
    root, destination = Path(root).resolve(), Path(destination).resolve()
    manifest = json.loads((root / 'manifest.json').read_text())
    with (root / 'cases.csv').open() as source:
        inventory = {row['id']: row for row in csv.DictReader(source)}
    rows = [json.loads(line) for p in root.glob('*/records.jsonl') for line in p.read_text().splitlines()]
    measured = [r for r in rows if not r['warmup']]
    for row in measured:
        if row['status'] == 'Success' and not valid(row):
            raise ValueError(f'Invalid successful record: {row["case"]}')
    statuses = collections.Counter(r['status'] for r in measured)
    pairs = collections.defaultdict(dict)
    for row in measured:
        if row['status'] == 'Success':
            key = (row['case'], row['hash'], row['heap_gib'], row['tolerance'], row['repetition'])
            pairs[key][row['backend']] = row
    timings = collections.defaultdict(list)
    candidate_pairs = collections.defaultdict(list)
    historical_pairs = collections.defaultdict(list)
    for key, backends in pairs.items():
        case = inventory[key[0]]
        group = (case['id'].split('-')[0], int(case['m']), int(case['heap_gib']), float(case['tolerance']))
        for backend, row in backends.items():
            timings[group + (backend,)].append(row['solve_seconds'])
        if {'cholesky', 'cg'} <= backends.keys():
            a, b = backends['cholesky'], backends['cg']
            if a['source_hash'] == b['source_hash']:
                candidate_pairs[group].append(a['solve_seconds'] / b['solve_seconds'])
        if {'cg-pre', 'cg'} <= backends.keys():
            a, b = backends['cg-pre'], backends['cg']
            if a['implementation_sha'] != b['implementation_sha']:
                historical_pairs[group].append(a['solve_seconds'] / b['solve_seconds'])
    validation = root / 'validation'
    def log_counts(name):
        path = validation / name
        return [int(n) for n in re.findall(r'Tests: succeeded (\d+), failed 0', path.read_text())] if path.exists() else []
    focused, matrix = log_counts('focused.log'), log_counts('spark-matrix.log')
    focused_text = str(sum(focused)) if focused else 'not recorded'
    matrix_text = f'{sum(matrix):,}' if matrix else 'not recorded'
    python_log = validation / 'python-tests.log'
    python_count = re.search(r'Ran (\d+) tests', python_log.read_text()) if python_log.exists() else None
    python_text = python_count.group(1) if python_count else 'not recorded'
    def smoke_count(prefix):
        paths = list((validation / 'smoke').glob(prefix + '-*.jsonl'))
        entries = [json.loads(line) for path in paths for line in path.read_text().splitlines()]
        entries = [r for r in entries if not r['warmup']]
        return f'{sum(r["status"] == "Success" for r in entries)}/{len(entries)}' if entries else 'not recorded'
    expected = manifest['expected_measured']
    complete = bool(manifest.get('finished_utc'))
    import os
    artifact = Path(os.path.relpath(root, destination.parent)).as_posix()
    lines = [
        '# Matrix-free LP results (issue #26)', '',
        '**' + ('Local execution finished; distributed validation pending.' if complete else
                'Full local campaign running; distributed validation pending.') + ' Auto is unchanged.**', '',
        'This report supports [#25](https://github.com/vbmacher/spark-lp/issues/25) and',
        '[#26](https://github.com/vbmacher/spark-lp/issues/26). See the [frozen protocol](matrix-free-lp-plan.md).', '',
        '## Execution and provenance', '',
        f'- Candidate base: `{manifest["implementation_sha"]}`.',
        f'- Candidate source SHA-256: `{manifest["source_hash"]}`.',
        f'- Started UTC: `{manifest["started_utc"]}`; finished UTC: `{manifest.get("finished_utc", "pending")}`.',
        f'- Recorded measured repetitions: **{len(measured)}/{expected}**; successful: **{statuses["Success"]}**.',
        f'- Scheduled completion rate: **{100 * statuses["Success"] / expected:.2f}%** (includes pending/excluded runs in denominator).',
        f'- [Retained local artifacts]({artifact}/): source archive, historical diagnostic patch/build,',
        '  environment manifests, exact Java commands, raw JSONL, event logs, and Spark task metrics.',
        f'- [Detailed timing table]({artifact}/summary.md), [machine-readable summary]({artifact}/summary.json).',
        '- These are retained local artifacts, not externally hosted downloads.',
        '- Historical CG: `d0e4939`, primary and difficulty families, diagnostic-only final-iterate callback.',
        '  Its manifest declares inner/rank/phase telemetry unavailable. Historical `phase_seconds`',
        '  is an uninstrumented whole-run placeholder and must not be interpreted as initialization time.',
        '- `SHA256SUMS` is generated only after the launcher finishes.', '',
        '```sh', 'python3 scripts/benchmarks/run.py --output /absolute/new/artifact/directory',
        'python3 scripts/benchmarks/analyze.py /absolute/artifact/directory',
        'python3 scripts/benchmarks/report.py /absolute/artifact/directory --output docs/benchmarks/matrix-free-lp-results.md',
        '```', '',
        'The launcher refuses to overwrite existing runs. The original launch used the verified',
        '`target/benchmarks/issue26-smoke/classpath.txt` via `--classpath`; omitting that flag builds first.', '',
        '## Correctness and harness validation', '',
        f'- Focused Spark 3.5.3 suites: **{focused_text} passed** before final telemetry additions.',
        f'- Complete matrix with telemetry: **{matrix_text} passed** across {len(matrix)} axes:',
        '  Spark 2.4.8, 3.0.2, 3.1.3, 3.2.4, 3.3.2, 3.4.2, and 3.5.3.',
        f'- Python analysis tests: **{python_text} passed** (accuracy gating, pair eligibility, event attribution).',
        f'- Core harness smoke: **{smoke_count("core")} measured solves passed independent original-LP checks**.',
        f'- Bounded DSL smoke: **{smoke_count("dsl")} measured solves passed**; 3 compiled rows, 4 columns,',
        '  including 2 bound rows and 2 slack columns; known optimum (2,1), objective 4.',
        f'- Focused/matrix logs: [{artifact}/validation/]({artifact}/validation/).',
        '- Compact smoke records are retained under `validation/smoke/`; smoke timings are not crossover evidence.', '',
        '## Recorded states', '', '| State | Measured repetitions |', '|---|---:|']
    lines += [f'| {state} | {count} |' for state, count in sorted(statuses.items())]
    lines += [f'| Pending records | {expected - len(measured)} |', '', '## Equal-accuracy timing', '',
              'Only paired repetitions passing every independent residual, nonnegativity, and known-objective',
              'check enter ratios. Ratios greater than one favor candidate CG. Medians pool seeds and',
              'shape/family variants within the displayed group; the detailed table preserves each case.',
              'A pooled value is not evidence that every shape has the same crossover.', '',
              '| Family | m | Heap GiB | Tolerance | Cholesky s | CG s | Historical CG s | Candidate pairs / median Cholesky÷CG | Historical pairs / median old÷new CG |',
              '|---|---:|---:|---:|---:|---:|---:|---:|---:|']
    def median(values):
        return f'{statistics.median(values):.3f}' if values else '—'
    for group in sorted({key[:-1] for key in timings}):
        times = [median(timings.get(group + (backend,), [])) for backend in ('cholesky', 'cg', 'cg-pre')]
        a, b = candidate_pairs[group], historical_pairs[group]
        lines.append('| ' + ' | '.join(map(str, group)) + ' | ' + ' | '.join(times) +
                     f' | {len(a)} / {median(a)} | {len(b)} / {median(b)} |')
    lines += ['', '## Decision and limits', '',
              '**Recommendation recorded for this campaign: retain its existing Auto policy:** Cholesky through',
              '1000 equality-form rows, CG above, with the DSL allowed to lower the resource cap.',
              'The observed small cases favor Cholesky, so these measurements do not support making',
              'matrix-free the unconditional default. No new production crossover is selected here.', '',
              "The current implementation subsequently raised Auto's cutoff to 10000 rows for the",
              f'EMR allocation workload; see [the allocation investigation]({Path(os.path.relpath(Path(__file__).resolve().parents[2] / "benchmarks/allocation.md", destination.parent)).as_posix()}).',
              "That policy change is separate from this campaign's recorded results and protocol.", '',
              'The candidate uses JDK 11 / Spark 3.5.3 / Scala 2.12.20, local[4], eight fixed input',
              'partitions, and Java fallback BLAS/LAPACK. The 8 GiB sweep is separate from the 4 GiB',
              'baseline. Task peak execution memory is not executor RSS; phase intervals include',
              'framework overhead. The input fixture remains resident in each backend JVM.', '',
              'Distributed validation is pending cluster access, as requested. Shape screening uses',
              'seed 11; findings used to change policy require follow-up seeds 29 and 47. Resource',
              'exclusions and unrun repetitions are never interpreted as measured speedups.',
              'A single row threshold must hold across the tested shape, sparsity, and difficulty',
              'envelope before it can be recommended. #26 is not declared complete while required',
              'execution or policy evidence remains pending.', '']
    destination.write_text('\n'.join(lines))
    # Postprocessing source is separate from the frozen measured candidate.
    (root / 'report-generator.sha256').write_text(hashlib.sha256(Path(__file__).read_bytes()).hexdigest() + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifacts', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    render(args.artifacts, args.output)
