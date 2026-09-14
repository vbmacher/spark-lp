#!/usr/bin/env python3
"""Normalize evidence and regenerate REPORT.md. Python standard library; no Spark required."""
import argparse
import collections
import csv
import hashlib
import io
import json
import math
from pathlib import Path
import re
import statistics

ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / 'benchmarks/src/results'
DATA = RESULTS / 'data'
SUCCESS = {'Converged', 'Optimal', 'Success'}
NOT_ATTEMPTED = {'Unrun', 'ResourceExcluded', 'MissingEvidence', 'Pending'}
def digest(value):
    return hashlib.sha256(value).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False)


def number(value):
    if value is None or value in ('', 'NA', 'NaN', 'None'):
        return None
    result = float(value)
    return result if math.isfinite(result) else None


def integer(value):
    value = number(value)
    if value is not None and value != int(value):
        raise ValueError('Expected an integer')
    return None if value is None else int(value)


def clean(value):
    if isinstance(value, float) and not math.isfinite(value):
        return None
    if isinstance(value, dict):
        return {k: clean(v) for k, v in value.items()}
    if isinstance(value, list):
        return [clean(v) for v in value]
    return value


def source(path, content, revision=None):
    return dict(path=path, revision=revision, sha256=digest(content),
                content_utf8=content.decode('utf-8'))


def campaign(cid, title, sources):
    return dict(schema_version=1, campaign_id=cid, title=title, sources=sources,
                cases=[], configurations=[], schedule=[], records=[], notes=[])


def register(items, item, key):
    previous = next((x for x in items if x[key] == item[key]), None)
    if previous is not None and previous != item:
        raise ValueError(f'Conflicting {key}: {item[key]}')
    if previous is None:
        items.append(item)
    return item[key]


def config_id(c, settings, label):
    cid = 'cfg-' + digest(canonical(settings).encode())[:16]
    register(c['configurations'], dict(configuration_id=cid, label=label, **settings), 'configuration_id')
    return cid


def record(c, case_id, cfg, raw, provenance, repetition, algorithm, attempt=1):
    run = f'{c["campaign_id"]}/{case_id}/{cfg}/rep-{repetition:03d}'
    return dict(campaign_id=c['campaign_id'], case_id=case_id, configuration_id=cfg,
                run_id=run, attempt_id=f'{run}/attempt-{attempt:03d}', attempt_number=attempt,
                repetition=repetition, warmup=repetition == 0, algorithm=algorithm,
                provenance=provenance, raw=clean(raw))


def configuration_environment(environment):
    """Keep settings in configuration identity; exclude run IDs, ports and measured diagnostics."""
    result = {k:v for k,v in environment.items() if k not in {
        'generation_seconds', 'known_objective', 'min_coefficient', 'max_coefficient',
        'max_row_nnz', 'case', 'hash', 'm', 'n', 'nnz', 'seed', 'family', 'nonzeros_per_row'}}
    if 'spark_conf' in result:
        volatile = {'spark.app.id','spark.app.name','spark.app.startTime','spark.app.submitTime',
                    'spark.driver.port','spark.blockManager.port','spark.eventLog.dir'}
        result['spark_conf'] = {k:v for k,v in result['spark_conf'].items() if k not in volatile}
    return result


