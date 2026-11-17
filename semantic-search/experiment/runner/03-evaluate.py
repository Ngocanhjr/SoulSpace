"""Bước 03 — Tính Precision@5, Recall@5, MRR@10 và nDCG@10.

Chạy sau 02-run-experiment.py; chỉ nhận lượt có complete=true và hash hợp lệ.
Đầu vào: thư mục lượt chạy và dữ liệu/nhãn 0/1/2 dùng chung trong ../data/.
Hash dữ liệu phải khớp run-info.json; không cần sao chép dữ liệu vào kết quả.
Đầu ra: thư mục evaluation-<timestamp>/ bên trong lượt chạy, chứa metrics
từng query, tổng hợp A–E/ALL, thời gian semantic và báo cáo summary.md.
Ví dụ chạy từ gốc repository (thay <ma-luot> bằng thư mục thực tế):
    python semantic-search/experiment/runner/03-evaluate.py semantic-search/experiment/results/both-search_<ma-luot>
Both chỉ cần evaluate một lần; báo cáo gồm cả keyword và semantic.
Bước này chạy local, không cần DB/backend và không gọi Jina.
"""

import argparse
import json
import math
import statistics
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

from experiment_data import file_hash, load_dataset, read_csv, write_csv

BASE = Path(__file__).resolve().parent.parent / "data"


def rank_metrics(ranked, labels):
    grades = [labels[bid] for bid in ranked[:10]]
    total = sum(label >= 1 for label in labels.values())
    hits = sum(label >= 1 for label in grades[:5])
    first = next((rank for rank, label in enumerate(grades, 1) if label >= 1), None)
    dcg = sum((2 ** label - 1) / math.log2(rank + 1) for rank, label in enumerate(grades, 1))
    ideal = sorted(labels.values(), reverse=True)[:10]
    idcg = sum((2 ** label - 1) / math.log2(rank + 1) for rank, label in enumerate(ideal, 1))
    return dict(precision_at_5=hits / 5, recall_at_5=hits / total if total else None,
                reciprocal_rank_at_10=1 / first if first else 0.0,
                ndcg_at_10=dcg / idcg if idcg else None, relevant_total=total)


def latency_stats(rows):
    values = sorted(float(row["elapsed_ms"]) for row in rows if row["status"] == "ok")
    return dict(attempts=len(rows), successes=len(values),
                errors=sum(row["status"] == "error" for row in rows),
                p50_ms=statistics.median(values) if values else "",
                p95_ms=values[math.ceil(.95 * len(values)) - 1] if values else "")


