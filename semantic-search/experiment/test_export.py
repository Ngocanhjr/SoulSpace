import importlib.util
import tempfile
import unittest
from pathlib import Path

from test_helpers import fixture, read

spec = importlib.util.spec_from_file_location("exporter", Path(__file__).resolve().parent / "runner" / "01-check-corpus.py")
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)


class ExportTests(unittest.TestCase):
    def test_database_check_accepts_extra_posts_outside_frozen_corpus(self):
        with tempfile.TemporaryDirectory() as directory:
            path = fixture(Path(directory))
            frozen = read(path / "corpus.csv")
            current = frozen + [dict(frozen[0], blog_id="external")]
            self.assertEqual(exporter.check_snapshot(frozen, current), 1)

    def test_database_check_rejects_missing_stale_and_duplicate_target_embeddings(self):
        with tempfile.TemporaryDirectory() as directory:
            path = fixture(Path(directory))
            frozen = read(path / "corpus.csv")
            for current in (frozen[1:], [dict(frozen[0], content_sha256="stale")] + frozen[1:], frozen + [frozen[0]]):
                with self.subTest(current=current), self.assertRaises(ValueError):
                    exporter.check_snapshot(frozen, current)


if __name__ == "__main__":
    unittest.main()
