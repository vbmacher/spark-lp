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


def omit_storage_locations(value):
    """Keep private object-storage locations out of published result fields."""
    if not isinstance(value, str):
        return value
    value = re.sub(
        r"\b(?:s3[an]?://|https?://[^/\s]*s3[.-][^/\s]*amazonaws\.com(?:\.cn)?/)[^\s<>\"'`]+",
        '[artifact location omitted]', value, flags=re.IGNORECASE)
    return re.sub(
        r'\barn:aws[^\s]+|\b(?:j|s)-[0-9][A-Z0-9]+\b|\b(?:subnet|sg|vpc|i)-[0-9a-f]+\b|'
        r'\bip-(?:\d+-){3}\d+(?:\.[a-z0-9.-]+)?|\bBME-\d+\b|'
        r'\b(?:us|eu|ap|ca|sa|af|me|il|mx)-(?:gov-)?[a-z]+-\d\b|'
        r'\b(?:aws|amazon|amzn|emr)\b|\b[cmrtipgdhxz]\d[a-z0-9]*\.(?:metal|\d*xlarge)\b',
        '[infrastructure omitted]', value, flags=re.IGNORECASE)


def public_identifier(value):
    return re.sub(r'(?i)\bemr(?=-|$)', 'distributed', value)


def public_result_row(row):
    """Export measurements through an allowlist; infrastructure stays in raw artifacts."""
    result = {key: row.get(key) for key in PUBLIC_FIELDS}
    for key in ('campaign_id', 'case_id'):
        result[key] = public_identifier(result[key])
    if result.get('spark_master') == 'yarn':
        result['computer'] = 'distributed cluster'
    version = result.get('spark_version')
    if version:
        result['spark_version'] = re.sub(r'-amzn.*$', '', version)
    result = {key: omit_storage_locations(value) for key, value in result.items()}
    config = {k: '' if result.get(k) is None else str(result[k]) for k in CONFIG_FIELDS}
    result['configuration_id'] = 'cfg-' + digest(canonical(config).encode())[:16]
    result['environment_id'] = 'env-' + digest(canonical({k: config[k] for k in ENV_FIELDS}).encode())[:12]
    return result


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
    if not manifest_path.exists():
        manifest_path = root / 'input' / 'manifest.json'
    manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
    c['notes'] = [f'Imported from {artifact_uri or str(root)}; preserve the entire original artifact tree.']
    inventory_root = manifest_path.parent if manifest_path.exists() else root
    inventory_name = manifest.get('inventory', 'cases.csv')
    if Path(inventory_name).name != inventory_name:
        raise ValueError('Manifest inventory must be a filename')
    inventories = list(inventory_root.glob(inventory_name))
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
                raise ValueError(f'{relative}:{line}: dimensions unavailable; recover {inventory_name}')
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
    expected = manifest.get('expected_measured')
    if expected is None and manifest.get('case') and manifest.get('benchmark'):
        expected = manifest.get('repetitions')
    if expected is not None:
        observed = len({(r['case_id'], r['raw']['backend'], r['repetition']) for r in c['records']
                        if not r['warmup'] and r['accuracy_basis'] != 'bounded-dsl-values'})
        expected = int(expected)
        c['notes'].append(f'Manifest expected_measured={expected}; retained_measured_slots={observed}; '
                          f'missing_measured_slots={max(0, expected-observed)}. '
                          'DSL is additional. Missing records remain unknown, not attempted.')
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
    # Columns that are the placeholder for every row carry no information; drop them entirely.
    placeholder = {i for i, name in enumerate(columns) if name != 'ID' and rows and all(row[i] == '—' for row in rows)}
    # Keep the ID joining the two tables even for a single configuration.
    shared = [i for i, name in enumerate(columns) if extract_shared and name not in ('ID', 'Env')
              and i not in placeholder and rows and all(row[i] == rows[0][i] for row in rows)]
    varying = [i for i in range(len(columns)) if i not in shared and i not in placeholder]

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


