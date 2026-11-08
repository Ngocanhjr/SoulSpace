"""Merge explicitly selected comment CSVs into a new file, without changing inputs."""

import argparse
import csv
from pathlib import Path
import sys
import tempfile


def merge_files(inputs, output):
    if len(inputs) < 2:
        raise ValueError("Select at least two input CSV files.")
    resolved = [path.resolve() for path in inputs]
    if len(set(resolved)) != len(resolved):
        raise ValueError("The same input file was supplied more than once.")
    if output.resolve() in resolved:
        raise ValueError("Output must be different from every input file.")
    if output.exists() or output.is_symlink():
        raise FileExistsError("Output already exists; choose another --output file.")
    for path in inputs:
        if not path.is_file() or path.suffix.lower() != ".csv":
            raise ValueError(f"Input must be an existing CSV file: {path}")
    output.parent.mkdir(parents=True, exist_ok=True)
    counts = []
    fields = None
    with tempfile.TemporaryDirectory(prefix=".merge-", dir=output.parent) as directory:
        staged = Path(directory) / "merged.csv"
        with staged.open("w", encoding="utf-8", newline="") as destination:
            for path in inputs:
                with path.open(encoding="utf-8-sig", newline="") as source:
                    reader = csv.DictReader(source, strict=True)
                    header = reader.fieldnames
                    if not header or "free_text" not in header or any(not name for name in header) or len(set(header)) != len(header):
                        raise ValueError(f"{path}: expected unique column names including free_text.")
                    if fields is None:
                        fields = header
                        writer = csv.DictWriter(destination, fieldnames=fields)
                        writer.writeheader()
                    elif set(header) != set(fields):
                        raise ValueError(f"{path}: columns do not match the first input file.")
                    count = 0
                    for row in reader:
                        if None in row or any(value is None for value in row.values()):
                            raise ValueError(f"{path}: malformed CSV row near line {reader.line_num}.")
                        writer.writerow(row)
                        count += 1
                    counts.append(count)
        # Publish the finished CSV; Windows rename refuses an existing destination.
        staged.rename(output)
    return counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", nargs="+", required=True, help="Selected CSV files, in merge order")
    parser.add_argument("--output", required=True, help="New output CSV file")
    args = parser.parse_args()
    inputs, output = [Path(value) for value in args.inputs], Path(args.output)
    if any(path.is_absolute() or path.drive for path in [*inputs, output]):
        parser.error("Use relative paths from ML/Comment classification.")
    if output.suffix.lower() != ".csv":
        parser.error("Output must have a .csv extension.")
    try:
        counts = merge_files(inputs, output)
    except (OSError, UnicodeError, csv.Error, ValueError) as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1
    for path, count in zip(inputs, counts):
        print(f"{path}: {count} rows")
    print(f"Merged {sum(counts)} rows from {len(inputs)} files into {output}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
