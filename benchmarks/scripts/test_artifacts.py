import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from reconcile import reconcile
from report import import_jsonl, validate

SCRIPTS = Path(__file__).resolve().parent
HEADER = 'id,m,n,nonzeros_per_row,family,seed,tolerance,heap_gib\n'
INVENTORY = HEADER + 'small,2,4,2,well,11,1e-8,1\n'


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_reconciliation_preserves_records_and_marks_only_first_missing_as_failure(self):
        for warmups in (0, 1):
            for status in ('Timeout', 'OOM', 'ProcessFailure', 'Unrun', 'ResourceExcluded'):
                with self.subTest(warmups=warmups, status=status):
                    path = self.root / 'records.jsonl'
                    existing = dict(repetition=0 if warmups else 1, status='Success')
                    path.write_text(json.dumps(existing) + '\n')
                    reconcile(path, {'case': 'small'}, 3, warmups, status, 'test')
                    rows = [json.loads(line) for line in path.read_text().splitlines()]
                    self.assertEqual(rows[0], existing)
                    self.assertEqual([r['repetition'] for r in rows], list(range(0 if warmups else 1, 4)))
                    self.assertEqual(rows[1]['status'], status)
                    self.assertEqual(rows[-1]['status'], status if status in ('Unrun', 'ResourceExcluded') else 'Unrun')
                    before = path.read_bytes()
                    reconcile(path, {}, 3, warmups, status, 'test')
                    self.assertEqual(path.read_bytes(), before)

    def test_watchdog_failure_is_not_duplicated_and_truncated_tail_is_preserved(self):
        path = self.root / 'records.jsonl'
        original = json.dumps(dict(repetition=0, status='Timeout')) + '\n'
        path.write_text(original + '{"repetition":')
        reconcile(path, {}, 3, 1, 'ProcessFailure', 'test')
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        self.assertEqual([r['status'] for r in rows], ['Timeout', 'Unrun', 'Unrun', 'Unrun'])
        self.assertEqual(path.with_suffix('.truncated').read_text(), '{"repetition":')
        self.assertTrue(path.read_text().startswith(original))

    def test_local_and_emr_import_recover_inventory_and_expected_slots(self):
        for emr in (False, True):
            with self.subTest(emr=emr):
                root = self.root / str(emr)
                metadata = root / 'input' if emr else root
                metadata.mkdir(parents=True)
                inventory_name = 'emr-scaling.csv' if emr else 'cases.csv'
                (metadata / inventory_name).write_text(INVENTORY)
                manifest = dict(expected_measured=3, repetitions=3, host={'node': 'test'})
                if emr:
                    manifest['inventory'] = inventory_name
                    manifest.update(case='small', benchmark='cg')
                    del manifest['expected_measured']  # Original EMR manifests recorded only repetitions.
                (metadata / 'manifest.json').write_text(json.dumps(manifest))
                results = root / 'results'
                results.mkdir()
                (results / 'records.jsonl').write_text(json.dumps(dict(
                    case='small', backend='cg', repetition=1, status='ProcessFailure')) + '\n')
                campaign = import_jsonl(root, 'test')
                validate(campaign)
                self.assertEqual(campaign['cases'][0]['shape']['seed'], '11')
                self.assertEqual(campaign['cases'][0]['m'], 2)
                self.assertEqual(campaign['configurations'][0]['campaign_environment']['host'], {'node': 'test'})
                self.assertTrue(any('missing_measured_slots=2' in note for note in campaign['notes']))
                self.assertIn(str((metadata / inventory_name).relative_to(root)), [s['path'] for s in campaign['sources']])

    def executable(self, path, text):
        path.write_text(text)
        path.chmod(0o755)

    def test_emr_bundle_and_remote_exit_handling_without_aws(self):
        # Build a small fake checkout so the build path can be exercised without sbt or AWS.
        repo = self.root / 'repo'
        scripts = repo / 'benchmarks/scripts'
        scripts.mkdir(parents=True)
        for name in ('cluster.sh', 'reconcile.py'):
            shutil.copyfile(SCRIPTS / name, scripts / name)
        resources = repo / 'benchmarks/src/main/resources'
        resources.mkdir(parents=True)
        (resources / 'emr-scaling.csv').write_text(INVENTORY)
        (repo / 'spark-lp/src').mkdir(parents=True)
        (repo / 'project').mkdir()
        (repo / 'build.sbt').write_text('// fixture\n')
        bin_dir = self.root / 'bin'
        bin_dir.mkdir()
        sha = 'a' * 40
        self.executable(bin_dir / 'git', '#!/bin/sh\nprintf "%s\\n" ' + sha + '\n')
        self.executable(bin_dir / 'sbt', '''#!/bin/sh
mkdir -p benchmarks/target/spark_3.5-jvm-2.12
printf jar > benchmarks/target/spark_3.5-jvm-2.12/benchmarks-assembly-test.jar
''')
        env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ['PATH'], TMPDIR=str(self.root))
        command = ['bash', str(scripts / 'cluster.sh'), '--cluster-id', 'j-TEST', '--region', 'us-east-1',
                   '--s3-prefix', 's3://test-bucket/runs', '--benchmark', 'cg', '--case', 'small',
                   '--repetitions', '3', '--dry-run']
        subprocess.run(command, env=env, check=True, capture_output=True, text=True)
        bundle = next(self.root.glob('spark-lp-emr.*'))
        manifest = json.loads((bundle / 'manifest.json').read_text())
        self.assertEqual(manifest['implementation_sha'], sha)
        self.assertEqual(manifest['expected_measured'], 3)
        args = json.loads((bundle / 'steps.json').read_text())[0]['Args'][4:]
        self.assertEqual(args[-1], sha)
        subprocess.run(['bash', '-n', str(bundle / 'execute.sh')], check=True)
        self.executable(bin_dir / 'aws', '''#!/usr/bin/env python3
import os, pathlib, shutil, sys
source, destination = sys.argv[5:7]
if source.startswith('s3://'):
    shutil.copyfile(pathlib.Path(os.environ['TEST_BUNDLE']) / pathlib.Path(source).name, destination)
    pathlib.Path(os.environ['TEST_WORK']).write_text(str(pathlib.Path(destination).parent))
else:
    shutil.copytree(source, os.environ['TEST_CAPTURE'], dirs_exist_ok=True)
    sys.exit(int(os.environ.get('TEST_UPLOAD_EXIT', '0')))
''')
        self.executable(bin_dir / 'timeout', '#!/bin/sh\nshift 3\nexec "$@"\n')
        self.executable(bin_dir / 'spark-submit', '''#!/usr/bin/env python3
import json, os, pathlib, sys
assert '-Dbenchmark.sha=' + 'a'*40 in sys.argv[sys.argv.index('--driver-java-options')+1]
output = pathlib.Path(sys.argv[-6])
if os.environ.get('TEST_WATCHDOG'):
    (output / 'records.jsonl').write_text(json.dumps(dict(case='small', backend='cg', m=2, n=4, repetition=0, status='Timeout'))+'\\n')
if os.environ.get('TEST_OOM'):
    print('java.lang.OutOfMemoryError: test')
sys.exit(int(os.environ['TEST_EXIT']))
''')
        for code, watchdog, oom, upload, expected in (
                (0, False, False, 0, 'Unrun'), (124, False, False, 0, 'Timeout'),
                (1, False, True, 0, 'OOM'), (7, False, False, 0, 'ProcessFailure'),
                (124, True, False, 0, 'Timeout'), (7, False, False, 1, 'ProcessFailure'),
                (0, False, False, 1, 'Unrun')):
            with self.subTest(code=code, watchdog=watchdog, oom=oom, upload=upload):
                capture = self.root / 'capture'
                if capture.exists():
                    shutil.rmtree(capture)
                run_env = dict(env, TEST_BUNDLE=str(bundle), TEST_CAPTURE=str(capture),
                               TEST_WORK=str(self.root / 'work'), TEST_EXIT=str(code),
                               TEST_UPLOAD_EXIT=str(upload), TEST_WATCHDOG='1' if watchdog else '',
                               TEST_OOM='1' if oom else '')
                try:
                    result = subprocess.run(['bash', str(bundle / 'execute.sh')] + args,
                                            env=run_env, capture_output=True, text=True)
                    self.assertEqual(result.returncode, code or upload, result.stderr)
                    rows = [json.loads(line) for line in (capture / 'records.jsonl').read_text().splitlines()]
                    self.assertEqual([r['repetition'] for r in rows], [0, 1, 2, 3])
                    self.assertEqual([r['status'] for r in rows], [expected, 'Unrun', 'Unrun', 'Unrun'])
                    self.assertEqual(json.loads((capture / 'exit.json').read_text())['exit_code'], code)
                finally:
                    work_path = self.root / 'work'
                    if work_path.exists():
                        shutil.rmtree(work_path.read_text())
        # A prebuilt assembly must not be attributed to the launcher's checkout.
        prebuilt = self.root / 'prebuilt.jar'
        prebuilt.write_bytes(b'prebuilt')
        result = subprocess.run(command + ['--jar', str(prebuilt)], env=env, check=True, capture_output=True, text=True)
        path = next(line.split(': ', 1)[1] for line in result.stdout.splitlines() if line.startswith('Local bundle:'))
        self.assertEqual(json.loads((Path(path) / 'manifest.json').read_text())['implementation_sha'], 'unrecorded')


if __name__ == '__main__':
    unittest.main()
