#!/usr/bin/env python3
"""Generate and check the Bencher result bundle without running or uploading benchmarks."""
import argparse
import collections
import json
from pathlib import Path
import re
import statistics

from report import (DATA, NOT_ATTEMPTED, canonical, digest, environment_values, is_valid,
                    omit_storage_locations, validate)


def metric(values, scale=1):
    values = [value * scale for value in values]
    return dict(value=statistics.median(values), lower_value=min(values), upper_value=max(values))


def metrics(records, config):
    result = {}
    measured = [r for r in records if not r['warmup']]
    valid = [r for r in measured if is_valid(r, config)]
    for phase, rows in [('measured', measured), ('warmup', [r for r in records if r['warmup']])]:
        attempted = [r for r in rows if r['status'] not in NOT_ATTEMPTED]
        converged = sum(is_valid(r, config) for r in attempted)
        counts = dict(records=len(rows), attempted=len(attempted), converged=converged,
                      failed=len(attempted) - converged,
                      unrun=sum(r['status'] == 'Unrun' for r in rows),
                      excluded=sum(r['status'] == 'ResourceExcluded' for r in rows),
                      unknown=sum(r['status'] in ('MissingEvidence', 'Pending') for r in rows))
        result.update({f'{phase}-{name}-count': {'value': value} for name, value in counts.items()})
    if valid:
        result['latency'] = metric([r['solve_seconds'] for r in valid], scale=1e9)
        for field in ('outer_iterations', 'cg_steps', 'cg_restarts', 'rank_escalations', 'maximum_rank'):
            values = [r[field] for r in valid]
            if all(value is not None for value in values):
                result[field.replace('_', '-')] = metric(values)
        for field in ('primal', 'dual', 'gap', 'objective_error'):
            values = [r['residuals'].get(field) for r in valid]
            if all(value is not None for value in values):
                result[f'{field.replace("_", "-")}-max'] = {'value': max(values)}
    failed_times = [r['solve_seconds'] for r in measured
                    if r['status'] not in NOT_ATTEMPTED and not is_valid(r, config)
                    and r['solve_seconds'] is not None]
    if failed_times:
        result['unsuccessful-duration-ns'] = metric(failed_times, scale=1e9)
    master = environment_values(config)['spark_master'] or ''
    scope = ('combined-process' if master == 'local' or master.startswith('local[')
             else 'driver' if master else 'process')
    for field in ('heap', 'rss'):
        values = [r['memory'].get(f'peak_{field}_bytes') for r in measured]
        values = [value for value in values if value is not None]
        if values:
            result[f'{scope}-{field}-bytes-max'] = {'value': max(values)}
    return result


def testbed(config):
    settings = dict(environment=environment_values(config), topology=config.get('memory_topology'),
                    partitions=config.get('partitions'), heap_gib=config.get('heap_gib'))
    master = settings['environment']['spark_master'] or 'unrecorded'
    prefix = re.sub(r'[^a-z0-9]+', '-', master.lower()).strip('-')
    return f'{prefix}-{digest(canonical(settings).encode())[:12]}', settings


def result_filename(campaign_id, config):
    bed, _ = testbed(config)
    return f'{campaign_id}--{bed}.bmf.json'


