#!/usr/bin/env python3
"""Build the focused benchmark reports published under ``benchmarks/reports``.

The detailed studies remain beside their runners and raw evidence.  This module
publishes deterministic copies with links rewritten for the central report
directory, and supplies a compact cross-study index for the main report and
offline dashboard.
"""
import csv
import json
import os
from pathlib import Path
import re


STUDIES = (
    dict(
        slug='mip-search',
        source='benchmarks/reports/mip-search/README.md',
        title='MIP search',
        status='Validated locally',
        evidence='64 samples and 562 progress events',
        finding='Two-node parallel search was fastest on both control fixtures; enabling every option was slower than baseline.',
        limit='Small knapsack fixtures do not establish large-MIP scalability.',
        checks=(
            dict(path='benchmarks/reports/mip-search/results/records.csv', format='csv', expected=64),
            dict(path='benchmarks/reports/mip-search/results/progress.csv', format='csv', expected=562))),
    dict(
        slug='presolve',
        source='benchmarks/reports/presolve/README.md',
        title='Presolve',
        status='Validated locally',
        evidence='24 independently validated samples',
        finding='Full presolve removed every solver row and column in two reducible fixtures, but its setup cost exceeded the saved solve time.',
        limit='Small local fixtures do not establish cluster-scale speedups.',
        checks=(dict(path='benchmarks/reports/presolve/results/records.csv', format='csv', expected=24),)),
    dict(
        slug='warm-starts',
        source='benchmarks/reports/warm-starts/README.md',
        title='Warm starts',
        status='Validated locally',
        evidence='16 independently validated samples',
        finding='The LP start saved one outer iteration but increased elapsed time; the MIP start retained an incumbent without reducing searched nodes.',
        limit='The measured fixtures show behavior, not a universal speedup.',
        checks=(dict(path='benchmarks/reports/warm-starts/results/records.csv', format='csv', expected=16),)),
    dict(
        slug='quadratic',
        source='benchmarks/reports/quadratic/README.md',
        title='Quadratic objectives',
        status='Validated locally',
        evidence='16 Spark attempts and 3 lifted-system cases',
        finding='All Spark attempts were optimal; diagonal curvature favored the separable path, while coupled free-variable models required regularized CG.',
        limit='Large distributed QP crossover remains unmeasured.',
        checks=(
            dict(path='benchmarks/reports/quadratic/comparison.csv', format='csv', expected=16),
            dict(path='benchmarks/reports/quadratic/strategy-results.json', format='json', expected=3))),
    dict(
        slug='gpu',
        source='benchmarks/reports/gpu/README.md',
        title='GPU acceleration',
        status='Production deferred',
        evidence='4 GPU operator cases and 12 CPU solver attempts',
        finding='The tested Apple GPU lacked FP64 and FP32 missed the accuracy target, so the CPU implementation remains the supported path.',
        limit='Other GPU vendors and end-to-end FP64 acceleration remain untested.',
        checks=(
            dict(path='benchmarks/reports/gpu/results/m2-opencl.jsonl', format='jsonl', expected=4,
                 field='kind', value='normal_operator'),
            dict(path='benchmarks/reports/gpu/results/cg-dense/records.jsonl', format='jsonl', expected=3),
            dict(path='benchmarks/reports/gpu/results/cg-sparse/records.jsonl', format='jsonl', expected=3),
            dict(path='benchmarks/reports/gpu/results/cholesky-dense/records.jsonl', format='jsonl', expected=3),
            dict(path='benchmarks/reports/gpu/results/cholesky-sparse/records.jsonl', format='jsonl', expected=3))),
    dict(
        slug='native-aarch64',
        source='native/netlib-aarch64/README.md',
        title='AArch64 native linear algebra',
        status='Validated on one host',
        evidence='10 factorization configurations and 24 solver records',
        finding='Native LAPACK made the 5,000-row Cholesky solve 8.58x faster; native BLAS made the tested CG solve 1.53x slower.',
        limit='Results are specific to the retained AArch64/OpenBLAS environment.',
        checks=(
            dict(path='benchmarks/reports/native-aarch64/results/aarch64-factorization.csv', format='csv', expected=10),
            dict(path='benchmarks/reports/native-aarch64/results/aarch64-solver.csv', format='csv', expected=12),
            dict(path='benchmarks/reports/native-aarch64/results/aarch64-cg.csv', format='csv', expected=12))),
)