def import_jsonl(root, cid, artifact_uri=None):
    """Import all available attempts; never invent results for missing scheduled slots."""
    root = Path(root).resolve()
    paths = sorted(root.glob('*/records.jsonl')) + sorted(root.glob('dsl-*.jsonl'))
    if not paths:
        raise ValueError('No records.jsonl or dsl-*.jsonl found')
    c = campaign(cid, cid, [])
    manifest_path = root / 'manifest.json'
    manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
    c['notes'] = [f'Imported from {artifact_uri or str(root)}; preserve the entire original artifact tree.']
    inventories = list(root.glob('cases.csv'))
    inventory = {r['id']: r for p in inventories for r in csv.DictReader(io.StringIO(p.read_text()))}
    for p in sorted(set(paths + inventories + list(root.rglob('manifest.json')) + list(root.rglob('environment.json')) +
                        list(root.rglob('command.json')) + list(root.rglob('exit.json')) +
                        list(root.glob('dsl-*-command.json')) + list(root.glob('dsl-*-exit.json')) +
                        list(root.glob('SHA256SUMS')))):
        c['sources'].append(dict(source(str(p.relative_to(root)), p.read_bytes()),
                                 uri=(artifact_uri.rstrip('/') + '/' + str(p.relative_to(root))) if artifact_uri else str(p)))
    for path in paths:
        relative = str(path.relative_to(root))
        evidence = next(s for s in c['sources'] if s['path'] == relative)
        env_path = path.parent / 'environment.json'
        environment = configuration_environment(clean(json.loads(env_path.read_text()))) if env_path.exists() else {}
        for line, text in enumerate(evidence['content_utf8'].splitlines(), 1):
            raw = json.loads(text)
            case_id = raw.get('case', 'bounded-dsl-smoke')
            spec = inventory.get(case_id, {})
            m = integer(raw.get('m', spec.get('m')))
            n = integer(raw.get('n', spec.get('n'))) or (m*int(spec['multiplier']) if m and spec.get('multiplier') else None)
            if not m or not n:
                raise ValueError(f'{relative}:{line}: dimensions unavailable; recover cases.csv')
            # Identity of physical fixture includes its recorded hash, independent of backend.
            case = dict(case_id=case_id, m=m, n=n, nnz=integer(raw.get('nnz')),
                        nnz_basis='recorded' if raw.get('nnz') is not None else 'unavailable',
                        fixture_hash=raw.get('hash'), shape=spec)
            old = next((x for x in c['cases'] if x['case_id'] == case_id), None)
            if old:
                for key in ['nnz', 'fixture_hash']:
                    if case[key] is None:
                        case[key] = old[key]
                    elif old[key] is None:
                        old[key] = case[key]
                case['nnz_basis'] = old['nnz_basis'] = 'recorded' if case['nnz'] is not None else 'unavailable'
            register(c['cases'], case, 'case_id')
            backend = raw['backend']
            if backend not in ('cholesky', 'cg', 'cg-pre'):
                raise ValueError(f'Unknown JSONL backend: {backend}')
            algorithm = 'Cholesky' if backend.lower() == 'cholesky' else 'CG'
            settings = {k: clean(raw.get(k, environment.get(k))) for k in [
                'implementation_sha', 'source_hash', 'warmups', 'heap_gib', 'partitions', 'outer_limit', 'cg_tolerance',
                'cg_limit_per_rank', 'primal_regularization', 'dual_regularization', 'preconditioner_memory_bytes']}
            # Keep full environment in identity. A changed host/SparkConf cannot be pooled silently.
            settings.update(algorithm=algorithm, implementation=backend, tolerance=raw.get('tolerance', 1e-8),
                environment=environment, suite=raw.get('suite', ALGORITHM_SUITES[algorithm]), campaign_environment={k: manifest.get(k) for k in ('host', 'java', 'blas_threads')},
                timing_scope='DSL end-to-end' if 'end_to_end_seconds' in raw else 'core solve excluding independent validation')
            # The launcher manifest establishes local mode; an absent manifest does not.
            settings['memory_topology'] = (dict(executors=integer(environment['executors']),
                concurrent_tasks_per_executor=integer(environment['concurrent_tasks_per_executor']))
                if environment.get('executors') and environment.get('concurrent_tasks_per_executor') else
                dict(executors=1, concurrent_tasks_per_executor=4) if manifest.get('mode') in ('smoke', 'full-local') else None)
            cfg = config_id(c, settings, backend)
            row = record(c, case_id, cfg, raw, dict(source_path=relative, source_sha256=evidence['sha256'], line=line),
                         int(raw['repetition']), algorithm, int(raw.get('attempt_number', 1)))
            row.update(status=raw['status'], solve_seconds=number(raw.get('solve_seconds', raw.get('end_to_end_seconds'))),
                accuracy_basis='bounded-dsl-values' if 'end_to_end_seconds' in raw else 'independent-original-lp',
                residuals=clean(raw.get('residuals', {})), outer_iterations=integer(raw.get('outer_iterations')),
                cg_steps=integer(raw.get('cg_steps')), cg_restarts=integer(raw.get('cg_restarts')),
                rank_escalations=integer(raw.get('rank_escalations')), maximum_rank=integer(raw.get('maximum_rank')),
                memory=dict(peak_heap_bytes=integer(raw.get('peak_heap_bytes')), peak_rss_bytes=integer(raw.get('peak_rss_bytes')),
                            scope='driver process; combined with executors in local mode; includes validation'),
                application_id=raw.get('application_id'), spark_jobs=raw.get('spark_jobs'), stop_reason=raw.get('reason'))
            c['records'].append(row)
    if manifest.get('expected_measured') is not None:
        c['notes'].append(f'Manifest expected_measured={manifest["expected_measured"]}; DSL is additional. Missing records remain unknown, not attempted.')
    return c


