# Preserve binary domains in exported MPS and HiGHS

**id** feature-139
**dependencies** []
**read_set** ['Ticket and baseline tests']
**write_set** ['.worktrees/feature-139/spark-lp/src/main/scala/com/github/vbmacher/spark_lp/dsl/LpMps.scala', '.worktrees/feature-139/spark-lp/src/test/scala relevant regression suite only']
**side_effects** ['Coordinator alone stages, commits and pushes refs/heads/feature-139']
**owner** formats
**inputs** ['development d4c121e', 'GitHub issue #139']
**expected_outputs** ['Minimal source diff and regression', 'One verified pushed commit']
**validation** ['LpMpsSuite and LpMpsImportSuite', 'Spark 3.5.6 minimum', 'Combined compatibility where relevant']
**evidence_required** ['Reviewed diff, regression runtime and local/remote SHA']
**stop_condition** Implementation and validation ready for coordinator approval, or identify concrete blocker
**status** pending
