# Remaining benchmark work

Remove completed items once their records are reconciled. Results and execution settings belong in [REPORT](REPORT.md).

## Campaigns

- [ ] Complete the outstanding non-dense cases in `sparsity-and-conditioning.csv`: one warmup and five measured repetitions per eligible backend batch in fresh JVMs, with fixed source, environment and configuration and alternating backend order.
- [ ] Run the remaining dense fixtures: 500 × 1,000 and 1,000 × 2,000, with seeds 11, 29 and 47, both backends and the same repetition protocol. Repeat the 100 × 200 fixtures on an idle host before using their timings for backend comparisons.
- [ ] Complete the outstanding distributed case/configuration combinations and reconcile every scheduled repetition.
- [ ] Repeat promising or unstable shapes with seeds 29 and 47 before drawing conclusions about crossover or defaults.
- [ ] Compare 4/8/16 executors on the same wide fixture for strong scaling, then proportionally larger inputs for weak scaling.
- [ ] Measure selected `wide`, `dependent` and `degenerate` fixtures on the distributed runtime, changing one parameter at a time with matched seeds.
- [ ] Verify distributed generation, materialization and validation at the widest size; inspect spill, shuffle, skew and executor peak load.
- [ ] Complete executor heap/RSS observations for all published batches, keeping sampling intervals, task concurrency and measurement scope explicit.

Use the [case inventories and execution protocol](../../README.md). Apply the documented memory gate separately to the driver and each executor, reserving at least half the heap for runtime overhead and transient allocations. Record resource exclusions explicitly. Cap each solve at 30 minutes and retain failure and unrun outcomes.

## Validation and publication

- [ ] Reconcile each inventory/configuration/repetition with records and process exits, distinguishing unknown evidence, unrun slots, resource exclusions and attempted failures.
- [ ] Validate every claimed success against independent normalized primal, dual, gap and objective errors and the case's nonnegativity tolerance.
- [ ] Import outstanding artifacts into the Bencher bundle and regenerate the report. Keep raw evidence private; publish generic runtime settings, relative evidence filenames and checksums only.
- [ ] Separate generation, preparation, core solve, validation and release timings, and show unsuccessful durations separately from successful solve statistics.
- [ ] Report driver and executor memory separately; identify combined local RSS and whole-application peaks accurately.
- [ ] Run `python3 benchmarks/scripts/report.py check` on the complete imported result set.
- [ ] Recommend backend selection from matched fixtures and equal-accuracy results across the tested workload range.