def section_for(campaign_id):
    """Map a campaign to a report section (order, title, id-prefix, purpose)."""
    if campaign_id == 'solver-scaling':
        return (0, 'Solver scaling', 'SS', 'measures scaling with rows, variables and sparse support')
    if campaign_id == 'sparsity-and-conditioning':
        return (1, 'Sparsity and conditioning', 'SC', 'tests density, row scaling and near dependence')
    if campaign_id.startswith('dense-small'):
        return (2, 'Dense small', 'DS', 'small dense fixtures for backend comparison on constrained hosts')
    if campaign_id.startswith('distributed') or campaign_id.startswith('result-limit'):
        return (3, 'Distributed scaling', 'DIST', 'measures larger problems on the distributed runtime')
    if campaign_id.startswith('cg-partitions'):
        return (4, 'CG partition tuning', 'PART',
                'CG input-partition tuning for the seed-11, width-32 fixtures; '
                'the recommendation, method and paired speedups follow the table')
    return (5, re.sub(r'\bIssue\s*#?\d+\s*', '', campaign_id.replace('-', ' ').capitalize(),
                      flags=re.IGNORECASE), campaign_id.upper(), '')


def experiment_key(campaign_id):
    """Strip the algorithm suffix so split cg/cholesky campaigns of one problem pair up."""
    return re.sub(r'-(cg|cholesky)$', '', campaign_id)


def comparison_key(cfg):
    """Keep runs separate when a backend-independent execution setting changes."""
    return canonical({key: cfg.get(key) for key in (
        'source_hash', 'environment', 'partitions', 'heap_gib', 'memory_topology',
        'tolerance', 'outer_limit', 'eta', 'control', 'scenario', 'timing_scope',
        'warmups', 'warmup_case')})


def parameter(value):
    if value is None:
        return 'unrecorded'
    return f'{value:g}' if isinstance(value, float) else str(value)


def algorithm_summary(records, cfg, case):
    """Fold solve timing, iterations, accuracy and memory for one algorithm of a case."""
    if cfg is None or not records:
        return None
    direct = cfg['algorithm'] == 'Cholesky'
    body = [r for r in records if not r['warmup']]
    if not body:
        return None
    attempted = [r for r in body if r['status'] not in NOT_ATTEMPTED]
    valid = [r for r in attempted if is_valid(r, cfg)]
    timed = valid if valid else attempted
    times = [r['solve_seconds'] for r in timed if r['solve_seconds'] is not None]
    timing = '—' if not times else f'{statistics.median(times):.3f} [{min(times):.3f}–{max(times):.3f}]'
    observed = attempted if attempted else body
    iters = f'outer {span([r["outer_iterations"] for r in observed])}'
    if not direct:
        iters += f', CG {span([r["cg_steps"] for r in observed])}'
    counts = collections.Counter(r['status'] for r in body)
    if attempted:
        result = f'{timing}; {len(valid)}/{len(attempted)} valid; {iters}'
        extra = ', '.join(f'{k} ×{v}' for k, v in sorted(counts.items()) if k not in SUCCESS)
        if extra:
            result += f'; {extra}'
        if not valid and times:
            result += '; unsuccessful duration'
    else:
        result = ', '.join(f'{k} ×{v}' for k, v in sorted(counts.items())) or 'No records'
    reason = sorted({r.get('stop_reason') for r in body if r.get('stop_reason') not in (None, 'None')})
    if reason:
        result += '; ' + ', '.join(reason)
    accuracy = [[r['residuals'].get(k) for r in body] for k in ('primal', 'dual', 'gap')]
    accuracy = ('/'.join(f'{max(values):.3g}' for values in accuracy)
                if all(v is not None for values in accuracy for v in values) else '—')
    ranks = [r['maximum_rank'] for r in observed]
    rank = max(ranks) if ranks and all(v is not None for v in ranks) else None
    topology = cfg.get('memory_topology')
    mem = memory_components(case, rank, topology['concurrent_tasks_per_executor'],
                            topology['executors']) if topology else None
    local = bool(topology) and topology['executors'] == 1
    e = ('O(nnz+n+m²)' if direct else 'O(nnz+n+m)') if local else (
        'O((nnz+n)/executors + tasks·m²)' if direct else 'O((nnz+n)/executors + tasks·m)')
    d = 'O(m²)' if direct else 'O(m+m·rank)'
    if mem:
        payload = mib(mem['cholesky_executor' if direct else 'cg_executor'])
        workspace = mib(mem['cholesky_driver' if direct else 'cg_driver'])
        memory = f'exec {e} [≈{payload}](../../README.md#memory); driver {d} ≈{workspace}'
    else:
        memory = f'exec {e}; driver {d}; estimate unavailable'
    peak = [r['memory']['peak_rss_bytes'] for r in body if r['memory'].get('peak_rss_bytes')]
    memory += ('; RSS ' + ('combined ' if local else 'driver ') + mib(max(peak))) if peak else '; RSS unmeasured'
    return dict(result=result, accuracy=accuracy, memory=memory, attempted=bool(attempted),
                topology=topology, warmups=[r for r in records if r['warmup']])


