#!/usr/bin/env python3
"""Retain missing repetition slots after a benchmark process exits."""
import csv
import json
from pathlib import Path
import sys


def status_from_exit(code, log_text):
    """Map a benchmark process exit code to a record status."""
    if not code:
        return 'Unrun'
    if code == 124:
        return 'Timeout'
    return 'OOM' if 'OutOfMemoryError' in log_text else 'ProcessFailure'


def reconcile(records, base, repetitions, warmups, status, reason):
    records = Path(records)
    content = records.read_bytes() if records.exists() else b''
    observed = []
    offset = 0
    for line in content.splitlines(keepends=True):
        try:
            observed.append(json.loads(line))
        except (ValueError, UnicodeDecodeError):
            # A killed writer can leave an incomplete final record. Preserve its bytes.
            if offset + len(line) != len(content) or line.endswith(b'\n'):
                raise
            records.with_suffix('.truncated').write_bytes(line)
            content = content[:offset]
            records.write_bytes(content)
            break
        offset += len(line)
    if any(row['status'] in ('Timeout', 'OOM', 'ProcessFailure') for row in observed):
        status = 'Unrun'
    present = {row['repetition'] for row in observed}
    with records.open('a') as writer:
        if content and not content.endswith(b'\n'):
            writer.write('\n')
        for repetition in range(0 if warmups else 1, repetitions + 1):
            if repetition not in present:
                writer.write(json.dumps(dict(base, repetition=repetition, warmup=repetition == 0,
                                             status=status, reason=reason)) + '\n')
                if status not in ('ResourceExcluded', 'Unrun'):
                    status = 'Unrun'


def main():
    output, manifest_path, inventory_path, exit_code = sys.argv[1:]
    output = Path(output)
    manifest = json.loads(Path(manifest_path).read_text())
    with Path(inventory_path).open() as source:
        case = next(row for row in csv.DictReader(source) if row['id'] == manifest['case'])
    base = dict(case=case['id'], m=int(case['m']), n=int(case['n']), seed=int(case['seed']),
                backend=manifest['benchmark'], tolerance=float(case['tolerance']),
                heap_gib=int(case['heap_gib']), implementation_sha=manifest['implementation_sha'],
                source_hash=manifest['source_archive_sha256'])
    code = int(exit_code)
    log = output / 'application.log'
    text = log.read_text(errors='replace') if log.exists() else ''
    status = status_from_exit(code, text)
    reconcile(output / 'records.jsonl', base, manifest['repetitions'], manifest['warmups'],
              status, f'Application exit {code}; see application.log and exit.json')


if __name__ == '__main__':
    main()
