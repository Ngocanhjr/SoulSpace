# Frozen search experiment implementation plan

**Goal:** Run the approved 15-query, 350-post, three-level ground truth through keyword and semantic retrieval; report P@5, Recall@5, MRR@10 and nDCG@10.

**Architecture:** Share frozen-dataset validation between runner and evaluator. Snapshot inputs before retrieval. Use the existing API's top 50 and retain the corpus top 10 only when completeness is established; otherwise fail the run. No backend or database mutation.

**Tech stack:** Python standard library and unittest; psycopg only for the optional database preflight.

**Spec:** User-approved majority labels in `semantic-search/experiment/ground-truth-final.csv` and the metric definitions in the test README.

## Constraints and review focus

- Preserve source corpus, queries and finalized labels byte-for-byte.
- Reject missing, duplicate, invalid or stale labels, including pairs outside returned results.
- Count labels 1 and 2 once for binary metrics; use gains 0/1/3 for nDCG.
- Include D and E in all repetitions and summaries; empty results are valid, API failures are not.
- Reject truncated candidate lists, changed content and unstable public data.
- Save hashes and frozen inputs; reject changed run outputs or labels when evaluating.

## Tasks

- [x] Write regression tests for dataset validation, 45 semantic requests, snapshots, candidate filtering and hand-calculated metrics; confirm failures.
- [x] Add shared `experiment_data.py`; update runner, evaluator, database preflight and metadata; run tests to green.
- [x] Update README and the experiment plan with runnable commands and the finalized dataset.
- [x] Run all Python experiment tests, keyword retrieval/evaluation end to end, inspect outputs and review changes.

## Execution notes

- Stay in the user-selected checkout: experiment inputs/scripts are untracked and must remain together. No branch, commit or source-data rewrite is needed.
- Local verification replaces only the HTTP boundary and does not call paid embedding APIs.
- Final verification: 26 unittest tests pass; keyword run `results/keyword-search/keyword-validation-1e4e141d` produced 150 ranked results and all four metrics for 15 queries, independently recalculated. Corpus, queries and finalized labels retain their approved hashes.
- The user moved the three agent source files into `label-comparison/` during work; source locations in README follow that organization. These files are not runner dependencies.
- Fresh read-only reviewer confirmed metric formulas, grouping, scoped candidate filtering and DB checks. Two findings fixed with failing regression tests followed by a green full suite: completion is now set only after final verification, truncated HTTP transport errors are sanitized, and hidden-cookie input refuses an echo fallback. No deferred findings.
- Layout follow-up requested by user: renamed `test` to `experiment`, migrated existing keyword runs into `results/keyword-search`, and split future keyword/semantic runs into their own branches (including `--method both`). Directory relocation verified 51 file hashes before code/document changes. Routing regression test brings the suite to 27 tests.

- Shared-data follow-up requested by user: removed per-run input copies. Runner checks data/ hashes before/after retrieval; evaluator defaults to shared data/. Existing duplicate inputs removed only after hash equality and metric equivalence checks; 28 tests pass.