PARTITION_NOTES = (
    '**Recommendation:** For these seed-11, width-32 CG fixtures on the tested four-executor '
    'cluster (4×4), use 16 input partitions. Median paired speedups versus a fresh 64-partition '
    'reference were 1.44–2.16×, and every measured solve and warmup passed independent `1e-8` '
    'accuracy checks. This is workload-specific: no solver default, numerical setting or algorithm '
    'changed.\n\n'
    '**Method:** CG only; seed 11; width 32; one warmup and five measured attempts per fresh JVM. '
    'Round 1 sweeps 64/32/16 partitions and round 2 sweeps 16/32/64 on the same cluster; the rounds '
    'stay separate because timing variability is visible. Each comparison uses a fresh 64-partition '
    'reference (the larger historical baseline used 128). Paired speedup divides the matching '
    '64-partition repetition by the candidate within the same fixture and round.\n\n'
    'Paired speedup, 16 vs 64 partitions (median [min, max]):\n\n'
    '| Fixture | Round 1 | Round 2 |\n'
    '|---|---|---|\n'
    '| Well-conditioned 1,000 × 100,000 | 1.589 [1.566, 1.702] | 2.164 [1.972, 2.196] |\n'
    '| Well-conditioned 5,000 × 1,000,000 | 1.440 [1.383, 1.476] | 1.522 [1.479, 1.566] |\n'
    '| Near-dependent 1,000 × 100,000 | 1.534 [1.485, 1.625] | 1.648 [1.626, 1.666] |\n\n'
    'Fewer partitions also lowered executor CPU, shuffle read and per-success step cost; the '
    '32-partition setting falls between 16 and 64. The near-dependent fixture\'s CG step count varies '
    'with floating-point reduction order, so its gain is not attributed only to scheduling.\n\n'
    '**Limits:** Seeds 29 and 47, other executor counts, other input shapes, native BLAS/LAPACK and '
    'backend selection are untested here; these gains do not establish a universal partition count. '
    'Per-attempt CPU, GC, phase, task and cost metrics remain in each `cg-partitions-*.bmf.json` under '
    '`_evidence`.')


SCALING_NOTES = (
    '**Fixed-wide executor sweep:** The identical 5,000 × 50,000 seed-11 fixture used '
    '4×4/16, 8×4/32 and 16×4/64 executor/partition configurations, each with one warmup and '
    'five measured attempts per backend. Cholesky medians were 202.785, 208.440 and 224.481 '
    'seconds respectively; all attempts passed independent `1e-8` validation. CG reached its '
    'iteration limit in every measured attempt, so unsuccessful durations are not speedups. '
    'This fixed-input sweep does not show a benefit from adding executors.\n\n'
    '**Proportional scaling:** The 5,000/4-executor, 10,000/8-executor and '
    '20,000/16-executor cases remain separate. Outcomes change with problem size: Cholesky alone '
    'succeeded at 5,000 rows, both backends succeeded at 10,000 rows, and at 20,000 rows Cholesky '
    'was resource-excluded while CG reached its iteration limit. These results do not support a '
    'monotonic scaling claim.')


