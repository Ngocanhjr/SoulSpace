import importlib.util
import json
import math
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from test_helpers import fixture, read, write, seal_run

BASE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("evaluation", BASE / "runner" / "03-evaluate.py")
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)


class EvaluateTests(unittest.TestCase):
    def test_graded_metrics_use_whole_corpus_and_count_label_two_once(self):
        result = evaluation.rank_metrics(["a", "b", "c"], {"a": 0, "b": 2, "c": 1, "d": 1})
        self.assertEqual(result["precision_at_5"], .4)
        self.assertAlmostEqual(result["recall_at_5"], 2 / 3)
        self.assertEqual(result["reciprocal_rank_at_10"], .5)
        expected = (3 / math.log2(3) + .5) / (3 + 1 / math.log2(3) + .5)
        self.assertAlmostEqual(result["ndcg_at_10"], expected)
        self.assertEqual(result["relevant_total"], 3)

    def test_ndcg_rewards_putting_label_two_first(self):
        labels = {"a": 1, "b": 2}
        low = evaluation.rank_metrics(["a", "b"], labels)
        high = evaluation.rank_metrics(["b", "a"], labels)
        self.assertGreater(high["ndcg_at_10"], low["ndcg_at_10"])
        self.assertAlmostEqual(high["ndcg_at_10"], 1)
        self.assertEqual(high["precision_at_5"], low["precision_at_5"])

    def test_no_relevant_posts_produces_na_without_dividing_by_zero(self):
        result = evaluation.rank_metrics([], {"a": 0})
        self.assertEqual(result["precision_at_5"], 0)
        self.assertEqual(result["reciprocal_rank_at_10"], 0)
        self.assertIsNone(result["recall_at_5"])
        self.assertIsNone(result["ndcg_at_10"])

    def test_reciprocal_rank_is_cut_off_at_ten(self):
        labels = {str(i): int(i == 10) for i in range(11)}
        self.assertEqual(evaluation.rank_metrics(list(labels), labels)["reciprocal_rank_at_10"], 0)

    def test_d_and_e_are_included_and_empty_results_still_count(self):
        with tempfile.TemporaryDirectory() as directory:
            path = fixture(Path(directory))
            per_query, summary, latency = evaluation.evaluate(path, path, path / "ground-truth-final.csv")
            self.assertEqual({r["group"] for r in per_query}, {"A", "D", "E"})
            total = next(r for r in summary if r["group"] == "ALL")
            self.assertEqual(total["queries"], 3)
            self.assertAlmostEqual(total["precision_at_5"], .4 / 3)
            self.assertAlmostEqual(total["recall_at_5"], (2 / 3) / 2)
            self.assertEqual(total["recall_valid_queries"], 2)
            self.assertEqual(total["ndcg_valid_queries"], 2)
            self.assertEqual(latency, [])

    def test_cli_uses_shared_data_without_copies_in_run_folder(self):
        with tempfile.TemporaryDirectory() as directory:
            path = fixture(Path(directory))
            original = (path / "ground-truth-final.csv").read_bytes()
            run = path / "run"
            run.mkdir()
            for name in ("results.csv", "timings.csv", "run-info.json"):
                (path / name).rename(run / name)
            with patch.object(evaluation, "BASE", path), \
                    patch("sys.argv", ["03-evaluate.py", str(run)]), patch("builtins.print"):
                evaluation.main()
            output, = run.glob("evaluation-*")
            summary = read(output / "metrics-summary.csv")
            self.assertIn("recall_at_5", summary[0])
            self.assertIn("ndcg_at_10", summary[0])
            self.assertIn("N/A", (output / "summary.md").read_text(encoding="utf-8"))
            self.assertEqual((path / "ground-truth-final.csv").read_bytes(), original)
            self.assertFalse((output / "negative-queries.csv").exists())
            self.assertFalse((run / "corpus.csv").exists())

    def test_full_labels_required_even_for_posts_outside_results(self):
        with tempfile.TemporaryDirectory() as directory:
            path = fixture(Path(directory))
            labels = read(path / "ground-truth-final.csv")
            labels = [r for r in labels if not (r["query_id"] == "Q01" and r["blog_id"] == "d")]
            write(path / "ground-truth-final.csv", labels)
            seal_run(path)
            with self.assertRaisesRegex(ValueError, "nhãn|cặp"):
                evaluation.evaluate(path, path, path / "ground-truth-final.csv")

    def test_rejects_changed_outputs_labels_and_incomplete_runs(self):
        for fault in ("results", "labels", "incomplete", "rank_gap", "missing_timing"):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as directory:
                path = fixture(Path(directory))
                if fault in ("results", "rank_gap"):
                    rows = read(path / "results.csv")
                    rows[1]["rank"] = "4"
                    write(path / "results.csv", rows)
                    if fault == "rank_gap":
                        seal_run(path)
                elif fault == "labels":
                    rows = read(path / "ground-truth-final.csv")
                    rows[0]["label"] = "2"
                    write(path / "ground-truth-final.csv", rows)
                else:
                    info = json.loads((path / "run-info.json").read_text())
                    info.update(complete=fault != "incomplete", method="both", repeats=2)
                    (path / "run-info.json").write_text(json.dumps(info))
                with self.assertRaises(ValueError):
                    evaluation.evaluate(path, path, path / "ground-truth-final.csv")

    def test_latency_uses_successes_and_nearest_rank_p95(self):
        rows = [{"elapsed_ms": str(i), "status": "ok"} for i in range(1, 21)]
        rows.append({"elapsed_ms": "999", "status": "error"})
        self.assertEqual(evaluation.latency_stats(rows), dict(attempts=21, successes=20, errors=1, p50_ms=10.5, p95_ms=19))


if __name__ == "__main__":
    unittest.main()