_LINK = re.compile(r'(?P<prefix>!?\[[^\]]*\]\()(?P<target>[^)]+)(?P<suffix>\))')


def _relative_path(path, start):
    return Path(os.path.relpath(path, start=start)).as_posix()


def _rewrite_links(markdown, source, destination, root):
    """Rewrite and validate repository-local Markdown links."""
    def replace(match):
        target = match.group('target')
        if target.startswith(('#', 'http://', 'https://', 'mailto:')):
            return match.group(0)
        path, separator, fragment = target.partition('#')
        resolved = (source.parent / path).resolve()
        try:
            resolved.relative_to(root)
        except ValueError as error:
            raise ValueError(f'{source}: link leaves repository: {target}') from error
        if not resolved.exists():
            raise ValueError(f'{source}: missing linked evidence: {target}')
        rewritten = _relative_path(resolved, destination.parent)
        if separator:
            rewritten += '#' + fragment
        return match.group('prefix') + rewritten + match.group('suffix')

    return _LINK.sub(replace, markdown)


def _render_study(study, source, destination, root):
    markdown = _rewrite_links(source.read_text(encoding='utf-8'), source, destination, root)
    heading, separator, body = markdown.partition('\n')
    source_href = _relative_path(source, destination.parent)
    navigation = ('[All benchmark reports](README.md) · '
                  f'[Study source]({source_href})')
    coverage = f'Evidence coverage: **{study["evidence"]}**.'
    return (heading + '\n\n' + navigation + '\n\n' + coverage + '\n' +
            (separator + body if separator else ''))


def _validate_evidence(root, check):
    path = root / check['path']
    if not path.is_file():
        raise ValueError(f'Missing specialized benchmark evidence: {path}')
    if check['format'] == 'csv':
        with path.open(newline='', encoding='utf-8') as stream:
            count = sum(1 for _ in csv.DictReader(stream))
    elif check['format'] == 'json':
        value = json.loads(path.read_text(encoding='utf-8'))
        count = len(value)
    elif check['format'] == 'jsonl':
        values = [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()
                  if line.strip()]
        if 'field' in check:
            values = [value for value in values
                      if value.get(check['field']) == check['value']]
        count = len(values)
    else:
        raise ValueError(f'Unsupported specialized evidence format: {check["format"]}')
    if count != check['expected']:
        raise ValueError(
            f'{path}: expected {check["expected"]} evidence rows, found {count}')


def _overview(studies):
    rows = [
        '## Capability studies',
        '',
        'These focused reports consolidate the benchmark evidence that uses dedicated '
        'runners or hardware rather than the shared Cholesky/CG campaign format. Each '
        'report keeps its environment, limitations, raw evidence links and reproduction '
        'command next to the measured conclusion.',
        '',
        '| Study | Status | Evidence | Result | Important limit |',
        '|---|---|---|---|---|',
    ]
    for study in studies:
        rows.append(
            f'| [{study["title"]}]({study["href"]}) | {study["status"]} | '
            f'{study["evidence"]} | {study["finding"]} | {study["limit"]} |')
    return '\n'.join(rows)


def build(root, reports):
    """Return study metadata, overview Markdown and generated topic files."""
    root = Path(root).resolve()
    reports = Path(reports).resolve()
    studies, files = [], {}
    for definition in STUDIES:
        study = dict(definition)
        for check in study.pop('checks'):
            _validate_evidence(root, check)
        source = root / study['source']
        destination = reports / f'{study["slug"]}.md'
        if not source.is_file():
            raise ValueError(f'Missing specialized benchmark report source: {source}')
        study['href'] = destination.name
        studies.append(study)
        files[destination] = _render_study(study, source, destination, root)
    return dict(studies=studies, overview=_overview(studies), files=files)