ACCURACY_NOTES = (
    '**Accuracy protocols:** `primary-*`, `difficulty-*`, `shape-*`, `memory-*` and `scale-*` '
    'cases use the primary `1e-8` protocol. The `accuracy-*` cases are the separate near-crossover '
    '`1e-6` sweep; timings and validation counts are never pooled across tolerances.')


def render_tables(campaigns):
    from bencher_export import result_filename
    columns = ['ID', 'Case', 'Rows m', 'Variables n', 'Nonzeros', 'Density (%)', 'Nonzeros / row',
               'Fixture family', 'Seed', 'Partitions', 'Heap (GiB)', 'Executor topology', 'Environment',
               'Warmup outcomes',
               'Cholesky solve s; valid; iterations', 'Cholesky max primal/dual/gap', 'Cholesky memory',
               'CG solve s; valid; iterations', 'CG max primal/dual/gap', 'CG memory']
    # Group records into one row per physical case, pairing the Cholesky and CG runs of that case.
    groups = {}
    for c in campaigns:
        if not c['records']:
            continue
        section = section_for(c['campaign_id'])
        ekey = experiment_key(c['campaign_id'])
        cases = {x['case_id']: x for x in c['cases']}
        configs = {x['configuration_id']: x for x in c['configurations']}
        for row in c['records']:
            case, cfg = cases[row['case_id']], configs[row['configuration_id']]
            gkey = (section[0], section[2], ekey, row['case_id'], comparison_key(cfg))
            group = groups.setdefault(gkey, dict(section=section, ekey=ekey, case=case,
                                                 records=[], cfgs={}, files=set()))
            group['records'].append(row)
            group['cfgs'][cfg['algorithm']] = cfg
            group['files'].add(result_filename(c['campaign_id'], cfg))
    def group_order(item):
        gkey, group = item
        cfg = next(iter(group['cfgs'].values()))
        topology = cfg.get('memory_topology') or {}
        return (group['section'][0], group['case']['m'], group['case']['n'], gkey[3], gkey[2],
                topology.get('executors', 0), cfg.get('partitions') or 0)

    ordered = sorted(groups.items(), key=group_order)
    environments, environment_ids, failures = [], {}, []
    sections = collections.OrderedDict()
    counters = collections.Counter()
    for gkey, group in ordered:
        section, case = group['section'], group['case']
        chol = algorithm_summary([r for r in group['records'] if r['algorithm'] == 'Cholesky'],
                                 group['cfgs'].get('Cholesky'), case)
        cg = algorithm_summary([r for r in group['records'] if r['algorithm'] == 'CG'],
                               group['cfgs'].get('CG'), case)
        cfg = group['cfgs'].get('CG') or group['cfgs'].get('Cholesky')
        env = environment_values(cfg)
        env_key = canonical(env)
        if env_key not in environment_ids:
            env_id = f'ENV-{len(environment_ids)+1:02d}'
            environment_ids[env_key] = env_id
            environments.append([env_id, env['computer'] or 'unrecorded', env['os'] or 'unrecorded',
                                 env['spark_version'] or 'unrecorded', env['java_version'] or 'unrecorded',
                                 env['spark_master'] or 'unrecorded'])
        env_id = environment_ids[env_key]
        case_label = case['case_id']
        # Cases where nothing was attempted for any backend move to the failures footnote.
        if not (chol and chol['attempted']) and not (cg and cg['attempted']):
            statuses = ', '.join(f'{k} ×{v}' for k, v in sorted(
                collections.Counter(r['status'] for r in group['records'] if not r['warmup']).items()))
            failures.append(f'- **{case_label} ({group["ekey"]})** ({env_id}): '
                            f'{"/".join(sorted(group["cfgs"]))} {statuses or "no records"}')
            continue
        counters[section[2]] += 1
        benchmark_id = f'{section[2]}-{counters[section[2]]:02d}'
        topology = (chol or cg or {}).get('topology') or (cg or {}).get('topology')
        executor_topology = (f'{topology["executors"]}×{topology["concurrent_tasks_per_executor"]}'
                             if topology else 'unrecorded')
        warmups = (chol or {}).get('warmups', []) + (cg or {}).get('warmups', [])
        warmup_outcomes = ', '.join(f'{k} ×{v}' for k, v in sorted(
            collections.Counter(r['status'] for r in warmups).items())) or 'unrecorded'
        z, m, n = case['nnz'], case['m'], case['n']
        shape = case['shape']
        environment = cfg.get('environment') or {}
        row = [benchmark_id, case_label, f'{m:,}', f'{n:,}',
               'unrecorded' if z is None else f'{z:,}',
               'unrecorded' if z is None else f'{100*z/(m*n):.4g}',
               parameter(shape.get('nonzeros_per_row', shape.get('width_per_column', 'n/a'))),
               parameter(shape.get('family')), parameter(shape.get('seed')),
               parameter(cfg.get('partitions', environment.get('input_partitions'))),
               parameter(cfg.get('heap_gib', environment.get('heap_gib'))),
               executor_topology, env_id, warmup_outcomes,
               chol['result'] if chol else '—', chol['accuracy'] if chol else '—', chol['memory'] if chol else '—',
               cg['result'] if cg else '—', cg['accuracy'] if cg else '—', cg['memory'] if cg else '—']
        entry = sections.setdefault(section[:2], dict(purpose=section[3], files=set(), rows=[]))
        entry['files'].update(group['files'])
        entry['rows'].append((row, case['case_id'], group['ekey']))
    out = ['## Environment\n\n' + render_table(
        ['ID', 'Computer', 'OS', 'Spark version', 'Java version', 'Spark master'], environments, extract_shared=False),
        '## Results\n\nAll runs use the `CholeskyBenchmark` / `CGBenchmark` instances in '
        '[Benchmark.scala](../main/scala/com/github/vbmacher/spark_lp/Benchmark.scala); '
        'each row pairs the two backends on one case. Memory payloads are computed estimates; '
        'RSS is sampled. Timing scope is core solve excluding independent validation; '
        'the tolerance is recorded per case; no CG restarts or rank escalations were recorded.']
    for (_, title), entry in sorted(sections.items()):
        # Add the campaign qualifier only when a case id appears more than once in the section.
        repeated = {cid for cid, count in collections.Counter(cid for _, cid, _ in entry['rows']).items() if count > 1}
        rows = []
        for cells, case_id, ekey in entry['rows']:
            cells[1] = f'{case_id} ({ekey})' if case_id in repeated else case_id
            rows.append(cells)
        files = ', '.join(f'[{name}](data/{name})' for name in sorted(entry['files']))
        block = f'### {title}\n\n{entry["purpose"]}. Data: {files}.\n\n' + render_table(columns, rows)
        if title == 'CG partition tuning':
            block += '\n\n' + PARTITION_NOTES
        elif title.startswith('Benchmark scaling distributed'):
            block += '\n\n' + SCALING_NOTES
        elif title.startswith('Sparsity and conditioning distributed'):
            block += '\n\n' + ACCURACY_NOTES
        out.append(block)
    if failures:
        out.append('### Failures and resource exclusions\n\n'
                   'Cases with no attempted solve on any backend:\n\n' + '\n'.join(failures))
    return '\n\n'.join(out)


