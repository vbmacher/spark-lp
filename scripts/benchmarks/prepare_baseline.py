#!/usr/bin/env python3
"""Build historical CG with only the final-iterate inspection hook added; preserve its patch."""
import io
import json
from pathlib import Path
import subprocess
import tarfile
import sys

from run import ROOT, AXIS


def prepare(destination):
    destination = Path(destination).resolve()
    destination.mkdir(parents=True, exist_ok=False)
    sha = subprocess.check_output(['git', 'rev-parse', 'd0e4939'], cwd=ROOT, text=True).strip()
    archive = subprocess.check_output(['git', 'archive', sha], cwd=ROOT)
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        tar.extractall(destination, filter='data')
    lp_path = destination / 'spark-lp/src/main/scala/com/github/vbmacher/spark_lp/LP.scala'
    original = lp_path.read_text()
    old = '    stopAfterIteration: Option[Int => Boolean] = None\n'
    assert original.count(old) == 1
    patched = original.replace(old, old.rstrip() + ',\n    inspectConverged: Option[(DVector, DenseVector, DVector) => Unit] = None\n')
    old = '      SolveSummary(\n'
    assert patched.count(old) == 1
    patched = patched.replace(old, '      if (converged) inspectConverged.foreach(_(x, lambda, s))\n\n' + old)
    lp_path.write_text(patched)
    import difflib
    (destination / 'diagnostic.patch').write_text(''.join(difflib.unified_diff(original.splitlines(True), patched.splitlines(True), fromfile='LP.scala', tofile='LP.scala')))
    folder = Path('spark-lp/src/test/scala/com/github/vbmacher/spark_lp/benchmarks')
    (destination / folder).mkdir(parents=True, exist_ok=True)
    (destination / folder / 'Fixtures.scala').write_bytes((ROOT / folder / 'Fixtures.scala').read_bytes())
    harness = (ROOT / folder / 'MatrixFreeBenchmark.scala').read_text()
    start = harness.index('      def progress(event: SolveProgress): Unit = {')
    end = harness.index('      try {\n        val result = LP.solveSummary', start)
    harness = harness[:start] + harness[end:]
    harness = harness.replace('          control = SolveControl(onProgress = Some(progress)),\n', '')
    harness = harness.replace('result.innerIterations', 'None').replace('result.innerRestarts', 'None').replace('result.preconditionerRank', 'None')
    harness = harness.replace('"phase_seconds" -> phaseTimes.toMap', '"phase_seconds" -> None')
    harness = harness.replace('(if (args(3) == "cg") 1e-8 else 0.0)', '0.0')
    harness = harness.replace('"backend" -> args(3)', '"backend" -> "cg-pre"')
    (destination / folder / 'MatrixFreeBenchmark.scala').write_text(harness)
    command = ['sbt', f'{AXIS}/Test/compile', f'export {AXIS}/Test/fullClasspath']
    result = subprocess.run(command, cwd=destination, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    (destination / 'build.log').write_text(result.stdout)
    if result.returncode:
        raise RuntimeError(f'Historical build failed: {destination}/build.log')
    cp = [line.strip() for line in result.stdout.splitlines() if line.startswith('/') and '.jar' in line][-1]
    (destination / 'classpath.txt').write_text(cp + '\n')
    import hashlib
    sources = {str(p.relative_to(destination)): hashlib.sha256(p.read_bytes()).hexdigest()
               for p in sorted((destination / 'spark-lp/src').rglob('*.scala'))}
    digest = hashlib.sha256(json.dumps(sources, sort_keys=True).encode()).hexdigest()
    metadata = dict(implementation_sha=sha, source_hash=digest, sources=sources,
                    diagnostic_patch='diagnostic.patch', unavailable=['cg_steps', 'cg_restarts', 'maximum_rank', 'phase_seconds', 'initialization_seconds'])
    (destination / 'manifest.json').write_text(json.dumps(metadata, indent=2) + '\n')
    return cp, metadata


if __name__ == '__main__':
    prepare(sys.argv[1])
