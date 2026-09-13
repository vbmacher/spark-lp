#!/usr/bin/env python3
"""Run a CSV campaign sequentially in fresh JVMs; preserve every measured and missing slot."""
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
AXIS = 'benchmarksSpark_3_52_12'
MAIN = 'com.github.vbmacher.spark_lp.BenchmarkRunner'
RESOURCES = ROOT / 'benchmarks/src/main/resources'
CASE_FIELDS = ['id','m','n','nonzeros_per_row','family','seed','tolerance','heap_gib']


def read_cases(path):
    with path.open(newline='') as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != CASE_FIELDS:
            raise ValueError(f'Expected case columns: {CASE_FIELDS}')
        cases = list(reader)
    if len({c['id'] for c in cases}) != len(cases):
        raise ValueError('Duplicate case IDs')
    for c in cases:
        if not 0 < int(c['m']) < int(c['n']) or not 0 < int(c['nonzeros_per_row']) <= int(c['n']):
            raise ValueError(f'Invalid dimensions: {c["id"]}')
    return cases


def source_manifest():
    paths = [ROOT / 'build.sbt']
    for folder in ['spark-lp/src', 'project', 'benchmarks/src/main', 'benchmarks/src/test', 'benchmarks/scripts']:
        paths += [p for p in (ROOT/folder).rglob('*') if p.is_file() and 'target' not in p.parts and '__pycache__' not in p.parts]
    entries = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(paths))}
    return entries, hashlib.sha256(json.dumps(entries,sort_keys=True).encode()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--campaign',choices=['solver-scaling','sparsity-and-conditioning'],default='solver-scaling')
    parser.add_argument('--inventory',type=Path)
    parser.add_argument('--smoke',action='store_true')
    parser.add_argument('--classpath',type=Path,help='Exported benchmarks Test/fullClasspath')
    parser.add_argument('--partitions',type=int,default=8)
    parser.add_argument('--repetitions',type=int,default=5)
    parser.add_argument('--warmups',type=int,choices=[0,1],default=1)
    args = parser.parse_args()
    if args.partitions < 1 or args.repetitions < 1:
        parser.error('partitions and repetitions must be positive')
    inventory = args.inventory or RESOURCES/('smoke.csv' if args.smoke else args.campaign+'.csv')
    cases = read_cases(inventory)
    output = args.output.resolve()
    output.mkdir(parents=True,exist_ok=False)
    (output/'cases.csv').write_bytes(inventory.read_bytes())
    if args.classpath:
        classpath = args.classpath.read_text().strip()
    else:
        build = subprocess.run(['sbt',f'{AXIS}/Test/compile',f'export {AXIS}/Test/fullClasspath'],cwd=ROOT,
                               stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        (output/'build.log').write_text(build.stdout)
        if build.returncode:
            raise SystemExit(f'Build failed: {output}/build.log')
        lines = [v.strip() for v in build.stdout.splitlines() if v.startswith('/') and '.jar' in v]
        if not lines:
            raise SystemExit('No classpath found in build.log')
        classpath = lines[-1]
    (output/'classpath.txt').write_text(classpath+'\n')
    sources, source_hash = source_manifest()
    with tarfile.open(output/'candidate-sources.tar.gz','w:gz') as archive:
        for path in sources:
            archive.add(ROOT/path,arcname=path)
    sha = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    env = dict(os.environ,OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='1',MKL_NUM_THREADS='1',SPARK_LOCAL_IP='127.0.0.1')
    manifest = dict(started_utc=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),campaign=args.campaign,
                    implementation_sha=sha,source_hash=source_hash,sources=sources,host=platform.uname()._asdict(),
                    mode='smoke' if args.smoke else 'full-local',warmups=args.warmups,repetitions=args.repetitions,
                    expected_measured=len(cases)*2*args.repetitions,
                    java=subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT,text=True),
                    inventory_sha256=hashlib.sha256(inventory.read_bytes()).hexdigest(),
                    blas_threads={k:env[k] for k in ['OPENBLAS_NUM_THREADS','OMP_NUM_THREADS','MKL_NUM_THREADS']})
    (output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    for index, case in enumerate(cases):
        for backend in (['cholesky','cg'] if index%2 == 0 else ['cg','cholesky']):
            batch = output/f'{case["id"]}--{backend}'
            batch.mkdir()
            m,n,heap = int(case['m']),int(case['n']),int(case['heap_gib'])
            base = dict(case=case['id'],m=m,n=n,seed=int(case['seed']),backend=backend,
                        tolerance=float(case['tolerance']),heap_gib=heap,implementation_sha=sha,source_hash=source_hash)
            # Local[4] includes four concurrent task buffers in the same process as the driver.
            direct_bytes = 16*m*m + 2*4*4*m*(m+1)
            excluded = backend=='cholesky' and direct_bytes > heap*1024**3/2
            status, reason = ('ResourceExcluded','Direct numeric workspace exceeds half local heap') if excluded else ('Unrun','No record produced')
            if not excluded:
                command = ['java',f'-Xms{heap}g',f'-Xmx{heap}g',f'-Dbenchmark.sha={sha}',
                           f'-Dbenchmark.sourceHash={source_hash}','-Dspark.master=local[4]','-cp',classpath,MAIN,
                           backend,str(batch),str(output/'cases.csv'),case['id'],str(args.partitions),
                           str(args.repetitions),str(args.warmups)]
                (batch/'command.json').write_text(json.dumps(command,indent=2)+'\n')
                print(f'[{index+1}/{len(cases)}] {case["id"]} {backend}',flush=True)
                with (batch/'application.log').open('w') as log:
                    try:
                        code = subprocess.run(command,cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT,
                                              timeout=(args.repetitions+args.warmups)*1800+600).returncode
                    except subprocess.TimeoutExpired:
                        code = 124
                (batch/'exit.json').write_text(json.dumps(dict(exit_code=code))+'\n')
                if code:
                    text = (batch/'application.log').read_text(errors='replace')
                    status = 'Timeout' if code==124 else 'OOM' if 'OutOfMemoryError' in text else 'ProcessFailure'
                    reason = f'Application exit {code}; see application.log'
            records = batch/'records.jsonl'
            observed = [json.loads(v) for v in records.read_text().splitlines()] if records.exists() else []
            if any(v['status'] in ('Timeout','OOM','ProcessFailure') for v in observed):
                status = 'Unrun'
            present = {v['repetition'] for v in observed}
            with records.open('a') as writer:
                for repetition in range(0 if args.warmups else 1,args.repetitions+1):
                    if repetition not in present:
                        writer.write(json.dumps(dict(base,repetition=repetition,warmup=repetition==0,status=status,reason=reason))+'\n')
                        if status not in ('ResourceExcluded','Unrun'):
                            status = 'Unrun'
            subprocess.run([sys.executable,str(ROOT/'benchmarks/scripts/analyze.py'),str(output)],check=True)
    manifest['finished_utc'] = time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())
    (output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    subprocess.run([sys.executable,str(ROOT/'benchmarks/scripts/analyze.py'),str(output)],check=True)
    print(f'Campaign finished: {output}',flush=True)


if __name__=='__main__':
    main()