PHASE_TIMING_SUMMARY = '''## Phase timing summary

Medians cover 1,065 measured records from the newly collected 1,278-record campaign set; warmups are excluded. Successful, unsuccessful and not-attempted outcomes remain separate, and missing phase values are not inferred. Release timing is representative matched distributed batch only. Source-manifest SHA-256: `099bd7c1b92cc6b336ea95550c58bdb8af1fe7dedecc86af144e61fd09f47605`.

| Campaign | Backend | Outcome | Records | Generation median s | Initialization median s | Preparation median s | Core solve median s | Validation median s | Release median s |
|---|---|---|---|---|---|---|---|---|---|
| completion | cg | not_attempted | 4 | — | — | — | — | — | — |
| completion | cg | success | 451 | 9.573 (n=451) | 3.422 (n=451) | 0.278 (n=451) | 36.746 (n=451) | 0.775 (n=451) | — |
| completion | cg | unsuccessful | 45 | 9.869 (n=45) | 3.046 (n=25) | 0.331 (n=25) | 323.223 (n=45) | 0.000 (n=25) | — |
| completion | cholesky | not_attempted | 90 | — | — | — | — | — | — |
| completion | cholesky | success | 410 | 9.527 (n=410) | 17.847 (n=410) | 0.283 (n=410) | 140.391 (n=410) | 0.769 (n=410) | — |
| release-timing | cg | success | 5 | 9.426 (n=5) | 1.975 (n=5) | 0.238 (n=5) | 25.515 (n=5) | 0.661 (n=5) | 0.003 (n=5) |
| release-timing | cholesky | success | 5 | 9.573 (n=5) | 0.596 (n=5) | 0.219 (n=5) | 22.374 (n=5) | 0.657 (n=5) | 0.003 (n=5) |
| scaling | cg | success | 5 | 10.391 (n=5) | 3.515 (n=5) | 0.574 (n=5) | 175.863 (n=5) | 1.000 (n=5) | — |
| scaling | cg | unsuccessful | 20 | 10.225 (n=20) | 3.576 (n=20) | 0.599 (n=20) | 441.366 (n=20) | 0.000 (n=20) | — |
| scaling | cholesky | not_attempted | 5 | — | — | — | — | — | — |
| scaling | cholesky | success | 20 | 9.969 (n=20) | 18.651 (n=20) | 0.468 (n=20) | 216.943 (n=20) | 1.118 (n=20) | — |
| widest | cg | success | 5 | 25.330 (n=5) | 24.511 (n=5) | 4.390 (n=5) | 482.619 (n=5) | 7.627 (n=5) | — |'''