def is_valid(row, cfg):
    if row['status'] not in SUCCESS:
        return False
    if row['accuracy_basis'] == 'bounded-dsl-values':
        return all(number(row['raw'].get(k)) is not None and abs(row['raw'][k]-target) < 1e-7
                   for k, target in [('x',2), ('y',1), ('objective',4)])
    keys = ['primal', 'dual', 'gap']
    if row['accuracy_basis'] == 'independent-original-lp':
        keys.append('objective_error')
    r, tol = row['residuals'], cfg['tolerance']
    if not all(number(r.get(k)) is not None and 0 <= r[k] < tol for k in keys):
        return False
    return row['accuracy_basis'] != 'independent-original-lp' or all(
        number(r.get(k)) is not None and r[k] >= -tol for k in ['min_x', 'min_s'])


def validate(c):
    """Validate dimensions, identities, configuration separation and accuracy gates."""
    cases = {v['case_id']: v for v in c['cases']}
    configs = {v['configuration_id']: v for v in c['configurations']}
    if len(cases) != len(c['cases']) or len(configs) != len(c['configurations']):
        raise ValueError('Duplicate case or configuration')
    for case in cases.values():
        if case['m'] <= 0 or case['n'] <= 0 or case['nnz'] is not None and not 0 <= case['nnz'] <= case['m']*case['n']:
            raise ValueError('Invalid dimensions or nonzero count')
    attempts = set()
    for row in c['records']:
        if row['attempt_id'] in attempts:
            raise ValueError('Duplicate measured attempt')
        attempts.add(row['attempt_id'])
        if row['case_id'] not in cases or row['campaign_id'] != c['campaign_id']:
            raise ValueError('Broken case/campaign reference')
        cfg = configs[row['configuration_id']]
        if row['algorithm'] not in ('Cholesky', 'CG') or row['algorithm'] != cfg['algorithm']:
            raise ValueError('Invalid algorithm')
        if cfg['tolerance'] <= 0 or row['warmup'] != (row['repetition'] == 0):
            raise ValueError('Invalid tolerance or warmup identity')
        if row['status'] in SUCCESS and (not is_valid(row, cfg) or row['solve_seconds'] is None):
            raise ValueError('Invalid claimed success')
        for key in ('solve_seconds', 'outer_iterations', 'cg_steps', 'cg_restarts', 'rank_escalations', 'maximum_rank'):
            if row.get(key) is not None and row[key] < 0:
                raise ValueError(f'Invalid {key}')
    canonical(c)


def memory_components(case, rank, q=4, executors=1):
    m, n, z = case['m'], case['n'], case['nnz']
    if z is None:
        return None
    # Deliberately explicit payload assumptions; these are not measured process totals.
    # One sparse input (double value/int index + reference per column) and twelve
    # n-double vector equivalents. JVM objects, extra caches and SQL are additional.
    cache = (12*z + 104*n) / executors
    packed = 4*m*(m+1)
    return dict(cholesky_executor=cache + 2*q*packed + 8*q*m,
                cholesky_driver=16*m*m + 64*m,
                cg_executor=cache + 32*q*m,
                cg_driver=None if rank is None else 64*m + 32*m*rank)


def span(values):
    if not values or any(v is None for v in values):
        return '—'
    a, b = min(values), max(values)
    return str(a) if a == b else f'{a}–{b}'


def mib(value):
    if value is None:
        return 'unknown'
    return f'{value / 1024**2:,.3f} MiB' if 0 < value < 0.01*1024**2 else f'{value / 1024**2:,.2f} MiB'


def render_table(columns, rows, extract_shared=True):
    # Keep the ID joining the two tables even for a single configuration.
    shared = [i for i, name in enumerate(columns) if extract_shared and name not in ('ID', 'Env') and rows
              and all(row[i] == rows[0][i] for row in rows)]
    varying = [i for i in range(len(columns)) if i not in shared]

    def table_row(cells):
        return '| ' + ' | '.join(str(v).replace('|', '\\|') for v in cells) + ' |'

    lines = []
    if shared:
        lines.extend(['Shared values: ' + '; '.join(
            f'**{columns[i]}:** {rows[0][i]}' for i in shared) + '.', ''])
    lines.extend([table_row(columns[i] for i in varying),
                  '|' + '|'.join('---' for _ in varying) + '|'])
    lines.extend(table_row(row[i] for i in varying) for row in rows)
    return '\n'.join(lines)