def build_export(campaigns, executor_memory):
    """One BMF data directory with a sibling manifest preserving every captured attempt."""
    payloads, reports = {}, {}
    if len({c['campaign_id'] for c in campaigns}) != len(campaigns):
        raise ValueError('Duplicate campaign ID')
    for campaign in sorted(campaigns, key=lambda c: c['campaign_id']):
        validate(campaign)
        if not re.fullmatch(r'[a-z0-9][a-z0-9-]*', campaign['campaign_id']):
            raise ValueError('Campaign ID must be a lowercase slug')
        serialized = canonical(campaign)
        if omit_storage_locations(serialized) != serialized:
            raise ValueError('Campaign metadata contains private infrastructure information')
        configs = {c['configuration_id']: c for c in campaign['configurations']}
        groups = collections.defaultdict(list)
        for row in campaign['records']:
            groups[(row['case_id'], row['configuration_id'])].append(row)
        for (case_id, config_id), records in sorted(groups.items()):
            config = configs[config_id]
            bed, settings = testbed(config)
            filename = result_filename(campaign['campaign_id'], config)
            name = f'{case_id} / {config["algorithm"]} / {config_id}'
            payload = payloads.setdefault(filename, {})
            if name in payload:
                raise ValueError(f'Duplicate Bencher benchmark: {name}')
            payload[name] = metrics(records, config)
            entry = reports.setdefault(filename, dict(file=filename, testbed=bed, settings=settings,
                                                       campaign_id=campaign['campaign_id'], benchmarks={}))
            entry['benchmarks'][name] = dict(case_id=case_id, configuration_id=config_id)
    if executor_memory:
        filename = 'executor-memory.bmf.json'
        payload = {}
        for row in executor_memory:
            if omit_storage_locations(canonical(row)) != canonical(row):
                raise ValueError('Executor metadata contains private infrastructure information')
            name = f'{row["case"]} / {row["backend"]} / {row["variant"]} / executor {row["executor_id"]}'
            if name in payload:
                raise ValueError(f'Duplicate executor observation: {name}')
            payload[name] = {f'executor-{field}-bytes-max': {'value': int(row[column])}
                for field, column in [('heap', 'peak_heap_bytes'), ('rss', 'peak_rss_bytes'),
                                      ('jvm-nonheap', 'peak_jvm_nonheap_bytes')] if row[column]}
        payloads[filename] = payload
        reports[filename] = dict(file=filename, testbed='executor-memory-unmapped',
            note='Whole-application executor peaks; original configuration and testbed are not recorded.')
    if not payloads:
        raise ValueError('No captured records found')
    manifest = dict(schema_version=1, format='Bencher Metric Format v0',
                    evidence_records=sum(len(c['records']) for c in campaigns),
                    campaigns=sorted(campaigns, key=lambda c: c['campaign_id']),
                    executor_observations=len(executor_memory), executor_memory=executor_memory,
                    reports=list(reports.values()))
    files = {f'data/{name}': json.dumps(payload, indent=2, sort_keys=True, allow_nan=False) + '\n'
             for name, payload in payloads.items()}
    files['manifest.json'] = json.dumps(manifest, indent=2, sort_keys=True, allow_nan=False) + '\n'
    return files


def read_bundle(data, check=True):
    root = data.parent
    manifest = json.loads((root / 'manifest.json').read_text())
    if manifest.get('schema_version') != 1 or manifest.get('format') != 'Bencher Metric Format v0':
        raise ValueError('Unsupported Bencher bundle format')
    campaigns, memory = manifest['campaigns'], manifest['executor_memory']
    files = build_export(campaigns, memory)
    if check:
        expected = {Path(name).name for name in files if name.startswith('data/')}
        actual = {p.name for p in data.iterdir()}
        if actual != expected:
            raise ValueError('Bencher data files are missing, stale or unexpected')
        for name, content in files.items():
            path = data / Path(name).name if name.startswith('data/') else root / name
            if json.loads(path.read_text()) != json.loads(content):
                raise ValueError(f'Bencher metrics or metadata differ from captured evidence: {name}')
    return campaigns, memory


def write_bundle(campaigns, executor_memory, output):
    files = build_export(campaigns, executor_memory)
    data = output / 'data'
    expected = {Path(name).name for name in files if name.startswith('data/')}
    if data.exists() and {p.name for p in data.iterdir()} - expected:
        raise ValueError('Data contains stale or unrelated files; choose a fresh output directory')
    data.mkdir(parents=True, exist_ok=True)
    for name, content in files.items():
        (output / name).write_text(content, encoding='utf-8')
    return len(files) - 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, default=DATA)
    parser.add_argument('--output', type=Path, help='Destination bundle root (contains data/ and manifest.json)')
    parser.add_argument('--check', action='store_true', help='Check BMF metrics against every recorded attempt')
    args = parser.parse_args()
    if args.check and args.output:
        parser.error('--check cannot be combined with --output')
    campaigns, memory = read_bundle(args.data, check=args.check)
    if args.check:
        print(f'{len(campaigns)} campaigns; Bencher bundle check OK: {args.data}')
    else:
        output = args.output or args.data.parent
        count = write_bundle(campaigns, memory, output)
        print(f'{count} BMF files; bundle write OK: {output}')


if __name__ == '__main__':
    main()