WIDEST_RUN_TELEMETRY = '''## Widest-run telemetry

Case `distributed-rows-100000-vars-100000000-width-128` used CG with 16 executors × 4 cores, 16 GiB executor heaps and 128 partitions. The private event log is identified by SHA-256 `b7068c261c145f98b0686c223d08470a8a55b64d8783b4b7c3882fb5f241d496`; no cloud location or identifier is published. Phase medians below exclude the warmup. Generation is shared fixture construction; release was not instrumented.

| Phase | Median seconds |
|---|---|
| Generation | 25.330 |
| Initialization | 24.511 |
| Preparation | 4.390 |
| Core Solve | 482.619 |
| Validation | 7.627 |
| Release | not instrumented |

| Stages | Tasks | Failed tasks | Input | Shuffle read | Shuffle write | Memory spill | Disk spill | Max task execution memory |
|---|---|---|---|---|---|---|---|---|
| 8,137 | 592,912 | 0 | 25.81 TiB | 267.48 GiB | 267.48 GiB | 0.00 B | 0.00 B | 1.04 GiB |

| Process | Count | Peak heap | Peak RSS | Peak JVM non-heap |
|---|---|---|---|---|
| Executors | 16 | 9,561.89 MiB–12,481.21 MiB | 12,213.93 MiB–14,736.72 MiB | 232.22 MiB–235.02 MiB |
| Driver | 1 | 5,698.12 MiB | 7,365.13 MiB | 351.98 MiB |

For the 337 stages whose median task duration was at least one second, the largest task-duration max/median ratio was 2.89×; shuffle-read and shuffle-write ratios were at most 1.007× and 1.005×. With no failed tasks or spill, this run does not show a material skew or spill bottleneck.'''