def render_tables(campaigns):
    plan_columns = ['ID', 'Type', 'Rows m', 'Variables n', 'Campaign / case', 'Purpose',
                    'Implementation', 'Control', 'Fixture family', 'Seed', 'Nonzeros', 'Density (%)',
                    'Nonzeros / column', 'Nonzeros / row', 'Row-scale ratio', 'Tolerance', 'Outer limit',
                    'Eta', 'CG tolerance', 'CG step limit per rank', 'Rp', 'Rd',
                    'Preconditioner budget (MiB)', 'Spark master', 'Partitions',
                    'Heap (GiB)', 'BLAS', 'Recorded repetitions', 'Warmups per backend invocation', 'Warmup case',
                    'Timing scope', 'Data file']
    result_columns = ['ID', 'Env', 'Converged / attempted', 'Iterations', 'Restarts/escalations',
                      'Max preconditioner rank', 'Solve seconds, median [min–max]',
                      'Outcome / accuracy', 'Memory per executor (big O + calculated/measured)',
                      'Memory per driver (big O + calculated/measured)']
    plans, results, environments, environment_ids = [], [], [], {}
    order = {'solver-scaling': 0, 'sparsity-and-conditioning': 1, 'emr-scaling': 2}
    names = {'solver-scaling': 'Solver scaling', 'sparsity-and-conditioning': 'Sparsity and conditioning',
             'emr-scaling': 'EMR scaling'}
    prefixes = {'solver-scaling': 'SS', 'sparsity-and-conditioning': 'SC', 'emr-scaling': 'EMR'}
    def parameter(value):
        if value is None:
            return 'unrecorded'
        return f'{value:g}' if isinstance(value, float) else str(value)
    for c in sorted(campaigns, key=lambda c: (order.get(c['campaign_id'], 3), c['campaign_id'])):
        measured = [r for r in c['records'] if not r['warmup']]
        if not measured:
            continue
        title = names.get(c['campaign_id'], re.sub(r'\bIssue\s*#?\d+\s*', '', c['title'], flags=re.IGNORECASE))
        cases = {x['case_id']: x for x in c['cases']}
        configs = {x['configuration_id']: x for x in c['configurations']}
        groups = collections.defaultdict(list)
        for row in measured:
            groups[(row['case_id'], row['configuration_id'])].append(row)
        def group_order(item):
            case_id, cfg_id = item[0]
            case, cfg = cases[case_id], configs[cfg_id]
            return (case['m'], case['n'], case_id, cfg['algorithm'] != 'Cholesky',
                    cfg['label'], cfg_id)
        for index, ((case_id, cfg_id), group) in enumerate(sorted(groups.items(), key=group_order), 1):
            benchmark_id = f'{prefixes.get(c["campaign_id"], c["campaign_id"].upper())}-{index:02d}'
            suite = configs[cfg_id].get('suite', ALGORITHM_SUITES.get(configs[cfg_id]['algorithm']))
            if suite:
                benchmark_id += f' · [{suite.split(".")[-1]}](../main/scala/{suite.replace(".", "/")}.scala)'
            case, cfg = cases[case_id], configs[cfg_id]
            env = environment_values(cfg)
            env_key = canonical(env)
            if env_key not in environment_ids:
                env_id = f'ENV-{len(environment_ids)+1:02d}'
                environment_ids[env_key] = env_id
                environments.append([env_id, env['computer'] or 'unrecorded', env['os'] or 'unrecorded',
                                     env['spark_version'] or 'unrecorded', env['java_version'] or 'unrecorded',
                                     env['emr_name'] or 'n/a'])
            env_id = environment_ids[env_key]
            attempted = [r for r in group if r['status'] not in NOT_ATTEMPTED]
            valid = [r for r in attempted if is_valid(r, cfg)]
            # Homogeneous failures show observed stop/failure duration explicitly, never successful solve timing.
            timed = valid if valid else attempted
            times = [r['solve_seconds'] for r in timed if r['solve_seconds'] is not None]
            timing = '—' if not times else f'{statistics.median(times):.3f} [{min(times):.3f}–{max(times):.3f}]'
            if not valid and times:
                timing += ' (unsuccessful duration)'
            z, m, n = case['nnz'], case['m'], case['n']
            observed = attempted if attempted else group
            ranks = [r['maximum_rank'] for r in observed]
            rank = max(ranks) if all(v is not None for v in ranks) else None
            topology = cfg.get('memory_topology')
            mem = memory_components(case, rank, topology['concurrent_tasks_per_executor'],
                topology['executors']) if topology else None
            direct = cfg['algorithm'] == 'Cholesky'
            local = topology and topology['executors'] == 1
            e = ('O(nnz+n+m²)' if direct else 'O(nnz+n+m)') if local else (
                'O((nnz+n)/executors + tasks·m²)' if direct else 'O((nnz+n)/executors + tasks·m)')
            d = 'O(m²)' if direct else 'O(m+m·rank)'
            if mem:
                e += '; [≈' + mib(mem['cholesky_executor' if direct else 'cg_executor']) + ' payload](../../README.md#memory)'
                e += f'; {topology["executors"]} executor(s), {topology["concurrent_tasks_per_executor"]} tasks/executor'
                d += '; [≈' + mib(mem['cholesky_driver' if direct else 'cg_driver']) + ' workspace](../../README.md#memory)'
            else:
                e += '; topology/nnz unavailable'
                d += '; payload estimate unavailable'
            outcomes = ', '.join(f'{k} ×{v}' for k,v in sorted(collections.Counter(r['status'] for r in group).items()))
            accuracy = [[r['residuals'].get(k) for r in group] for k in ('primal', 'dual', 'gap')]
            if all(v is not None for values in accuracy for v in values):
                outcomes += '; max primal/dual/gap ' + '/'.join(f'{max(values):.3g}' for values in accuracy)
            reason = sorted({r.get('stop_reason') for r in group if r.get('stop_reason') not in (None, 'None')})
            if reason:
                outcomes += '; ' + ', '.join(reason)
            if len(group) == 1 and group[0]['status'] == 'Stopped':
                candidate = group[0]['raw'].get('candidate_available')
                if candidate in (False, 'false'):
                    outcomes += '; no candidate'
                elif candidate in (True, 'true'):
                    outcomes += '; candidate feasible=' + str(group[0]['raw'].get('candidate_feasible')).lower()
            peak = [r['memory']['peak_rss_bytes'] for r in group if r['memory'].get('peak_rss_bytes')]
            if peak:
                d += '; sampled ' + ('combined driver+executor ' if local else 'driver ') + 'RSS max ' + mib(max(peak))
            else:
                d += '; RSS unmeasured'
            name = case_id
            display = re.sub(r'\b[0-9a-f]{7,64}\b', 'Recorded implementation',
                             cfg['implementation'], flags=re.IGNORECASE)
            control = {'none': 'Off', 'report': 'Progress callback',
                       'candidate': 'Stop at first feasible event', 'time': 'Time limit',
                       'stagnation': 'Stagnation stop'}.get(cfg.get('control'), parameter(cfg.get('control')))
            purpose = {
                'solver-scaling': 'measures scaling with rows, variables and sparse support',
                'sparsity-and-conditioning': 'tests density, row scaling and near dependence',
                'emr-scaling': 'measures larger distributed problems on EMR'
            }.get(c['campaign_id'], '')
            environment = cfg.get('environment') or {}
            partitions = cfg.get('partitions', environment.get('input_partitions'))
            heap = cfg.get('heap_gib', environment.get('heap_gib'))
            repetitions = len({r['repetition'] for r in group})
            budget = cfg.get('preconditioner_memory_bytes')
            filename = c['campaign_id'] + '.csv'
            plan = [benchmark_id, cfg['algorithm'], f'{m:,}', f'{n:,}', f'{title} / {name}',
                    purpose or 'unrecorded', display, control, parameter(case['shape'].get('family')), parameter(case['shape'].get('seed')),
                    'unrecorded' if z is None else f'{z:,}',
                    'unrecorded' if z is None else f'{100*z/(m*n):.4g}',
                    parameter(case['shape'].get('width_per_column', 'n/a')),
                    parameter(case['shape'].get('nonzeros_per_row', 'n/a')),
                    parameter(case['shape'].get('row_scale_ratio', 'n/a')),
                    parameter(cfg['tolerance']), parameter(cfg.get('outer_limit')), parameter(cfg.get('eta')),
                    'n/a' if direct else parameter(cfg.get('cg_tolerance')),
                    'n/a' if direct else parameter(cfg.get('cg_limit_per_rank')),
                    parameter(cfg.get('primal_regularization', cfg.get('regularization'))),
                    parameter(cfg.get('dual_regularization', cfg.get('regularization'))),
                    'n/a' if direct else parameter(None if budget is None else budget/1024**2),
                    parameter(environment.get('master')), parameter(partitions), parameter(heap),
                    parameter(environment.get('blas')), repetitions, parameter(cfg.get('warmups')),
                    cfg.get('warmup_case') or ('n/a' if cfg.get('warmups') == 0 else 'unrecorded'),
                    cfg['timing_scope'], f'[{filename}](data/{filename})']
            plans.append(plan)
            cells = [benchmark_id, env_id, f'{len(valid)}/{len(attempted)}',
                f'outer {span([r["outer_iterations"] for r in observed])}' + ('' if direct else f'; CG {span([r["cg_steps"] for r in observed])}'),
                'n/a' if direct else f'{span([r["cg_restarts"] for r in observed])} / {span([r["rank_escalations"] for r in observed])}'.replace('—', 'unrecorded'),
                'n/a' if direct else ('—' if rank is None else str(rank)), timing, outcomes, e, d]
            results.append(cells)
    return ('## Benchmark types\n\n' + render_table(plan_columns, plans)
            + '\n\n## Environment\n\n' + render_table(
                ['ID', 'Computer', 'OS', 'Spark version', 'Java version', 'EMR name'], environments, extract_shared=False)
            + '\n\n## Result\n\n' + render_table(result_columns, results))


