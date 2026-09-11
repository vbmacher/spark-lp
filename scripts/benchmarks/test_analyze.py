import json
from pathlib import Path
import tempfile
import unittest

import analyze


class AnalysisTests(unittest.TestCase):
    def test_failure_states_do_not_enter_paired_speedups(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'cases.csv').write_text('id\na\n')
            batch = root / 'batch'
            batch.mkdir()
            good = dict(case='a', repetition=1, warmup=False, status='Success', backend='cholesky',
                        tolerance=1e-8, heap_gib=4, hash='fixture', source_hash='source', solve_seconds=2.0,
                        residuals=dict(primal=1e-10, dual=1e-10, gap=1e-10, objective_error=1e-10, min_x=0, min_s=0))
            failed = dict(good, backend='cg', status='AccuracyFailure', solve_seconds=0.001)
            (batch / 'records.jsonl').write_text('\n'.join(map(json.dumps, [good, failed])) + '\n')
            analyze.main(directory)
            report = json.loads((root / 'summary.json').read_text())
            self.assertEqual(report['paired_speedups'], {})
            self.assertEqual(report['states'], {'Success': 1, 'AccuracyFailure': 1})
            failed.update(status='Success', hash='different-fixture')
            (batch / 'records.jsonl').write_text('\n'.join(map(json.dumps, [good, failed])) + '\n')
            analyze.main(directory)
            self.assertEqual(json.loads((root / 'summary.json').read_text())['paired_speedups'], {})
            failed['hash'] = 'fixture'
            (batch / 'records.jsonl').write_text('\n'.join(map(json.dumps, [good, failed])) + '\n')
            analyze.main(directory)
            self.assertEqual(json.loads((root / 'summary.json').read_text())['paired_speedups']['a']['median'], 2000)

    def test_invalid_success_cannot_pass(self):
        row = dict(tolerance=1e-8, residuals=dict(primal=0, dual=0, gap=0, objective_error=0, min_x=0, min_s=0))
        self.assertTrue(analyze.valid(row))
        for field in ['primal', 'dual', 'gap', 'objective_error']:
            for value in [float('nan'), float('inf'), -1, 1e-8]:
                self.assertFalse(analyze.valid(dict(row, residuals=dict(row['residuals'], **{field: value}))))

    def test_event_accounting_excludes_validation_and_counts_task_attempts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'events').mkdir()
            records = [
                {'Event': 'SparkListenerJobStart', 'Job ID': 1, 'Stage IDs': [10], 'Properties': {'spark.jobGroup.id': 'solve'}},
                {'Event': 'SparkListenerJobStart', 'Job ID': 2, 'Stage IDs': [20], 'Properties': {'spark.jobGroup.id': 'solve-validation'}},
                {'Event': 'SparkListenerTaskEnd', 'Stage ID': 10, 'Task Metrics': {'JVM GC Time': 3,
                    'Shuffle Read Metrics': {'Remote Bytes Read': 2, 'Local Bytes Read': 5},
                    'Shuffle Write Metrics': {'Shuffle Bytes Written': 11}, 'Peak Execution Memory': 19}},
                {'Event': 'SparkListenerTaskEnd', 'Stage ID': 20, 'Task Metrics': {'JVM GC Time': 100}},
                {'Event': 'SparkListenerStageCompleted', 'Stage Info': {'Stage ID': 10}}]
            (root / 'events' / 'eventlog').write_text('\n'.join(map(json.dumps, records)) + '\n{')
            result = analyze.events(root)
            self.assertEqual(result['solve'], dict(jobs=1, tasks=1, gc_ms=3, shuffle_read_bytes=7,
                                                   shuffle_write_bytes=11, peak_task_execution_bytes=19, stage_attempts=1))
            self.assertEqual(result['solve-validation']['gc_ms'], 100)


if __name__ == '__main__':
    unittest.main()