BACKEND_RECOMMENDATION = (
    '## Backend selection\n\n'
    'Apply the documented driver and per-executor memory gate first. Among configurations that fit, '
    'prefer Cholesky for the tested dense, small (up to 1,000 rows), dependent and degenerate fixtures: '
    'it was generally faster and more reliable there. Prefer CG for the tested well-conditioned sparse '
    'fixtures from 2,500 rows upward, and whenever Cholesky is resource-excluded or exhausts its allowed '
    'heap; the matched 10,000-row, 16 GiB runs also show a large CG advantage. Do not silently fall back '
    'to CG for difficult large dependent or wide fixtures: numerical failures, timeouts and iteration '
    'limits occurred, so require the independent residual/objective validation and surface failure. '
    'Treat this as workload- and configuration-specific: all three fixed-wide 5,000-row executor '
    'configurations reached the CG iteration limit while Cholesky succeeded. Row count alone does not '
    'establish a universal crossover, so `Auto` keeps the documented 10,000-row conservative heuristic '
    'and memory gate; callers can lower the gate or explicitly select either backend.'
)


def render_report(campaigns, executor_memory=()):
    measured = [r for c in campaigns for r in c['records'] if not r['warmup']]
    warmups = sum(r['warmup'] for c in campaigns for r in c['records'])
    report = (f'# Benchmarks\n\nCaptured evidence: {len(campaigns)} campaigns, '
              f'{len(measured)} measured slots and {warmups} warmup records. '
              'Tables include partial batches, failures and resource exclusions. '
              'Warmups are shown separately and excluded from solve statistics; '
              'missing records are not inferred.\n\n' + render_tables(campaigns) + '\n\n' +
              PHASE_TIMING_SUMMARY + '\n\n' + WIDEST_RUN_TELEMETRY + '\n\n' +
              BACKEND_RECOMMENDATION + '\n')
    if executor_memory:
        recovered = [row for row in executor_memory if row['variant'].endswith('event-449cb674ed47')]
        groups = collections.defaultdict(list)
        for row in executor_memory:
            groups[(row['backend'], row['variant'], row['scope'])].append(row)
        rows = []
        for (backend, variant, scope), observations in sorted(groups.items()):
            def memory_range(field):
                values = [integer(row[field]) for row in observations if row[field]]
                return f'{mib(min(values))}–{mib(max(values))}'
            rows.append([len({row['case'] for row in observations}), backend, variant,
                         len(observations), memory_range('peak_heap_bytes'),
                         memory_range('peak_rss_bytes'), memory_range('peak_jvm_nonheap_bytes'), scope])
        report += ('\n## Executor memory observations\n\n'
                   'Whole-application peaks include generation, warmup, preparation, solve and validation, '
                   'with 1,000 ms polling and per-stage peak logging. Rows aggregate exact per-executor '
                   'observations by backend, variant and scope; JVM non-heap does not cover all native memory. '
                   f'The retained-log recovery contributed {len(recovered)} executor observations across '
                   f'{len({(row["case"], row["backend"], row["variant"]) for row in recovered})} runs. '
                   'Fifteen failed submitted jobs (12 early OOMs and three capped difficult-CG failures) '
                   'retained no application event log, so no executor peak is inferred for them. '
                   'Source: [executor-memory.bmf.json](data/executor-memory.bmf.json).\n\n'
                   + render_table(['Cases', 'Backend', 'Variant', 'Executor observations', 'Peak heap', 'Peak RSS',
                                   'Peak JVM non-heap', 'Scope'],
                       rows) + '\n')
    return report


# Flattened public evidence fields used to sanitize imported attempts. Empty values mean unavailable.
CASE_FIELDS = 'case_id,m,n,nnz,fixture_family,seed,nonzeros_per_column,nonzeros_per_row,row_scale_ratio,blocks,fixture_hash'.split(',')
ENV_FIELDS = 'computer,os,spark_version,java_version,blas,spark_master'.split(',')
CONFIG_FIELDS = ('source_configuration_id,suite,algorithm,implementation,scenario,control,tolerance,outer_limit,eta,cg_tolerance,'
                 'cg_limit_per_rank,rp,rd,preconditioner_budget_bytes,partitions,executors,tasks_per_executor,'
                 'heap_gib,timing_scope,warmups,warmup_case,source_hash').split(',') + ENV_FIELDS
