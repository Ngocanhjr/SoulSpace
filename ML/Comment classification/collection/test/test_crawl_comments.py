"""Offline checks; no external requests or credentials required."""

import csv
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from crawl_comments import CrawlError, Http, Output, facebook, html_rows, main, record, relative_path, validate, website, youtube


class CollectionTests(unittest.TestCase):
    def test_youtube_paginates_replies_and_top_level_comments(self):
        http = Mock()
        http.json.side_effect = [
            {"items": [{"snippet": {"topLevelComment": {"id": "c1", "snippet": {"textDisplay": "Xin chào"}}, "totalReplyCount": 2}}], "nextPageToken": "thread-page-2"},
            {"items": [{"id": "r1", "snippet": {"textDisplay": "Trả lời 1"}}], "nextPageToken": "reply-page-2"},
            {"items": [{"id": "r2", "snippet": {"textDisplay": "Trả lời 2"}}]},
            {"items": []},
        ]
        source = {"name": "yt", "type": "youtube", "video_ids": ["abcdefghijk"]}
        with patch.dict(os.environ, YOUTUBE_API_KEY="test-only"):
            rows = list(youtube(http, source))
        self.assertEqual([r["source_id"] for r in rows], ["c1", "r1", "r2"])
        self.assertEqual(rows[2]["parent_id"], "c1")
        self.assertEqual(http.json.call_args_list[-1].kwargs["params"]["pageToken"], "thread-page-2")
        self.assertTrue(all(r["label_id"] == "" for r in rows))

    def test_facebook_rebuilds_cursor_without_following_token_url(self):
        http = Mock()
        http.json.side_effect = [
            {"data": [{"id": "1", "message": "A", "from": {"name": "Private name"}}], "paging": {"next": "https://untrusted.invalid/?access_token=secret", "cursors": {"after": "cursor2"}}},
            {"data": [{"id": "2", "message": "B"}]},
        ]
        source = {"type": "facebook", "name": "fb", "post_ids": ["123_456"], "include_replies": False}
        with patch.dict(os.environ, FACEBOOK_PAGE_ACCESS_TOKEN="test-only", META_GRAPH_VERSION="v99.0"):
            rows = list(facebook(http, source))
        self.assertEqual(len(rows), 2)
        self.assertEqual(http.json.call_args.kwargs["params"]["after"], "cursor2")
        self.assertTrue(all(call.args[0].startswith("https://graph.facebook.com/") for call in http.json.call_args_list))
        self.assertNotIn("Private name", str(rows))

    def test_html_keeps_thread_identity_across_pages_and_links_parent(self):
        source = {"type": "forum", "name": "forum", "comment_selector": ".comment", "text_selector": ".body", "next_selector": "a.next"}
        page1 = '<title>Chủ đề</title><div class="comment" data-comment-id="1"><p class="body">Tiếng Việt 😀</p></div><a class="next" href="?page=2">Next</a>'
        page2 = '<div class="comment" data-comment-id="2" data-parent-id="1"><p class="body">Phản hồi</p></div>'
        generator = html_rows(source, page1, "https://example.org/thread")
        first = next(generator)
        with self.assertRaises(StopIteration) as end:
            next(generator)
        self.assertEqual(end.exception.value, "https://example.org/thread?page=2")
        second = next(html_rows(source, page2, "https://example.org/thread?page=2", "https://example.org/thread"))
        self.assertEqual(second["document_id"], first["document_id"])
        self.assertEqual(second["parent_id"], first["source_id"])
        self.assertEqual(first["free_text"], "Tiếng Việt 😀")

    def test_output_rerun_deduplicates_ids_but_keeps_other_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "comments.csv"
            row = record({"name": "a", "type": "youtube"}, "id", "doc", "Xin chào,\n😀")
            writer = Output(path)
            self.assertTrue(writer.write(row))
            writer.close()
            writer = Output(path)
            self.assertFalse(writer.write(row))
            self.assertTrue(writer.write({**row, "source_name": "b"}))
            writer.close()
            with path.open(encoding="utf-8", newline="") as stream:
                rows = list(csv.DictReader(stream))
            self.assertEqual(len(rows), 2)
            self.assertEqual(rows[0]["free_text"], row["free_text"])

    def test_existing_incompatible_output_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "comments.csv"
            path.write_text("old,schema\n1,2\n", encoding="utf-8")
            before = path.read_bytes()
            with self.assertRaises(CrawlError):
                Output(path)
            self.assertEqual(path.read_bytes(), before)

    def test_robots_blocks_fetching_page(self):
        http = Mock()
        http.session.headers = {"User-Agent": "SoulSpaceResearch/1.0"}
        http.get.return_value = Mock(status_code=200, text="User-agent: *\nDisallow: /private")
        with self.assertRaisesRegex(CrawlError, "robots"):
            list(website(http, {"start_urls": ["https://example.org/private"]}))
        self.assertEqual(http.get.call_count, 1)

    def test_redirect_does_not_forward_credentials(self):
        http = Http("test", delay=0)
        http.session.get = Mock(return_value=Mock(status_code=302))
        with self.assertRaisesRegex(CrawlError, "Redirect"):
            http.get("https://graph.facebook.com/", headers={"Authorization": "Bearer test"})
        self.assertFalse(http.session.get.call_args.kwargs["allow_redirects"])

    @patch("crawl_comments.time.sleep")
    def test_rate_limit_retry_and_error_redaction(self, sleep):
        http = Http("test", delay=0)
        http.session.get = Mock(side_effect=[Mock(status_code=429, headers={"Retry-After": "2"}), Mock(status_code=200)])
        self.assertEqual(http.get("https://example.org").status_code, 200)
        self.assertEqual(http.count, 2)
        http.session.get = Mock(return_value=Mock(status_code=403))
        with self.assertRaises(CrawlError) as error:
            http.get("https://example.org", params={"key": "do-not-print"})
        self.assertNotIn("do-not-print", str(error.exception))

    def test_validation_rejects_bad_ids_and_absolute_paths(self):
        with self.assertRaises(CrawlError):
            validate({"sources": [{"type": "youtube", "name": "yt", "video_ids": ["https://youtube.com/watch?v=x"]}]})
        with self.assertRaises(CrawlError):
            relative_path(Path("."), "C:/data/comments.csv")

    def test_invalid_selector_fails_before_network_collection(self):
        source = {"name": "news", "type": "news", "access_basis": "Publisher permission", "start_urls": ["https://example.org/article"], "comment_selector": ".comment", "text_selector": "p", "next_selector": "[invalid"}
        with self.assertRaisesRegex(CrawlError, "CSS selector"):
            validate({"sources": [source]})

    def test_website_refuses_cross_origin_next_page(self):
        http = Mock()
        http.session.headers = {"User-Agent": "SoulSpaceResearch/1.0"}
        http.get.side_effect = [
            Mock(status_code=200, text="User-agent: *\nAllow: /"),
            Mock(headers={"Content-Type": "text/html"}, content=b'<div class="comment"><p>A</p></div><a class="next" href="https://other.invalid/next">Next</a>'),
        ]
        source = {"name": "news", "type": "news", "start_urls": ["https://example.org/article"], "comment_selector": ".comment", "text_selector": "p", "next_selector": "a.next"}
        rows = website(http, source)
        self.assertEqual(next(rows)["free_text"], "A")
        with self.assertRaisesRegex(CrawlError, "origin"):
            next(rows)
        self.assertEqual(http.get.call_count, 2)

    def test_per_source_limit_counts_new_rows_and_continues_next_source(self):
        first = {"name": "youtube", "type": "youtube", "video_ids": ["abcdefghijk"], "max_comments": 1}
        second = {"name": "facebook", "type": "facebook", "post_ids": ["123_456"], "max_comments": 1}
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            config = {"sources": [first, second], "output": "comments.csv", "user_agent": "test"}
            (folder / "config.json").write_text(json.dumps(config), encoding="utf-8")
            existing = record(first, "old", "video", "Already saved")
            output = Output(folder / "comments.csv")
            output.write(existing)
            output.close()
            yt_rows = [existing, record(first, "new1", "video", "New"), record(first, "skip1", "video", "Over limit")]
            fb_rows = [record(second, "new2", "post", "New"), record(second, "skip2", "post", "Over limit")]
            with patch("crawl_comments.load_dotenv"), patch("crawl_comments.Path.cwd", return_value=folder), patch("sys.argv", ["crawl_comments.py", "--config", "config.json", "--limit", "10"]), patch("crawl_comments.Http"), patch("crawl_comments.youtube", return_value=iter(yt_rows)), patch("crawl_comments.facebook", return_value=iter(fb_rows)), redirect_stdout(io.StringIO()):
                self.assertEqual(main(), 0)
            with (folder / "comments.csv").open(encoding="utf-8", newline="") as stream:
                self.assertEqual([r["source_id"] for r in csv.DictReader(stream)], ["old", "new1", "new2"])

    def test_dotenv_loads_from_project_preserves_shell_and_literal_tokens(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / "other").mkdir()
            (folder / ".env").write_text("YOUTUBE_API_KEY='literal-${KEEP}#token'\nFACEBOOK_PAGE_ACCESS_TOKEN=file-token\nMETA_GRAPH_VERSION=v99.0\n", encoding="utf-8-sig")
            config = {"sources": [{"name": "yt", "type": "youtube", "video_ids": ["abcdefghijk"]}], "output": "comments.csv", "user_agent": "test"}
            (folder / "config.json").write_text(json.dumps(config), encoding="utf-8")
            stdout = io.StringIO()
            with patch.dict(os.environ, {"FACEBOOK_PAGE_ACCESS_TOKEN": "shell-token"}, clear=True), patch("crawl_comments.__file__", str(folder / "collection" / "crawl_comments.py")), patch("crawl_comments.Path.cwd", return_value=folder / "other"), patch("sys.argv", ["crawl_comments.py", "--config", "../config.json", "--validate-only"]), redirect_stdout(stdout):
                self.assertEqual(main(), 0)
                self.assertEqual(os.environ["YOUTUBE_API_KEY"], "literal-${KEEP}#token")
                self.assertEqual(os.environ["FACEBOOK_PAGE_ACCESS_TOKEN"], "shell-token")
                self.assertEqual(os.environ["META_GRAPH_VERSION"], "v99.0")
            self.assertNotIn("token", stdout.getvalue())
            self.assertFalse((folder / "comments.csv").exists())


if __name__ == "__main__":
    unittest.main()
