#!/usr/bin/env python3
"""Validate records, retain every state, summarize equal-accuracy pairs and Spark events."""
import collections
import hashlib
import json
from pathlib import Path
import math
import statistics
import sys


def valid(row):
    r = row.get('residuals', {})
    return (all(key in r and math.isfinite(r[key]) and 0 <= r[key] < row['tolerance']
                for key in ['primal', 'dual', 'gap', 'objective_error']) and
            all(key in r and math.isfinite(r[key]) and r[key] >= -row['tolerance'] for key in ['min_x', 'min_s']))


def events(batch):
    groups, stages = {}, collections.defaultdict(set)
    counts = collections.defaultdict(collections.Counter)
    for path in sorted((batch / 'events').glob('*')):
        if not path.is_file() or path.name.startswith('.'):
            continue
        with path.open(errors='replace') as event_file:
            for line in event_file:
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue  # A killed Spark application can leave an unfinished final line.
                kind = event.get('Event')
                if kind == 'SparkListenerJobStart':
                    group = (event.get('Properties') or {}).get('spark.jobGroup.id')
                    if group:
                        groups[event['Job ID']] = group
                        counts[group]['jobs'] += 1
                        for stage in event.get('Stage IDs', []):
                            stages[stage].add(group)
                elif kind == 'SparkListenerStageCompleted':
                    for group in stages[event['Stage Info']['Stage ID']]:
                        counts[group]['stage_attempts'] += 1
                elif kind == 'SparkListenerTaskEnd':
                    metrics = event.get('Task Metrics') or {}
                    for group in stages[event['Stage ID']]:
                        c = counts[group]
                        c['tasks'] += 1
                        c['gc_ms'] += metrics.get('JVM GC Time', 0)
                        c['shuffle_read_bytes'] += sum(metrics.get('Shuffle Read Metrics', {}).get(k, 0)
                                                      for k in ['Remote Bytes Read', 'Local Bytes Read'])
                        c['shuffle_write_bytes'] += metrics.get('Shuffle Write Metrics', {}).get('Shuffle Bytes Written', 0)
                        c['peak_task_execution_bytes'] = max(c['peak_task_execution_bytes'], metrics.get('Peak Execution Memory', 0))
    return dict(counts)


def main(directory):
    root = Path(directory)
    rows, spark = [], {}
    for path in sorted(root.glob('*/records.jsonl')):
        batch_rows = [json.loads(line) for line in path.read_text().splitlines()]
        rows.extend(batch_rows)
        # Parse completed event logs once, preserving originals.
        metrics = path.parent / 'spark-metrics.json'
        if not metrics.exists() and (path.parent / 'exit.json').exists():
            metrics.write_text(json.dumps(events(path.parent), indent=2) + '\n')
        if metrics.exists():
            spark.update(json.loads(metrics.read_text()))
    measured = [r for r in rows if not r['warmup']]
    for row in measured:
        if row['status'] == 'Success' and not valid(row):
            raise ValueError(f'Invalid claimed success: {row["case"]} {row["backend"]}')
    keys = [(r['case'], r['backend'], r['repetition']) for r in measured]
    if len(keys) != len(set(keys)):
        raise ValueError('Duplicate repetition')
    counts = collections.Counter(r['status'] for r in measured)
    by_case = collections.defaultdict(list)
    by_pair = collections.defaultdict(dict)
    for row in measured:
        by_case[(row['case'], row['backend'])].append(row)
        if row['status'] == 'Success':
            key = (row['case'], row['hash'], row['source_hash'], row['heap_gib'], row['tolerance'], row['repetition'])
            by_pair[key][row['backend']] = row['solve_seconds']
    pairs = collections.defaultdict(list)
    for key, times in by_pair.items():
        if 'cholesky' in times and 'cg' in times:
            pairs[key[0]].append(times['cholesky'] / times['cg'])
    summary = []
    for (case, backend), group in sorted(by_case.items()):
        times = [r['solve_seconds'] for r in group if r['status'] == 'Success']
        summary.append(dict(case=case, backend=backend, completed=len(group), successes=len(times),
                            states=dict(collections.Counter(r['status'] for r in group)),
                            median_seconds=statistics.median(times) if times else None,
                            min_seconds=min(times) if times else None, max_seconds=max(times) if times else None))
    result = dict(states=dict(counts), recorded_measured=len(measured),
                  expected_measured=(json.loads((root / 'manifest.json').read_text()).get('expected_measured')
                                     if (root / 'manifest.json').exists() else (len((root / 'cases.csv').read_text().splitlines()) - 1) * 10),
                  summary=summary, paired_speedups={k: dict(count=len(v), median=statistics.median(v),
                                                         min=min(v), max=max(v)) for k, v in pairs.items()})
    (root / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
    lines = ['| Case | Backend | Successes / recorded | Median s | Min s | Max s |',
             '|---|---|---:|---:|---:|---:|']
    def fmt(v): return '—' if v is None else f'{v:.3f}'
    for row in summary:
        lines.append(f'| {row["case"]} | {row["backend"]} | {row["successes"]}/{row["completed"]} | ' +
                     ' | '.join(fmt(row[k]) for k in ['median_seconds', 'min_seconds', 'max_seconds']) + ' |')
    (root / 'summary.md').write_text('\n'.join(lines) + '\n')
    if (root / 'manifest.json').exists() and 'finished_utc' in json.loads((root / 'manifest.json').read_text()):
        with (root / 'SHA256SUMS').open('w') as f:
            for path in sorted(root.rglob('*')):
                if path.is_file() and path.name != 'SHA256SUMS':
                    with path.open('rb') as source:
                        digest = hashlib.file_digest(source, 'sha256').hexdigest()
                    f.write(f'{digest}  {path.relative_to(root)}\n')


if __name__ == '__main__':
    main(sys.argv[1])
