"""Offline merge checks with synthetic CSVs; no collected data is changed."""

import csv
from pathlib import Path
import tempfile
import unittest

from merge_comments import merge_files


def write_csv(path, fields, rows):
    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


class MergeTests(unittest.TestCase):
    def test_merge_preserves_order_text_metadata_and_duplicate_comments(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            first, second, result = [folder / name for name in ("a.csv", "b.csv", "merged.csv")]
            row = {"source_id": "1", "free_text": 'Tiếng Việt 😀, "hay"\nxuống dòng', "label_id": "0"}
            write_csv(first, ["source_id", "free_text", "label_id"], [row])
            write_csv(second, ["free_text", "label_id", "source_id"], [row, {**row, "source_id": "2"}])
            originals = [path.read_bytes() for path in (first, second)]
            self.assertEqual(merge_files([first, second], result), [1, 2])
            with result.open(encoding="utf-8", newline="") as stream:
                self.assertEqual(list(csv.DictReader(stream)), [row, row, {**row, "source_id": "2"}])
            self.assertEqual([path.read_bytes() for path in (first, second)], originals)

    def test_mismatched_columns_do_not_publish_partial_output(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            first, second, result = [folder / name for name in ("a.csv", "b.csv", "merged.csv")]
            write_csv(first, ["free_text"], [{"free_text": "A"}])
            write_csv(second, ["free_text", "filter_reason"], [])
            with self.assertRaisesRegex(ValueError, "columns"):
                merge_files([first, second], result)
            self.assertFalse(result.exists())
            self.assertFalse(list(folder.glob(".merge-*")))

    def test_malformed_csv_does_not_publish_partial_output(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            first, second, result = [folder / name for name in ("a.csv", "b.csv", "merged.csv")]
            write_csv(first, ["source_id", "free_text"], [])
            second.write_text("source_id,free_text\n1\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "malformed"):
                merge_files([first, second], result)
            self.assertFalse(result.exists())

    def test_existing_output_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            first, second, result = [folder / name for name in ("a.csv", "b.csv", "merged.csv")]
            for path in (first, second):
                write_csv(path, ["free_text"], [])
            result.write_text("user data", encoding="utf-8")
            with self.assertRaises(FileExistsError):
                merge_files([first, second], result)
            self.assertEqual(result.read_text(encoding="utf-8"), "user data")

    def test_reject_duplicate_input_and_output_matching_input(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            first, second = folder / "a.csv", folder / "b.csv"
            for path in (first, second):
                write_csv(path, ["free_text"], [])
            with self.assertRaisesRegex(ValueError, "more than once"):
                merge_files([first, first], folder / "result.csv")
            with self.assertRaisesRegex(ValueError, "different"):
                merge_files([first, second], first)


if __name__ == "__main__":
    unittest.main()
