# Reject contradictory scaled duplicate equalities

**id** feature-138
**dependencies** []
**read_set** ['Ticket and baseline tests']
**write_set** ['.worktrees/feature-138/spark-lp/src/main/scala/com/github/vbmacher/spark_lp/dsl/compiler/LpCompiler.scala', '.worktrees/feature-138/spark-lp/src/test/scala relevant regression suite only']
**side_effects** ['Coordinator alone stages, commits and pushes refs/heads/feature-138']
**owner** compiler
**inputs** ['development d4c121e', 'GitHub issue #138']
**expected_outputs** ['Minimal source diff and regression', 'One verified pushed commit']
**validation** ['LpDslValidationSuite', 'Spark 3.5.6 minimum', 'Combined compatibility where relevant']
**evidence_required** ['Reviewed diff, regression runtime and local/remote SHA']
**stop_condition** Implementation and validation ready for coordinator approval, or identify concrete blocker
**status** pending