def render_report(campaigns):
    return '# Benchmarks\n\n' + render_tables(campaigns) + '\n'


# Each physical CSV record is a measured attempt. Empty numeric cells mean unavailable.
CASE_FIELDS = 'case_id,m,n,nnz,fixture_family,seed,nonzeros_per_column,nonzeros_per_row,row_scale_ratio,blocks,fixture_hash'.split(',')
ENV_FIELDS = 'computer,os,spark_version,java_version,emr_name,blas,spark_master'.split(',')
CONFIG_FIELDS = ('source_configuration_id,suite,algorithm,implementation,scenario,control,tolerance,outer_limit,eta,cg_tolerance,'
                 'cg_limit_per_rank,rp,rd,preconditioner_budget_bytes,partitions,executors,tasks_per_executor,'
                 'heap_gib,timing_scope,warmups,warmup_case,source_hash').split(',') + ENV_FIELDS
RESULT_FIELDS = ('repetition,attempt,warmup,status,solve_seconds,outer_iterations,cg_steps,cg_restarts,'
                 'rank_escalations,max_rank,accuracy_basis,primal,dual,gap,objective,objective_error,min_x,min_s,'
                 'peak_heap_bytes,peak_rss_bytes,memory_scope,spark_jobs,application_id,stop_reason').split(',')
