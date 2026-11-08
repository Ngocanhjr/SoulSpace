"""Offline HTML/mocked-flow checks. Never open Facebook or start a browser."""

import base64
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from crawl_comments import CrawlError, Output
from crawl_facebook_public import canonical_url, collect_post, facebook_url, numeric_id, parse_comments


POST = "https://www.facebook.com/example/posts/pfbidExample"


def comment(item_id, text, parent=""):
    query = f"comment_id={parent}&amp;reply_comment_id={item_id}" if parent else f"comment_id={item_id}"
    return f'<div role="article" aria-label="Comment by Example"><a href="https://www.facebook.com/example?{query}"><span dir="auto">AUTHOR_NOT_CONTENT</span></a><span lang="vi-VN">{text}</span><a href="{POST}?{query}">2h</a></div>'


class PublicFacebookTests(unittest.TestCase):
    def test_url_validation_and_tracking_removal(self):
        self.assertEqual(facebook_url("https://www.facebook.com/share/p/example/"), "https://www.facebook.com/share/p/example/")
        self.assertEqual(canonical_url(POST + "?rdid=tracking#section"), POST)
        for url in ("https://facebook.com.attacker.invalid/example/posts/1", "https://user:password@facebook.com/example/posts/1", "https://www.facebook.com/login/"):
            with self.assertRaises(CrawlError):
                facebook_url(url)

    def test_reads_numeric_and_encoded_comment_ids(self):
        value = base64.b64encode(b"comment:123456_987654").decode()
        self.assertEqual(numeric_id(value), "987654")
        self.assertEqual(numeric_id("987654"), "987654")
        self.assertEqual(numeric_id("not-a-comment-id"), "")

    def test_parser_keeps_emoji_and_excludes_author_and_controls(self):
        html = comment("101", 'Xin chào<img alt="😀" src="emoji.png"><br>Đoạn hai')
        row = list(parse_comments(html, POST, "Post title"))[0]
        self.assertEqual(row["free_text"], "Xin chào😀\nĐoạn hai")
        self.assertEqual(row["source_id"], "101")
        self.assertEqual(row["label_id"], "")
        self.assertEqual(row["published_at"], "")
        self.assertNotIn("AUTHOR_NOT_CONTENT", str(row))

    def test_nested_reply_does_not_contaminate_parent(self):
        reply = comment("202", "Nội dung trả lời", "101")
        html = comment("101", "Nội dung cha").replace("</div>", reply + "</div>")
        parent, child = list(parse_comments(html, POST, "Post title"))
        self.assertEqual(parent["free_text"], "Nội dung cha")
        self.assertEqual(parent["source_id"], "101")
        self.assertEqual(child["free_text"], "Nội dung trả lời")
        self.assertEqual(child["parent_id"], "101")
        self.assertIn("reply_comment_id=202", child["url"])

    def test_truncated_or_image_only_comments_are_skipped(self):
        html = comment("101", 'Nội dung bị cắt<span role="button">See more</span>') + comment("102", '<img src="photo.png" alt="">')
        self.assertEqual(list(parse_comments(html, POST, "Post title")), [])

    def test_loading_limit_counts_only_new_rows_and_never_opens_browser(self):
        page = Mock(url=POST)
        page.title.return_value = "Post title"
        old = comment("101", "Đã lưu")
        new = comment("102", "Mới")
        page.locator.return_value.evaluate_all.side_effect = [old, old + new]
        with tempfile.TemporaryDirectory() as directory:
            output = Output(Path(directory) / "comments.csv")
            try:
                output.write(list(parse_comments(old, POST, "Post title"))[0])
                with patch("crawl_facebook_public.prepare_page"), patch("crawl_facebook_public.expand_comments"):
                    added, reason = collect_post(page, POST, output, limit=1, max_rounds=5, delay=1)
                self.assertEqual(added, 1)
                self.assertIn("limit", reason)
                self.assertEqual(page.locator.return_value.evaluate_all.call_count, 2)
            finally:
                output.close()


if __name__ == "__main__":
    unittest.main()
