"""Bước 00 — Kiểm tra và chốt bộ nhãn đã được người/AI điền.

Chạy sau khi gắn đủ nhãn, trước bước 02. Không tự chấm nhãn, không gọi DB/Jina.
Đầu vào --input: CSV có query_id,blog_id và cột nhãn được chọn.
Mặc định đọc label; dùng --label-column final_label cho file tổng hợp đã review.
Nếu có query/content thì phải khớp.
Đầu ra: data/ground-truth-final.csv đúng 6 cột và metadata/hash đã cập nhật.
Ví dụ: python semantic-search/experiment/runner/00-finalize-ground-truth.py --input <file-da-cham.csv>
"""
import argparse
import json
from pathlib import Path
from experiment_data import file_hash, load_inputs, read_csv, validate_labels, write_csv

BASE = Path(__file__).resolve().parent.parent / "data"

def finalize(directory, source, label_column="label"):
    corpus, queries, info = load_inputs(directory)
    rows = read_csv(source, ("query_id", "blog_id", label_column))
    rows = [dict(row, label=row[label_column]) for row in rows]
    validate_labels(corpus, queries, rows)
    lookup = {(r["query_id"], r["blog_id"]): r["label"] for r in rows}
    canonical = [dict(group=q["group"], query_id=q["query_id"], query=q["query"],
                      blog_id=p["blog_id"], content=p["content"], label=lookup[q["query_id"], p["blog_id"]])
                 for q in queries for p in corpus]
    target = directory / "ground-truth-final.csv"
    temporary = directory / "ground-truth-final.csv.tmp"
    write_csv(temporary, ("group", "query_id", "query", "blog_id", "content", "label"), canonical)
    temporary.replace(target)
    info.update(ground_truth_status="finalized", ground_truth_sha256=file_hash(target))
    metadata = directory / "dataset-info.json.tmp"
    metadata.write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    metadata.replace(directory / "dataset-info.json")
    print(f"Đã chốt {len(canonical)} nhãn tại {target}.")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="CSV đã được gắn đủ nhãn 0/1/2")
    parser.add_argument("--label-column", default="label", help="Cột nhãn đã chốt: label hoặc final_label")
    args = parser.parse_args()
    finalize(BASE, args.input, args.label_column)

if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        raise SystemExit(str(error)) from None