def evaluate(run_dir, dataset_dir, ground_truth_path):
    info = json.loads((run_dir / "run-info.json").read_text(encoding="utf-8"))
    if info.get("complete") is not True:
        raise ValueError("Lượt chạy chưa hoàn tất; không xuất điểm từ kết quả một phần.")
    methods = {"keyword": ["keyword"], "semantic": ["semantic"], "both": ["keyword", "semantic"]}.get(info.get("method"))
    if not methods:
        raise ValueError("Phương pháp trong run-info.json không hợp lệ.")
    if info.get("dataset_info_sha256") != file_hash(dataset_dir / "dataset-info.json"):
        raise ValueError("Metadata dataset đã thay đổi sau lượt chạy.")
    for path, key in ((dataset_dir / "queries.csv", "queries_sha256"),
                      (ground_truth_path, "ground_truth_sha256"),
                      (run_dir / "results.csv", "results_sha256"),
                      (run_dir / "timings.csv", "timings_sha256")):
        if info.get(key) != file_hash(path):
            raise ValueError(f"Hash không khớp lượt chạy: {path.name}. Không dùng kết quả/nhãn đã thay đổi.")
    corpus, queries, labels, digest = load_dataset(dataset_dir, ground_truth_path)
    if digest != info.get("corpus_fingerprint"):
        raise ValueError("Corpus không khớp lượt chạy.")
    lookup = {row["query_id"]: row for row in queries}
    posts = {row["blog_id"]: row for row in corpus}
    labels_by_query = defaultdict(dict)
    for row in labels:
        labels_by_query[row["query_id"]][row["blog_id"]] = int(row["label"])

    rankings = defaultdict(dict)
    for row in read_csv(run_dir / "results.csv", ("query_id", "method", "rank", "blog_id", "score")):
        qid, method, bid = row["query_id"], row["method"], row["blog_id"]
        if qid not in lookup or method not in methods or bid not in posts:
            raise ValueError("Kết quả có truy vấn/phương pháp/bài ngoài dataset của lượt chạy.")
        rank, score = int(row["rank"]), float(row["score"])
        ranked = rankings[qid, method]
        if not 1 <= rank <= 10 or rank in ranked or bid in ranked.values() or not math.isfinite(score):
            raise ValueError("Thứ hạng/điểm không hợp lệ, trùng thứ hạng hoặc trùng bài.")
        ranked[rank] = bid
    for ranked in rankings.values():
        if sorted(ranked) != list(range(1, len(ranked) + 1)):
            raise ValueError("Thứ hạng phải liên tục từ 1, không được thiếu vị trí.")

    timings = read_csv(run_dir / "timings.csv", ("query_id", "repeat", "elapsed_ms", "status", "error"))
    seen = set()
    for row in timings:
        qid, repeat = row["query_id"], int(row["repeat"])
        elapsed = float(row["elapsed_ms"])
        if qid not in lookup or repeat < 1 or (qid, repeat) in seen:
            raise ValueError("Timing có truy vấn/lượt không hợp lệ hoặc trùng.")
        if row["status"] not in ("ok", "error") or not math.isfinite(elapsed) or elapsed < 0:
            raise ValueError("Timing có trạng thái hoặc thời gian không hợp lệ.")
        seen.add((qid, repeat))
    if "semantic" in methods:
        repeats = info.get("repeats")
        if type(repeats) is not int or repeats < 1:
            raise ValueError("Số lượt đo trong metadata không hợp lệ.")
        expected = {(q["query_id"], repeat) for q in queries for repeat in range(1, repeats + 1)}
        if seen != expected or any(row["status"] != "ok" for row in timings):
            raise ValueError("Timing thiếu lượt hoặc có lỗi nhưng metadata báo hoàn tất.")
    elif timings:
        raise ValueError("Lượt chỉ chạy từ khóa không được có timing ngữ nghĩa.")

    per_query = []
    for query in queries:
        qid = query["query_id"]
        for method in methods:
            ranked = rankings.get((qid, method), {})
            ordered = [ranked[rank] for rank in sorted(ranked)]
            per_query.append(dict(query_id=qid, group=query["group"], method=method,
                                  returned=len(ordered), **rank_metrics(ordered, labels_by_query[qid])))
    summary = []
    for method in methods:
        for group in ("A", "B", "C", "D", "E", "ALL"):
            rows = [r for r in per_query if r["method"] == method and (group == "ALL" or r["group"] == group)]
            if not rows:
                continue
            result = dict(method=method, group=group, queries=len(rows))
            for source, target in (("precision_at_5", "precision_at_5"), ("recall_at_5", "recall_at_5"),
                                   ("reciprocal_rank_at_10", "mrr_at_10"), ("ndcg_at_10", "ndcg_at_10")):
                values = [r[source] for r in rows if r[source] is not None]
                result[target] = statistics.mean(values) if values else None
            result["recall_valid_queries"] = sum(r["recall_at_5"] is not None for r in rows)
            result["ndcg_valid_queries"] = sum(r["ndcg_at_10"] is not None for r in rows)
            summary.append(result)
    latency = []
    for scope in ("A", "B", "C", "D", "E", "ALL"):
        rows = [r for r in timings if scope == "ALL" or lookup[r["query_id"]]["group"] == scope]
        if rows:
            latency.append(dict(scope=scope, **latency_stats(rows)))
    return per_query, summary, latency


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_dir", type=Path, help="Thư mục lượt chạy chứa results.csv, timings.csv và run-info.json")
    parser.add_argument("--dataset-dir", type=Path, help="Mặc định dùng chung experiment/data")
    parser.add_argument("--ground-truth", type=Path, help="Phải khớp hash ground truth đã chốt trước lượt chạy")
    args = parser.parse_args()
    dataset_dir = args.dataset_dir or BASE
    ground_truth = args.ground_truth or dataset_dir / "ground-truth-final.csv"
    per_query, summary, latency = evaluate(args.run_dir, dataset_dir, ground_truth)
    output = args.run_dir / ("evaluation-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ"))
    output.mkdir(exist_ok=False)
    write_csv(output / "metrics-per-query.csv",
              ("query_id", "group", "method", "returned", "precision_at_5", "recall_at_5",
               "reciprocal_rank_at_10", "ndcg_at_10", "relevant_total"), per_query)
    write_csv(output / "metrics-summary.csv",
              ("method", "group", "queries", "precision_at_5", "recall_at_5", "mrr_at_10",
               "ndcg_at_10", "recall_valid_queries", "ndcg_valid_queries"), summary)
    write_csv(output / "latency-summary.csv", ("scope", "attempts", "successes", "errors", "p50_ms", "p95_ms"), latency)
    report = [
        "# Kết quả đánh giá tìm kiếm", "",
        "Nhãn chốt: một cột label 0/1/2 trong ground-truth-final.csv.",
        "P@5, Recall@5, MRR@10: label >= 1 là liên quan. P@5 luôn chia 5.",
        "Recall dùng toàn corpus; nDCG dùng gain = 2^label - 1, IDCG lấy 10 nhãn cao nhất toàn corpus.",
        "Nhóm A–E đều được tính; ALL là trung bình không trọng số từng câu.",
        "Không có bài liên quan: Recall/nDCG = N/A (CSV để trống), P@5/RR@10 = 0. Ghi số câu hợp lệ riêng.", "",
        "| Phương pháp | Nhóm | Số câu | P@5 | Recall@5 | MRR@10 | nDCG@10 | Recall hợp lệ | nDCG hợp lệ |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in summary:
        values = ["N/A" if row[key] is None else f"{row[key]:.4f}"
                  for key in ("precision_at_5", "recall_at_5", "mrr_at_10", "ndcg_at_10")]
        report.append(f"| {row['method']} | {row['group']} | {row['queries']} | " + " | ".join(values)
                      + f" | {row['recall_valid_queries']} | {row['ndcg_valid_queries']} |")
    report += ["", "## Thời gian tìm kiếm ngữ nghĩa", "",
               "Đo Jina tạo vector query và truy vấn PostgreSQL; giới hạn corpus trước khi xếp hạng exact cosine Top 10.",
               "p50 là trung vị; p95 là vị trí ceil(0.95 × n) trong các lần thành công. Không so trực tiếp với baseline đọc CSV.", ""]
    for row in latency:
        report.append(f"- {row['scope']}: {row['successes']}/{row['attempts']} thành công, {row['errors']} lỗi; p50={row['p50_ms']} ms, p95={row['p95_ms']} ms.")
    if not latency:
        report.append("Lượt chạy này không có số đo ngữ nghĩa.")
    report += ["", "Bộ 175 bài thuộc Học tập/Công nghệ và 10 query là thực nghiệm quy mô nhỏ; chỉ kết luận trong phạm vi này.",
               "Nhãn do người dùng cung cấp/chốt; công cụ không tự xác nhận độ đúng của mọi nhãn.",
               "Baseline đếm token theo chuỗi con, không đại diện BM25 hoặc mọi hệ thống tìm kiếm từ khóa.", ""]
    (output / "summary.md").write_text("\n".join(report), encoding="utf-8")
    provenance = {key: file_hash(path) for key, path in (
        ("ground_truth_sha256", ground_truth), ("queries_sha256", dataset_dir / "queries.csv"),
        ("corpus_sha256", dataset_dir / "corpus.csv"), ("results_sha256", args.run_dir / "results.csv"),
        ("run_info_sha256", args.run_dir / "run-info.json"), ("timings_sha256", args.run_dir / "timings.csv"))}
    (output / "evaluation-info.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    print(f"Đã xuất bốn metrics tại {output}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError) as error:
        raise SystemExit(f"Không đánh giá: {error}") from None
