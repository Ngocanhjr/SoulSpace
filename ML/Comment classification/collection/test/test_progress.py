"""Offline progress checks with a fake collector; never call external APIs."""

import io
import json
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import Mock, patch

from crawl_comments import Output, main, record


class ProgressTests(unittest.TestCase):
    def run_collector(self, interrupt=False):
        source = {"name": "yt", "type": "youtube", "video_ids": ["abcdefghijk"]}
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / "config.json").write_text(json.dumps({"sources": [source], "output": "comments.csv", "user_agent": "test"}), encoding="utf-8")
            old = record(source, "old", "video", "Existing comment")
            output = Output(folder / "comments.csv")
            output.write(old)
            output.close()

            def rows(*args):
                yield old
                for index in range(24):
                    yield record(source, str(index), "video", "New comment")
                if interrupt:
                    raise KeyboardInterrupt()
                yield record(source, "last", "video", "Last comment")

            stdout = io.StringIO()
            with patch("crawl_comments.load_dotenv"), patch("crawl_comments.Path.cwd", return_value=folder), patch("sys.argv", ["crawl_comments.py", "--config", "config.json", "--limit", "25"]), patch("crawl_comments.Http", return_value=Mock(count=7)), patch("crawl_comments.youtube", side_effect=rows), redirect_stdout(stdout):
                if interrupt:
                    with self.assertRaises(KeyboardInterrupt):
                        main()
                else:
                    self.assertEqual(main(), 0)
            return stdout.getvalue()

    def test_progress_counts_saved_rows_separately_from_duplicates(self):
        text = self.run_collector()
        self.assertIn("Existing IDs: 1", text)
        self.assertIn("Saved: 24/25 new | Duplicates skipped: 1 | Requests: 7", text)
        self.assertIn("Saved: 25/25 new", text)
        self.assertIn("Row limit reached", text)

    def test_interrupt_keeps_last_progress_summary(self):
        text = self.run_collector(interrupt=True)
        self.assertIn("Saved: 24/25 new | Duplicates skipped: 1 | Requests: 7", text)
        self.assertTrue(text.rstrip().endswith("Saved rows are in CSV. Labels remain empty."))


if __name__ == "__main__":
    unittest.main()
