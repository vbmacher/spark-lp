# Recognize signed unnamed LP rows at a new line

**id** feature-142
**dependencies** []
**read_set** ['Ticket and baseline tests']
**write_set** ['.worktrees/feature-142/spark-lp/src/main/scala/com/github/vbmacher/spark_lp/dsl/LpImport.scala', '.worktrees/feature-142/spark-lp/src/test/scala relevant regression suite only']
**side_effects** ['Coordinator alone stages, commits and pushes refs/heads/feature-142']
**owner** formats
**inputs** ['development d4c121e', 'GitHub issue #142']
**expected_outputs** ['Minimal source diff and regression', 'One verified pushed commit']
**validation** ['LpImportSuite', 'Spark 3.5.6 minimum', 'Combined compatibility where relevant']
**evidence_required** ['Reviewed diff, regression runtime and local/remote SHA']
**stop_condition** Implementation and validation ready for coordinator approval, or identify concrete blocker
**status** pending