SOURCE_FIELDS = 'source_uri,source_path,source_revision,source_sha256,source_line'.split(',')
CSV_FIELDS = ['campaign_id', 'configuration_id', 'environment_id'] + CASE_FIELDS + CONFIG_FIELDS + RESULT_FIELDS + SOURCE_FIELDS
ALGORITHM_SUITES = {'Cholesky': 'com.github.vbmacher.spark_lp.CholeskyBenchmark',
                    'CG': 'com.github.vbmacher.spark_lp.CGBenchmark'}


def environment_values(cfg):
    env = cfg.get('environment') or {}
    host = env.get('host', '')
    computer, _, os_name = host.partition('; ')
    return dict(computer=env.get('computer', computer), os=env.get('os', os_name),
                spark_version=env.get('spark_version', env.get('spark')),
                java_version=env.get('java_version', env.get('jdk', env.get('java'))),
                emr_name=env.get('emr_name'), blas=env.get('blas'), spark_master=env.get('master'))


def flat_rows(c):
    cases = {v['case_id']: v for v in c['cases']}
    configs = {v['configuration_id']: v for v in c['configurations']}
    sources = {v['path']: v for v in c['sources']}
    for r in c['records']:
        case, cfg = cases[r['case_id']], configs[r['configuration_id']]
        shape, topology = case['shape'], cfg.get('memory_topology') or {}
        env = cfg.get('environment') or {}
        p = r['provenance']
        source_info = sources.get(p['source_path'], {})
        row = dict(campaign_id=c['campaign_id'], case_id=case['case_id'], m=case['m'], n=case['n'], nnz=case['nnz'],
            fixture_family=shape.get('family', 'planted' if 'width_per_column' in shape else 'structured'),
            seed=shape.get('seed'), nonzeros_per_column=shape.get('width_per_column'),
            nonzeros_per_row=shape.get('nonzeros_per_row'), source_configuration_id=cfg.get('source_configuration_id',cfg['configuration_id']),
            row_scale_ratio=shape.get('row_scale_ratio'), blocks=shape.get('days'), fixture_hash=case.get('fixture_hash'),
            suite='com.github.vbmacher.spark_lp.' + ('CholeskyBenchmark' if r['algorithm']=='Cholesky' else 'CGBenchmark'),
            algorithm=cfg['algorithm'], implementation=cfg['implementation'], scenario=cfg.get('scenario'),
            control=cfg.get('control'), tolerance=cfg['tolerance'], outer_limit=cfg.get('outer_limit'), eta=cfg.get('eta'),
            cg_tolerance=cfg.get('cg_tolerance'), cg_limit_per_rank=cfg.get('cg_limit_per_rank'),
            rp=cfg.get('primal_regularization',cfg.get('regularization')),
            rd=cfg.get('dual_regularization',cfg.get('regularization')),
            preconditioner_budget_bytes=cfg.get('preconditioner_memory_bytes'),
            partitions=cfg.get('partitions',env.get('input_partitions')), executors=topology.get('executors'),
            tasks_per_executor=topology.get('concurrent_tasks_per_executor'), heap_gib=cfg.get('heap_gib',env.get('heap_gib')),
            timing_scope=cfg['timing_scope'], warmups=cfg.get('warmups'), warmup_case=cfg.get('warmup_case'),
            source_hash=cfg.get('source_hash'), repetition=r['repetition'], attempt=r['attempt_number'], warmup=r['warmup'],
            status=r['status'], solve_seconds=r['solve_seconds'], outer_iterations=r['outer_iterations'],
            cg_steps=r['cg_steps'], cg_restarts=r['cg_restarts'], rank_escalations=r['rank_escalations'],
            max_rank=r['maximum_rank'], accuracy_basis=r['accuracy_basis'],
            peak_heap_bytes=r['memory'].get('peak_heap_bytes'), peak_rss_bytes=r['memory'].get('peak_rss_bytes'),
            memory_scope=r['memory'].get('scope'), spark_jobs=r.get('spark_jobs'), application_id=r.get('application_id'),
            stop_reason=r.get('stop_reason'), source_uri=source_info.get('uri'), source_path=p['source_path'], source_revision=source_info.get('revision'),
            source_sha256=p['source_sha256'], source_line=p['line'], **environment_values(cfg))
        row.update({key: r['residuals'].get(key) for key in ('primal','dual','gap','objective','objective_error','min_x','min_s')})
        # Stable identities hash all visible configuration/environment columns, including unknowns.
        config_text = {k: '' if row.get(k) is None else str(row[k]) for k in CONFIG_FIELDS}
        row['configuration_id'] = 'cfg-' + digest(canonical(config_text).encode())[:16]
        row['environment_id'] = 'env-' + digest(canonical({k: config_text[k] for k in ENV_FIELDS}).encode())[:12]
        yield row


