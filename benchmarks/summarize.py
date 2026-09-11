#!/usr/bin/env python3
"""Validate original-LP residuals and summarize successful backend comparisons only."""
import csv
import math
import statistics
import sys
from collections import defaultdict

pairs = defaultdict(lambda: defaultdict(list))
failures = []
for path in sys.argv[1:]:
    with open(path, newline="") as source:
        for row in csv.DictReader(source):
            key = tuple(row[k] for k in ("m", "n", "nonzeros_per_column", "row_scale_ratio"))
            if row["status"] != "Converged":
                failures.append((key, row["backend"], row["status"], row["error"]))
                continue
            for residual in ("primal", "dual", "gap"):
                value = float(row[residual])
                if not math.isfinite(value) or not 0 <= value < 1e-8:
                    raise ValueError(f"Invalid successful residual: {path}: {row}")
            pairs[key][row["backend"]].append(row)

print("| m | n | nnz/column | min/max row scale | Cholesky seconds | CG seconds | Cholesky/CG |")
print("|---:|---:|---:|---:|---:|---:|---:|")
for key, backends in sorted(pairs.items(), key=lambda entry: tuple(map(float, entry[0]))):
    if not all(backends.get(b) for b in ("Cholesky", "ConjugateGradient")):
        print(f"Unpaired successful case (excluded from crossover): {key}", file=sys.stderr)
        continue
    direct, cg = (statistics.median(float(r["wall_seconds"]) for r in backends[b])
                  for b in ("Cholesky", "ConjugateGradient"))
    print("| " + " | ".join(key) + f" | {direct:.3f} | {cg:.3f} | {direct / cg:.2f} |")
print(f"\nNonconverged runs: {len(failures)}")
for entry in failures:
    print(entry)
