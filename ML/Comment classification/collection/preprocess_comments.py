"""Filter low-information CSV comments; preserve original files and retained text."""

import argparse
from collections import Counter
import csv
from pathlib import Path
import re
import sys
import unicodedata


STAMP = r"[0-9]+\s*:\s*[0-5]?[0-9](?:\s*:\s*[0-5]?[0-9])?"
TIMESTAMPS = re.compile(rf"{STAMP}(?:\s+{STAMP})*")
TIME_REFERENCE = re.compile(rf"xem\s+(?:đoạn|doan)\s*:?\s*{STAMP}(?:\s+{STAMP})*\s*[.!…]*", re.I)
EMOTICONS = re.compile(r"(?:[:;=8][-^']?[)(DPpOo/\\|*vV3]+|[xX][-^']?[dD]+|<+/?3+)")
KEYCAPS = re.compile(r"[0-9#*]\ufe0f?\u20e3")
KEEP_SINGLE_LETTERS = {"a", "e", "i", "k", "o", "u"}
NAME_ONLY = {"trấn thành", "quế vân"}


def duplicate_key(row):
    text = " ".join(unicodedata.normalize("NFC", row["free_text"]).split())
    return text, row.get("label_id", "")


def removal_reason(value):
    text = "".join(c for c in unicodedata.normalize("NFKC", value) if unicodedata.category(c) != "Cf").strip()
    if not text:
        return "empty"
    if TIMESTAMPS.fullmatch(text):
        return "timestamp_only"
    if TIME_REFERENCE.fullmatch(text):
        return "timestamp_reference"
    name = " ".join(text.casefold().split()).strip(" .,!?:;…\"'“”‘’()[]{}")
    if name in NAME_ONLY:
        return "name_only"
    remainder, emoticons = EMOTICONS.subn("", text)
    remainder, keycaps = KEYCAPS.subn("", remainder)
    has_icon = bool(emoticons or keycaps or any(unicodedata.category(c).startswith("S") for c in remainder))
    if has_icon and not any(c.isalnum() for c in remainder):
        return "icon_only"
    if all(c.isspace() or unicodedata.category(c).startswith("P") for c in text):
        return "punctuation_only"
    letters = "".join(c for c in remainder if c.isalnum())
    if len(letters) == 1 and letters.isascii() and letters.isalpha() and letters.casefold() not in KEEP_SINGLE_LETTERS:
        return "single_letter"
    return ""


def preprocess_file(input_path, output_dir):
    clean_path = output_dir / f"{input_path.stem}.clean.csv"
    removed_path = output_dir / f"{input_path.stem}.removed.csv"
    if clean_path.exists() or removed_path.exists():
        raise ValueError("Output already exists; choose a new --output-dir to preserve it.")
    counts = Counter(total=0, kept=0, duplicate=0, name_only=0, icon_only=0, timestamp_only=0, timestamp_reference=0, punctuation_only=0, single_letter=0, empty=0)
    seen = set()
    with input_path.open(encoding="utf-8-sig", newline="") as original:
        reader = csv.DictReader(original, strict=True)
        fields = reader.fieldnames
        if not fields or "free_text" not in fields or len(set(fields)) != len(fields) or "filter_reason" in fields:
            raise ValueError("Expected unique CSV columns including free_text, without filter_reason.")
        output_dir.mkdir(parents=True, exist_ok=True)
        with clean_path.open("x", encoding="utf-8", newline="") as clean, removed_path.open("x", encoding="utf-8", newline="") as removed:
            clean_writer = csv.DictWriter(clean, fieldnames=fields)
            removed_writer = csv.DictWriter(removed, fieldnames=[*fields, "filter_reason"])
            clean_writer.writeheader()
            removed_writer.writeheader()
            for row in reader:
                if None in row or any(value is None for value in row.values()):
                    raise ValueError(f"Malformed CSV row near line {reader.line_num}; outputs may be partial.")
                counts["total"] += 1
                key = duplicate_key(row)
                reason = "duplicate" if key in seen else removal_reason(row["free_text"])
                seen.add(key)
                if reason:
                    removed_writer.writerow({**row, "filter_reason": reason})
                    counts[reason] += 1
                else:
                    clean_writer.writerow(row)
                    counts["kept"] += 1
    return counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", default="data/collected", help="Relative CSV file or directory")
    parser.add_argument("--output-dir", default="data/processed", help="Relative directory for new files")
    args = parser.parse_args()
    input_path, output_dir = Path(args.input), Path(args.output_dir)
    if any(p.is_absolute() or p.drive for p in (input_path, output_dir)):
        parser.error("Use relative paths from ML/Comment classification.")
    files = sorted(input_path.glob("*.csv")) if input_path.is_dir() else [input_path]
    if not files:
        parser.error("No CSV files found.")
    totals = Counter()
    failed = False
    for path in files:
        try:
            if path.suffix.lower() != ".csv" or not path.is_file():
                raise ValueError("Input must be an existing CSV file or directory of CSV files.")
            counts = preprocess_file(path, output_dir)
            totals.update(counts)
            details = " | ".join(f"{reason}={count}" for reason, count in counts.items())
            print(f"{path.name}: {details}", flush=True)
        except (OSError, UnicodeError, csv.Error, ValueError) as error:
            print(f"{path.name}: {error}", file=sys.stderr)
            failed = True
    print(f"Total: kept={totals['kept']} | removed={totals['total'] - totals['kept']}. Output: {output_dir}")
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
