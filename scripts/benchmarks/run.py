#!/usr/bin/env python3
"""Sequential fresh-JVM campaign launcher. Standard library only; never overwrites a run."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import tarfile
import time

ROOT = Path(__file__).resolve().parents[2]
MAIN = 'com.github.vbmacher.spark_lp.benchmarks.MatrixFreeBenchmark'
AXIS = 'spark-lpSpark_3_52_12'
RESOURCES = ROOT / 'spark-lp/src/test/resources/benchmarks/matrix-free-lp'


def source_manifest():
    paths = [ROOT / 'build.sbt']
    for folder in ['spark-lp/src', 'project', 'scripts/benchmarks']:
        paths += [p for p in (ROOT / folder).rglob('*') if p.is_file() and
                  'target' not in p.parts and '__pycache__' not in p.parts]
    entries = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths))}
    digest = hashlib.sha256(json.dumps(entries, sort_keys=True).encode()).hexdigest()
    return entries, digest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--smoke', action='store_true')
    parser.add_argument('--classpath', type=Path, help='Previously exported candidate Test/fullClasspath')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    cases_path = RESOURCES / ('smoke.csv' if args.smoke else 'cases.csv')
    with cases_path.open() as source:
        cases = list(csv.DictReader(source))
    (output / 'cases.csv').write_bytes(cases_path.read_bytes())
    if args.classpath:
        classpath = args.classpath.read_text().strip()
    else:
        command = ['sbt', f'{AXIS}/Test/compile', f'export {AXIS}/Test/fullClasspath']
        build = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        (output / 'build.log').write_text(build.stdout)
        if build.returncode:
            raise SystemExit(f'Build failed: {output}/build.log')
        candidates = [line.strip() for line in build.stdout.splitlines() if line.startswith('/') and '.jar' in line]
        if not candidates:
            raise SystemExit('No exported classpath found in build.log')
        classpath = candidates[-1]
    (output / 'classpath.txt').write_text(classpath + '\n')
    historical = None
    if not args.smoke:
        from prepare_baseline import prepare
        historical = prepare(output / 'historical-build')
    entries, source_hash = source_manifest()
    with tarfile.open(output / 'candidate-sources.tar.gz', 'w:gz') as archive:
        for path in entries:
            archive.add(ROOT / path, arcname=path)
    sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    env = os.environ.copy()
    env.update(OPENBLAS_NUM_THREADS='1', OMP_NUM_THREADS='1', MKL_NUM_THREADS='1', SPARK_LOCAL_IP='127.0.0.1')
    manifest = dict(schema=1, started_utc=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                    implementation_sha=sha, source_hash=source_hash, sources=entries,
                    inventory_sha256=hashlib.sha256(cases_path.read_bytes()).hexdigest(),
                    host=platform.uname()._asdict(), cpu=Path('/proc/cpuinfo').read_text(),
                    memory=Path('/proc/meminfo').read_text(),
                    load_average=os.getloadavg(),
                    java=subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT, text=True),
                    blas_threads={key: env[key] for key in ['OPENBLAS_NUM_THREADS', 'OMP_NUM_THREADS', 'MKL_NUM_THREADS']},
                    mode='smoke' if args.smoke else 'full-local', warmups=1, repetitions=5,
                    distributed='Pending: cluster access unavailable',
                    historical_backend='Primary and numerical difficulty cases; diagnostic-only adapter at d0e4939' if historical else 'Not part of smoke',
                    expected_measured=sum(5 * (3 if historical and c['id'].startswith(('primary-', 'difficulty-')) else 2) for c in cases))
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    for index, case in enumerate(cases):
        backends = ['cholesky', 'cg'] if index % 2 == 0 else ['cg', 'cholesky']
        if historical and case['id'].startswith(('primary-', 'difficulty-')):
            backends.append('cg-pre')
        for backend in backends:
            batch = output / f'{case["id"]}--{backend}'
            batch.mkdir()
            batch_cp = historical[0] if backend == 'cg-pre' else classpath
            batch_sha = historical[1]['implementation_sha'] if backend == 'cg-pre' else sha
            batch_hash = historical[1]['source_hash'] if backend == 'cg-pre' else source_hash
            base = dict(case=case['id'], seed=int(case['seed']), backend=backend,
                        tolerance=float(case['tolerance']), heap_gib=int(case['heap_gib']),
                        implementation_sha=batch_sha, source_hash=batch_hash)
            excluded = backend == 'cholesky' and 16 * int(case['m']) ** 2 > int(case['heap_gib']) * 1024 ** 3 / 2
            status = 'ResourceExcluded' if excluded else 'Unrun'
            reason = '16*m*m exceeds half driver heap' if excluded else 'Application did not produce this repetition'
            if not excluded:
                (batch / 'host-before.json').write_text(json.dumps({'load_average': os.getloadavg(),
                    'memory': Path('/proc/meminfo').read_text()}) + '\n')
                command = ['java', f'-Xms{case["heap_gib"]}g', f'-Xmx{case["heap_gib"]}g',
                           f'-Dbenchmark.sha={batch_sha}', f'-Dbenchmark.sourceHash={batch_hash}',
                           '-Dspark.master=local[4]', '-cp', batch_cp, MAIN,
                           str(batch), str(output / 'cases.csv'), case['id'], 'cg' if backend == 'cg-pre' else backend, '8']
                (batch / 'command.json').write_text(json.dumps(command, indent=2) + '\n')
                print(f'[{index + 1}/{len(cases)}] {case["id"]} {backend}', flush=True)
                with (batch / 'application.log').open('w') as log:
                    try:
                        result = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                                                timeout=6 * 1800 + 600)
                        code = result.returncode
                    except subprocess.TimeoutExpired:
                        code = 124
                (batch / 'exit.json').write_text(json.dumps({'exit_code': code}) + '\n')
                if code:
                    log_text = (batch / 'application.log').read_text(errors='replace')
                    status = 'Timeout' if code == 124 else ('OOM' if 'OutOfMemoryError' in log_text else 'ProcessFailure')
                    reason = f'Application exit {code}; see application.log'
            records = batch / 'records.jsonl'
            present = set()
            fatal_recorded = False
            if records.exists():
                for line in records.read_text().splitlines():
                    record = json.loads(line)
                    present.add(record['repetition'])
                    fatal_recorded |= record['status'] in ('Timeout', 'OOM', 'ProcessFailure')
            if fatal_recorded:
                status = 'Unrun'
            with records.open('a') as writer:
                for repetition in range(6):
                    if repetition not in present:
                        writer.write(json.dumps(dict(base, repetition=repetition, warmup=repetition == 0,
                                                     status=status, reason=reason)) + '\n')
                        # A fatal process outcome belongs to the first missing repetition only.
                        if status not in ('ResourceExcluded', 'Unrun'):
                            status = 'Unrun'
            subprocess.run([sys.executable, str(ROOT / 'scripts/benchmarks/analyze.py'), str(output)], check=True)
    # DSL timing is a separate bounded smoke, never mixed into crossover pairs.
    for backend in ['cholesky', 'cg']:
        command = ['java', '-Xms4g', '-Xmx4g', '-cp', classpath,
                   'com.github.vbmacher.spark_lp.dsl.DslSmoke', str(output / f'dsl-{backend}.jsonl'), backend]
        (output / f'dsl-{backend}-command.json').write_text(json.dumps(command, indent=2) + '\n')
        with (output / f'dsl-{backend}.log').open('w') as log:
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=1800)
        (output / f'dsl-{backend}-exit.json').write_text(json.dumps({'exit_code': result.returncode}) + '\n')
    manifest['finished_utc'] = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    subprocess.run([sys.executable, str(ROOT / 'scripts/benchmarks/analyze.py'), str(output)], check=True)
    print(f'Campaign finished: {output}', flush=True)


if __name__ == '__main__':
    main()