def write_campaign(c, path):
    validate(c)
    path = Path(path)
    if path.suffix != '.csv':
        raise ValueError('Measured campaign output must have a .csv extension')
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', newline='', encoding='utf-8') as out:
        writer = csv.DictWriter(out, fieldnames=CSV_FIELDS, lineterminator='\n')
        writer.writeheader()
        writer.writerows(sorted(flat_rows(c), key=lambda r: (r['case_id'], r['algorithm'], r['configuration_id'], r['repetition'], r['attempt'])))


def read_campaign(path):
    with Path(path).open(newline='', encoding='utf-8') as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != CSV_FIELDS:
            raise ValueError(f'Unexpected result columns in {path}')
        rows = list(reader)
    c = campaign(Path(path).stem, Path(path).stem.replace('-', ' ').capitalize(), [])
    for v in rows:
        if None in v or any(value is None for value in v.values()):
            raise ValueError('Result CSV row width differs from its header')
        if v['campaign_id'] != c['campaign_id']:
            raise ValueError('Campaign ID differs from filename')
        if v['configuration_id'] != 'cfg-' + digest(canonical({k:v[k] for k in CONFIG_FIELDS}).encode())[:16]:
            raise ValueError('Configuration ID differs from settings')
        if v['environment_id'] != 'env-' + digest(canonical({k:v[k] for k in ENV_FIELDS}).encode())[:12]:
            raise ValueError('Environment ID differs from description')
        shape = dict(family=v['fixture_family'])
        for field, target, convert in [('nonzeros_per_column','width_per_column',integer),
                                      ('nonzeros_per_row','nonzeros_per_row',integer),('row_scale_ratio','row_scale_ratio',number),('blocks','days',integer),('seed','seed',integer)]:
            if v[field]:
                shape[target] = convert(v[field])
        case = dict(case_id=v['case_id'],m=integer(v['m']),n=integer(v['n']),nnz=integer(v['nnz']),shape=shape,
                    fixture_hash=v['fixture_hash'] or None,nnz_basis='see source provenance')
        register(c['cases'],case,'case_id')
        cfg = dict(configuration_id=v['configuration_id'],source_configuration_id=v['source_configuration_id'],label=v['implementation'],algorithm=v['algorithm'],suite=v['suite'],
                   implementation=v['implementation'],scenario=v['scenario'],control=v['control'],tolerance=number(v['tolerance']),
                   timing_scope=v['timing_scope'],warmup_case=v['warmup_case'] or None,environment_id=v['environment_id'],
                   environment=dict(computer=v['computer'],os=v['os'],spark_version=v['spark_version'],
                                    java_version=v['java_version'],emr_name=v['emr_name'],blas=v['blas'],master=v['spark_master']))
        for field in ('outer_limit','cg_limit_per_rank','partitions','heap_gib','warmups'):
            cfg[field] = integer(v[field])
        for field in ('eta','cg_tolerance'):
            cfg[field] = number(v[field])
        cfg.update(primal_regularization=number(v['rp']),dual_regularization=number(v['rd']),
                   preconditioner_memory_bytes=integer(v['preconditioner_budget_bytes']),source_hash=v['source_hash'] or None,
                   memory_topology=dict(executors=integer(v['executors']),concurrent_tasks_per_executor=integer(v['tasks_per_executor']))
                   if v['executors'] and v['tasks_per_executor'] else None)
        register(c['configurations'],cfg,'configuration_id')
        r = record(c,case['case_id'],cfg['configuration_id'],{},dict(source_path=v['source_path'],
                   source_sha256=v['source_sha256'],line=integer(v['source_line'])),integer(v['repetition']),v['algorithm'],integer(v['attempt']))
        if v['warmup'] not in ('True','False') or (v['warmup']=='True') != r['warmup']:
            raise ValueError('Invalid warmup')
        r.update(status=v['status'],solve_seconds=number(v['solve_seconds']),accuracy_basis=v['accuracy_basis'],
                 residuals={key:number(v[key]) for key in ('primal','dual','gap','objective','objective_error','min_x','min_s')},
                 outer_iterations=integer(v['outer_iterations']),cg_steps=integer(v['cg_steps']),cg_restarts=integer(v['cg_restarts']),
                 rank_escalations=integer(v['rank_escalations']),maximum_rank=integer(v['max_rank']),
                 memory=dict(peak_heap_bytes=integer(v['peak_heap_bytes']),peak_rss_bytes=integer(v['peak_rss_bytes']),scope=v['memory_scope']),
                 spark_jobs=integer(v['spark_jobs']),application_id=v['application_id'] or None,stop_reason=v['stop_reason'] or None)
        if r['accuracy_basis']=='bounded-dsl-values':
            raise ValueError('DSL smoke belongs in correctness artifacts, not the performance report')
        c['records'].append(r)
        register(c['sources'],dict(path=v['source_path'],uri=v['source_uri'] or None,revision=v['source_revision'] or None,sha256=v['source_sha256']), 'path')
    validate(c)
    return c


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    ingest = sub.add_parser('import-jsonl', help='Import an available artifact directory; refuses overwrite')
    ingest.add_argument('directory', type=Path)
    ingest.add_argument('--campaign', required=True)
    ingest.add_argument('--artifact-uri')
    ingest.add_argument('--output', type=Path, required=True)
    for cmd in ['render', 'check']:
        p = sub.add_parser(cmd)
        p.add_argument('--data', type=Path, default=DATA)
        p.add_argument('--report', type=Path, default=RESULTS / 'REPORT.md')
    args = parser.parse_args()
    if args.command == 'import-jsonl':
        if not re.fullmatch(r'[a-z0-9][a-z0-9-]*', args.campaign):
            parser.error('campaign must be a stable lowercase slug')
        write_campaign(import_jsonl(args.directory, args.campaign, args.artifact_uri), args.output)
    else:
        campaigns = []
        for path in sorted(args.data.glob('*.csv')):
            c = read_campaign(path)
            if path.stem != c['campaign_id']:
                raise ValueError(f'Campaign file must be named {c["campaign_id"]}.csv: {path}')
            validate(c)
            campaigns.append(c)
        if len({c['campaign_id'] for c in campaigns}) != len(campaigns):
            raise ValueError('Duplicate campaign ID')
        generated = render_report(campaigns)
        if args.command == 'check' and args.report.read_text() != generated:
            raise ValueError('REPORT.md is stale; run report.py render')
        if args.command == 'render':
            args.report.write_text(generated)
        print(f'{len(campaigns)} campaigns; {sum(len(c["records"]) for c in campaigns)} evidence records; report {args.command} OK')


if __name__ == '__main__':
    main()
