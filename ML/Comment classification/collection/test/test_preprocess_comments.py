"""Offline tests using synthetic comments and temporary CSVs."""

import csv
from pathlib import Path
import tempfile
import unittest
import unicodedata

from preprocess_comments import preprocess_file, removal_reason


class PreprocessTests(unittest.TestCase):
    def test_icon_only(self):
        for value in ("🥰", "❤️", "👍🏽", "👨‍👩‍👧‍👦", "🇻🇳", "🎉 ❤️\n😂", ":)))", ":D :v", "<3", "1️⃣", "★♫"):
            with self.subTest(value=value):
                self.assertEqual(removal_reason(value), "icon_only")

    def test_timestamp_only(self):
        for value in ("1:14:22", "3: 00", " 3 : 00 \n", "90:00", "00:59", "1:14:22\n3: 00"):
            with self.subTest(value=value):
                self.assertEqual(removal_reason(value), "timestamp_only")

    def test_time_references_without_additional_meaning(self):
        for value in ("xem đoạn 3:00", "Xem đoạn: 1:14:22.", "xem doan 3: 00", "XEM ĐOẠN 3:00!"):
            with self.subTest(value=value):
                self.assertEqual(removal_reason(value), "timestamp_reference")

    def test_preserve_meaningful_text_numbers_and_punctuation(self):
        for value in ("Hay quá ❤️", "😂 buồn cười", "xem đoạn 3:00, phần giải thích rất hay", "1:14:22 hay quá", "123", "1", "1:99", "1:00:99", "<3 bạn", "1️⃣ hay", "ừ", "ở", "ý", "à", "k", "K", "a", "I", "e", "ok", "ko"):
            with self.subTest(value=value):
                self.assertEqual(removal_reason(value), "")

    def test_punctuation_only(self):
        for value in (".", ",", "!!!", "…", "--", "\n. . "):
            with self.subTest(value=value):
                self.assertEqual(removal_reason(value), "punctuation_only")

    def test_single_letter_noise_even_with_punctuation_or_emoji(self):
        for value in ("h", " H ", "h...", "v", "D", "b", "h ❤️", "x ."):
            with self.subTest(value=value):
                self.assertEqual(removal_reason(value), "single_letter")

    def test_empty_and_invisible_only(self):
        for value in ("", " \t\n ", "\u200b"):
            with self.subTest(value=value):
                self.assertEqual(removal_reason(value), "empty")

    def test_name_only_is_case_insensitive_but_keeps_actual_opinions(self):
        for value in ("Trấn Thành", "quế vân", "  QUẾ   VÂN... ", "(Trấn Thành)"):
            with self.subTest(value=value):
                self.assertEqual(removal_reason(value), "name_only")
        for value in ("tôi thích Trấn Thành", "Quế Vân á", "Quế Vân ❤️", "Trấn Thành không hay", "Nam"):
            with self.subTest(value=value):
                self.assertEqual(removal_reason(value), "")

    def test_deduplicate_normalized_text_first_without_merging_near_matches_or_labels(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            source = folder / "comments.csv"
            texts = ["Rất hay", "  Rất   hay\n", unicodedata.normalize("NFD", "Rất hay"), "Rất hay á", "Rất hay", "Quế Vân", " Quế Vân "]
            rows = [{"source_id": str(i), "free_text": text, "label_id": "1" if i == 4 else "0"} for i, text in enumerate(texts)]
            with source.open("w", encoding="utf-8", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=["source_id", "free_text", "label_id"])
                writer.writeheader()
                writer.writerows(rows)
            counts = preprocess_file(source, folder / "processed")
            self.assertEqual(counts["kept"], 3)
            self.assertEqual(counts["duplicate"], 3)
            self.assertEqual(counts["name_only"], 1)
            with (folder / "processed/comments.clean.csv").open(encoding="utf-8", newline="") as stream:
                self.assertEqual([r["source_id"] for r in csv.DictReader(stream)], ["0", "3", "4"])
            with (folder / "processed/comments.removed.csv").open(encoding="utf-8", newline="") as stream:
                self.assertEqual([r["filter_reason"] for r in csv.DictReader(stream)], ["duplicate", "duplicate", "name_only", "duplicate"])

    def test_csv_outputs_preserve_raw_text_labels_and_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            source = folder / "comments.csv"
            rows = [{"source_id": "1", "free_text": "❤️", "label_id": ""}, {"source_id": "2", "free_text": "3: 00", "label_id": ""}, {"source_id": "3", "free_text": "Hay quá ❤️\nĐúng rồi", "label_id": "0"}, {"source_id": "4", "free_text": "xem đoạn 3:00", "label_id": ""}, {"source_id": "5", "free_text": "h", "label_id": ""}, {"source_id": "6", "free_text": ".", "label_id": ""}, {"source_id": "7", "free_text": "", "label_id": ""}]
            with source.open("w", encoding="utf-8-sig", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=["source_id", "free_text", "label_id"])
                writer.writeheader()
                writer.writerows(rows)
            original = source.read_bytes()
            counts = preprocess_file(source, folder / "processed")
            self.assertEqual(dict(counts), {"total": 7, "kept": 1, "duplicate": 0, "name_only": 0, "icon_only": 1, "timestamp_only": 1, "timestamp_reference": 1, "punctuation_only": 1, "single_letter": 1, "empty": 1})
            self.assertEqual(source.read_bytes(), original)
            with (folder / "processed/comments.clean.csv").open(encoding="utf-8", newline="") as stream:
                self.assertEqual(list(csv.DictReader(stream)), [rows[2]])
            with (folder / "processed/comments.removed.csv").open(encoding="utf-8", newline="") as stream:
                self.assertEqual([row["filter_reason"] for row in csv.DictReader(stream)], ["icon_only", "timestamp_only", "timestamp_reference", "single_letter", "punctuation_only", "empty"])
            with self.assertRaisesRegex(ValueError, "already exists"):
                preprocess_file(source, folder / "processed")


if __name__ == "__main__":
    unittest.main()
