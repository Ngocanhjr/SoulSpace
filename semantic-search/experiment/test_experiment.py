import csv
import hashlib
import importlib.util
import json
import tempfile
import unittest
import warnings
import getpass
from http.client import IncompleteRead
from collections import Counter
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.parse import urlsplit, parse_qs

from test_helpers import fixture, write, read, seal_run

BASE = Path(__file__).resolve().parent / "data"
spec = importlib.util.spec_from_file_location("experiment", BASE.parent / "runner" / "02-run-experiment.py")
experiment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(experiment)


def post(blog_id, content):
    return {"blog_id": blog_id, "content": content,
            "content_sha256": hashlib.sha256(content.encode()).hexdigest()}


class ExperimentTests(unittest.TestCase):
    def test_both_mode_collects_45_semantic_calls_without_copying_data(self):
        corpus, queries, _, _ = experiment.load_dataset()
        feed = [{"id": row["blog_id"], "content": row["content"]} for row in corpus]
        calls = []

        def fake_get(base_url, path, cookie):
            self.assertEqual(cookie, "SOULSPACESESSION=test-secret")
            if path == "/api/v1/auth/me":
                return {"user": {"id": "test-user"}}
            if path == "/api/v1/soulspaces":
                return feed
            self.assertEqual(urlsplit(path).path, "/api/v1/search")
            self.assertEqual(parse_qs(urlsplit(path).query)["limit"], ["50"])
            calls.append(path)
            return [{**feed[0], "similarity": 0.5}]

        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "run"
            with patch("sys.argv", ["02-run-experiment.py", "--method", "both", "--output", str(output)]), \
                    patch.dict("os.environ", {"SOULSPACE_SESSION": "test-secret"}), \
                    patch.object(experiment, "get_json", side_effect=fake_get), patch("builtins.print"):
                experiment.main()
            keyword_output, = (output / "keyword-search").iterdir()
            semantic_output, = (output / "semantic-search").iterdir()
            self.assertEqual(keyword_output.name, semantic_output.name)
            self.assertEqual(len(calls), 45)
            self.assertEqual(len(experiment.read_csv(semantic_output / "results.csv")), len(queries))
            for method, folder in (("keyword", keyword_output), ("semantic", semantic_output)):
                results = experiment.read_csv(folder / "results.csv")
                self.assertTrue(all(row["method"] == method for row in results))
                query_lookup = {q["query_id"]: q for q in queries}
                post_lookup = {p["blog_id"]: p for p in corpus}
                label_lookup = {(r["query_id"], r["blog_id"]): r["label"]
                                for r in experiment.read_csv(BASE / "ground-truth-final.csv")}
                for row in results:
                    self.assertEqual(row["query"], query_lookup[row["query_id"]]["query"])
                    self.assertEqual(row["content"], post_lookup[row["blog_id"]]["content"])
                    self.assertEqual(row["label"], label_lookup[row["query_id"], row["blog_id"]])
                info = json.loads((folder / "run-info.json").read_text())
                self.assertTrue(info["complete"])
                self.assertEqual(info["method"], method)
                self.assertEqual(info["queries_sha256"], experiment.file_hash(BASE / "queries.csv"))
                self.assertEqual(info["ground_truth_sha256"], experiment.file_hash(BASE / "ground-truth-final.csv"))
                self.assertEqual({p.name for p in folder.iterdir()}, {"results.csv", "timings.csv", "run-info.json"})
            self.assertEqual(experiment.read_csv(keyword_output / "timings.csv"), [])
            self.assertEqual(len(experiment.read_csv(semantic_output / "timings.csv")), 45)
            self.assertTrue(all("test-secret" not in path.read_text(encoding="utf-8-sig") for path in output.rglob("*") if path.is_file()))

    def test_default_keyword_output_uses_results_keyword_search(self):
        with tempfile.TemporaryDirectory() as directory:
            path = fixture(Path(directory))
            with patch.object(experiment, "BASE", path), patch.object(experiment, "RESULTS", path / "results"), \
                    patch("sys.argv", ["02-run-experiment.py", "--method", "keyword"]), patch("builtins.print"):
                experiment.main()
            output, = (path / "results" / "keyword-search").iterdir()
            self.assertTrue((output / "results.csv").is_file())
            rows = experiment.read_csv(output / "results.csv")
            self.assertEqual(list(rows[0]), ["group", "query_id", "query", "method", "rank",
                                            "blog_id", "content", "score", "label"])
            labels = {(r["query_id"], r["blog_id"]): r["label"] for r in read(path / "ground-truth-final.csv")}
            for row in rows:
                self.assertEqual(row["label"], labels[row["query_id"], row["blog_id"]])
            self.assertTrue((path / "results" / "semantic-search").is_dir())
            self.assertFalse((path / "runs").exists())

    def test_changing_shared_data_during_retrieval_marks_run_incomplete(self):
        with tempfile.TemporaryDirectory() as directory:
            path = fixture(Path(directory))
            original_search = experiment.keyword_search
            changed = []

            def search_and_change(query, corpus):
                ranked = original_search(query, corpus)
                if not changed:
                    queries_path = path / "queries.csv"
                    queries_path.write_bytes(queries_path.read_bytes() + b"\n")
                    changed.append(True)
                return ranked

            with patch.object(experiment, "BASE", path), patch.object(experiment, "RESULTS", path / "results"), \
                    patch.object(experiment, "keyword_search", side_effect=search_and_change), \
                    patch("sys.argv", ["02-run-experiment.py", "--method", "keyword"]), \
                    self.assertRaisesRegex(ValueError, "thay đổi"):
                experiment.main()
            output, = (path / "results" / "keyword-search").iterdir()
            self.assertFalse(json.loads((output / "run-info.json").read_text())["complete"])

    def test_failed_semantic_run_is_marked_incomplete_with_error_log(self):
        corpus, _, _, _ = experiment.load_dataset()
        feed = [{"id": row["blog_id"], "content": row["content"]} for row in corpus]

        def fake_get(base_url, path, cookie):
            if path == "/api/v1/auth/me":
                return {"user": {}}
            if path == "/api/v1/soulspaces":
                return feed
            raise ValueError("Backend trả HTTP 503.")

        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "run"
            with patch("sys.argv", ["02-run-experiment.py", "--method", "semantic", "--output", str(output)]), \
                    patch.dict("os.environ", {"SOULSPACE_SESSION": "test-secret"}), \
                    patch.object(experiment, "get_json", side_effect=fake_get), self.assertRaises(ValueError):
                experiment.main()
            output, = (output / "semantic-search").iterdir()
            self.assertFalse(json.loads((output / "run-info.json").read_text())["complete"])
            self.assertEqual(experiment.read_csv(output / "results.csv"), [])
            self.assertEqual(experiment.read_csv(output / "timings.csv")[0]["status"], "error")

    def test_keywords_normalize_unicode_case_and_whitespace_without_counting_duplicates(self):
        corpus = [post("2", "Áp lực học tập"), post("1", "Lịch thi"), post("3", "Áp lực")]
        results = experiment.keyword_search(" A\u0301P   lực áp ", corpus)
        self.assertEqual(results, [{"id": "2", "score": 2}, {"id": "3", "score": 2}])

    def test_keywords_are_substrings_with_stable_id_ties_and_top_ten(self):
        corpus = [post(f"{index:02}", "computer") for index in range(15, -1, -1)]
        self.assertEqual([row["id"] for row in experiment.keyword_search("put", corpus)],
                         [f"{index:02}" for index in range(10)])
        self.assertEqual(experiment.keyword_search("", corpus), [])
        self.assertEqual(experiment.keyword_search("xyz", corpus), [])

    def test_semantic_rejects_unknown_changed_duplicate_and_non_finite_results(self):
        posts = {"a": post("a", "Học tập")}
        valid = {"id": "a", "content": "Học tập", "similarity": 0.7}
        self.assertEqual(experiment.semantic_results([valid], posts), [{"id": "a", "score": 0.7}])
        for data in ([{**valid, "id": "other"}], [{**valid, "content": "Đã sửa"}],
                     [valid, valid], [{**valid, "similarity": float("nan")}],
                     [{**valid, "similarity": True}], {"error": "bad"}):
            with self.subTest(data=data), self.assertRaises(ValueError):
                experiment.semantic_results(data, posts)

    def test_feed_check_allows_external_posts_but_rejects_missing_or_changed_targets(self):
        posts = {"a": post("a", "Học tập")}
        row = {"id": "a", "content": "Học tập"}
        experiment.check_public_feed([row], posts)
        experiment.check_public_feed([row, {"id": "b", "content": "Mới"}], posts)
        for data in ([], [{**row, "content": "Đã sửa"}], [row, row]):
            with self.subTest(data=data), self.assertRaises(ValueError):
                experiment.check_public_feed(data, posts)

    def test_http_errors_are_not_converted_to_empty_results_or_sensitive_bodies(self):
        error = HTTPError("http://localhost/", 401, "sensitive-detail", {}, None)
        with patch.object(experiment, "build_opener") as factory:
            factory.return_value.open.side_effect = error
            with self.assertRaisesRegex(ValueError, "HTTP 401") as raised:
                experiment.get_json("http://localhost", "/api/v1/search", "SOULSPACESESSION=secret")
            self.assertNotIn("secret", str(raised.exception))
            self.assertNotIn("sensitive-detail", str(raised.exception))

    def test_truncated_http_response_is_normalized_without_exposing_body(self):
        with patch.object(experiment, "build_opener") as factory:
            factory.return_value.open.return_value.__enter__.return_value.read.side_effect = IncompleteRead(b"sensitive-body", 10)
            with self.assertRaisesRegex(ValueError, "kết nối|thời gian") as raised:
                experiment.get_json("http://localhost", "/api/v1/soulspaces", "SOULSPACESESSION=secret")
            self.assertNotIn("sensitive", str(raised.exception))

    def test_unexpected_final_check_failure_never_marks_run_complete(self):
        corpus, _, _, _ = experiment.load_dataset()
        feed = [{"id": p["blog_id"], "content": p["content"]} for p in corpus]
        feed_calls = []

        def fake_get(base_url, path, cookie):
            if path == "/api/v1/auth/me": return {}
            if path == "/api/v1/soulspaces":
                feed_calls.append(path)
                if len(feed_calls) == 2: raise RuntimeError("Final verification interrupted")
                return feed
            return [{**feed[0], "similarity": .5}]

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "run"
            with patch("sys.argv", ["02-run-experiment.py", "--method", "semantic", "--output", str(output)]), \
                    patch.dict("os.environ", {"SOULSPACE_SESSION": "secret"}), \
                    patch.object(experiment, "get_json", side_effect=fake_get), patch("builtins.print"), \
                    self.assertRaises(RuntimeError):
                experiment.main()
            output, = (output / "semantic-search").iterdir()
            info = json.loads((output / "run-info.json").read_text())
            self.assertFalse(info["complete"])
            self.assertTrue(info["error"])
            self.assertEqual(len(experiment.read_csv(output / "timings.csv")), 45)

    def test_cookie_prompt_refuses_echo_fallback(self):
        def unavailable_hidden_input(prompt):
            warnings.warn("Cannot hide input", getpass.GetPassWarning)
            self.fail("getpass must not reach its echoed-input fallback")

        with patch("sys.argv", ["02-run-experiment.py", "--method", "semantic"]), \
                patch.dict("os.environ", {"SOULSPACE_SESSION": ""}), \
                patch.object(experiment.getpass, "getpass", side_effect=unavailable_hidden_input), \
                patch.object(experiment, "get_json") as api, \
                self.assertRaisesRegex(ValueError, "SOULSPACE_SESSION"):
            experiment.main()
        api.assert_not_called()

    def test_candidate_filtering_recovers_corpus_top_ten_before_truncating(self):
        public = {str(i): post(str(i), str(i)) for i in range(50)}
        targets = {str(i): public[str(i)] for i in range(15, 50)}
        data = [dict(id=str(i), content=str(i), similarity=1-i/100) for i in range(50)]
        results = experiment.semantic_results(data, targets, public)
        self.assertEqual([r["id"] for r in results], [str(i) for i in range(15, 25)])

    def test_truncated_candidate_list_without_ten_targets_is_rejected(self):
        public = {str(i): post(str(i), str(i)) for i in range(50)}
        targets = {"49": public["49"]}
        data = [dict(id=str(i), content=str(i), similarity=1-i/100) for i in range(50)]
        with self.assertRaisesRegex(ValueError, "top 10|50"):
            experiment.semantic_results(data, targets, public)

    def test_invalid_or_incomplete_ground_truth_is_rejected_before_search(self):
        for fault in ("blank", "missing_unreturned", "duplicate", "out_of_range", "content"):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as directory:
                path = fixture(Path(directory))
                rows = read(path / "ground-truth-final.csv")
                if fault == "blank": rows[0]["label"] = ""
                if fault == "missing_unreturned": rows.pop(3)
                if fault == "duplicate": rows.append(rows[0])
                if fault == "out_of_range": rows[0]["label"] = "3"
                if fault == "content":
                    for r in rows: r["content"] = "Changed"
                write(path / "ground-truth-final.csv", rows)
                seal_run(path)
                with patch.object(experiment, "BASE", path), self.assertRaises(ValueError):
                    experiment.load_dataset()

    def test_metadata_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = fixture(Path(directory))
            info = json.loads((path / "dataset-info.json").read_text())
            info["corpus_count"] += 1
            (path / "dataset-info.json").write_text(json.dumps(info))
            with patch.object(experiment, "BASE", path), self.assertRaisesRegex(ValueError, "dataset-info"):
                experiment.load_dataset()

    def test_real_dataset_has_350_posts_15_queries_and_all_5250_final_labels(self):
        corpus, queries, labels, digest = experiment.load_dataset()
        self.assertEqual(len(corpus), 350)
        self.assertEqual(Counter(row["group"] for row in queries), dict.fromkeys("ABCDE", 3))
        self.assertEqual(len(labels), 5250)
        self.assertEqual(list(labels[0]), ["group", "query_id", "query", "blog_id", "content", "label"])
        self.assertEqual(Counter(row["label"] for row in labels), {"0": 5181, "1": 52, "2": 17})
        info = json.loads((BASE / "dataset-info.json").read_text(encoding="utf-8"))
        self.assertEqual(digest, info["corpus_fingerprint"])


if __name__ == "__main__":
    unittest.main()
