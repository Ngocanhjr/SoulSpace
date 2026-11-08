"""Offline source-list checks; no browser/API requests."""

from pathlib import Path
import tempfile
import unittest

from crawl_comments import CrawlError, read_source_urls, youtube_ids_from_source
from crawl_facebook_public import facebook_url


class SourceLinksTests(unittest.TestCase):
    def test_mixed_numbered_markdown_links_are_filtered_and_deduplicated(self):
        text = """## Youtube
1. https://www.youtube.com/watch?v=abcdefghijk
2. [Video](https://youtu.be/abcdefghijk)
## Facebook
1. [https://www.facebook.com/share/p/example/](https://www.facebook.com/share/p/example/)
2. https://www.facebook.com/share/p/another/
3. https://www.facebook.com.attacker.invalid/share/p/example/
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "SOURCE.MD"
            path.write_text(text, encoding="utf-8-sig")
            urls = read_source_urls(path, {"www.facebook.com"})
            self.assertEqual([facebook_url(url) for url in urls], ["https://www.facebook.com/share/p/example/", "https://www.facebook.com/share/p/another/"])
            self.assertEqual(youtube_ids_from_source(path), ["abcdefghijk"])

    def test_invalid_youtube_id_is_not_silently_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "SOURCE.MD"
            path.write_text("https://www.youtube.com/watch?v=short", encoding="utf-8")
            with self.assertRaises(CrawlError):
                youtube_ids_from_source(path)

    def test_plain_url_list_still_works(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "posts.txt"
            path.write_text("https://www.facebook.com/share/p/example/\n", encoding="utf-8")
            self.assertEqual(read_source_urls(path, {"www.facebook.com"}), ["https://www.facebook.com/share/p/example/"])

    def test_no_youtube_links_reports_missing_platform(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "SOURCE.MD"
            path.write_text("## Youtube\n## Facebook\nhttps://www.facebook.com/share/p/example/", encoding="utf-8")
            with self.assertRaisesRegex(CrawlError, "No YouTube"):
                youtube_ids_from_source(path)


if __name__ == "__main__":
    unittest.main()