RESULT_FIELDS = ('repetition,attempt,warmup,status,solve_seconds,outer_iterations,cg_steps,cg_restarts,'
                 'rank_escalations,max_rank,accuracy_basis,primal,dual,gap,objective,objective_error,min_x,min_s,'
                 'peak_heap_bytes,peak_rss_bytes,memory_scope,spark_jobs').split(',')
SOURCE_FIELDS = 'source_path,source_revision,source_sha256,source_line'.split(',')
PUBLIC_FIELDS = ['campaign_id', 'configuration_id', 'environment_id'] + CASE_FIELDS + CONFIG_FIELDS + RESULT_FIELDS + SOURCE_FIELDS
ALGORITHM_SUITES = {'Cholesky': 'com.github.vbmacher.spark_lp.CholeskyBenchmark',
                    'CG': 'com.github.vbmacher.spark_lp.CGBenchmark'}


def environment_values(cfg):
    env = cfg.get('environment') or {}
    host = env.get('host', '')
    computer, _, os_name = host.partition('; ')
    return dict(computer=env.get('computer', computer), os=env.get('os', os_name),
                spark_version=env.get('spark_version', env.get('spark')),
                java_version=env.get('java_version', env.get('jdk', env.get('java'))),
                blas=env.get('blas'), spark_master=env.get('master'))


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
            stop_reason=r.get('stop_reason'), source_uri=None, source_path=p['source_path'], source_revision=source_info.get('revision'),
            source_sha256=p['source_sha256'], source_line=p['line'], **environment_values(cfg))
        row.update({key: r['residuals'].get(key) for key in ('primal','dual','gap','objective','objective_error','min_x','min_s')})
        yield public_result_row(row)


def public_campaign(c):
    """Normalize and sanitize captured evidence before embedding it in the Bencher files."""
    validate(c)
    rows = [{key: '' if value is None else str(value) for key, value in row.items()}
            for row in flat_rows(c)]
    return campaign_from_rows(public_identifier(c['campaign_id']), rows)


def write_campaign(c, path):
    from bencher_export import read_bundle, write_bundle
    path = Path(path)
    campaigns, memory = read_bundle(path / 'data') if (path / 'data').exists() else ([], [])
    c = public_campaign(c)
    if any(existing['campaign_id'] == c['campaign_id'] for existing in campaigns):
        raise ValueError('Campaign already exists; use a new campaign ID')
    write_bundle(campaigns + [c], memory, path)


def campaign_from_rows(cid, rows):
    c = campaign(cid, cid.replace('-', ' ').capitalize(), [])
    for v in rows:
        if None in v or any(value is None for value in v.values()):
            raise ValueError('Incomplete evidence record')
        if any(omit_storage_locations(value) != value for value in v.values()):
            raise ValueError('Evidence contains private infrastructure information')
        if v['campaign_id'] != c['campaign_id']:
            raise ValueError('Campaign ID differs from evidence')
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
                                    java_version=v['java_version'],blas=v['blas'],master=v['spark_master']))
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
                 spark_jobs=integer(v['spark_jobs']),application_id=None,stop_reason=None)
        if r['accuracy_basis']=='bounded-dsl-values':
            raise ValueError('DSL smoke belongs in correctness artifacts, not the performance report')
        c['records'].append(r)
        register(c['sources'],dict(path=v['source_path'],uri=None,revision=v['source_revision'] or None,sha256=v['source_sha256']), 'path')
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
        from bencher_export import read_bundle
        campaigns, executor_memory = read_bundle(args.data)
        generated = render_report(campaigns, executor_memory)
        if args.command == 'check' and args.report.read_text() != generated:
            raise ValueError('REPORT.md is stale; run report.py render')
        if args.command == 'render':
            args.report.write_text(generated)
        print(f'{len(campaigns)} campaigns; {sum(len(c["records"]) for c in campaigns)} evidence records; report {args.command} OK')


if __name__ == '__main__':
    main()
