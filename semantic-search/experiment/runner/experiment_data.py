"""Thư viện dùng chung — không có thứ tự chạy và không chạy trực tiếp.

Các script 01/02/03 import file này để đọc/ghi CSV, tính hash, kiểm tra corpus,
queries, nhãn 0/1/2 và metadata. Hàm load_dataset xác nhận đủ các cặp nhãn và
nội dung khớp dữ liệu đã chốt; không tự sửa nhãn hoặc tạo bộ ground truth mới.
"""

import csv
import hashlib
import json
from collections import Counter


def file_hash(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_csv(path, required=()):
    with path.open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        fields = reader.fieldnames or []
        if len(set(fields)) != len(fields) or not set(required) <= set(fields):
            raise ValueError(f"CSV thiếu cột bắt buộc hoặc trùng cột: {path.name}.")
        rows = list(reader)
    if any(None in row or any(value is None for value in row.values()) for row in rows):
        raise ValueError(f"CSV có dòng sai số cột: {path.name}.")
    return rows


def write_csv(path, fields, rows):
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def fingerprint(corpus):
    pairs = sorted((row["blog_id"], row["content_sha256"]) for row in corpus)
    return hashlib.sha256(json.dumps(pairs, separators=(",", ":")).encode()).hexdigest()


def load_dataset(directory, ground_truth_path=None):
    corpus = read_csv(directory / "corpus.csv", ("blog_id", "content", "content_sha256"))
    queries = read_csv(directory / "queries.csv", ("query_id", "group", "query", "intent"))
    ground_truth_path = ground_truth_path or directory / "ground-truth-final.csv"
    labels = read_csv(ground_truth_path, ("query_id", "blog_id", "label"))
    posts = {row["blog_id"]: row for row in corpus}
    lookup = {row["query_id"]: row for row in queries}
    if not corpus or not queries or len(posts) != len(corpus) or len(lookup) != len(queries):
        raise ValueError("Dataset trống hoặc trùng ID bài/truy vấn.")
    if any(not row["blog_id"].strip() or not row["content"].strip()
           or hashlib.sha256(row["content"].encode()).hexdigest() != row["content_sha256"] for row in corpus):
        raise ValueError("Nội dung corpus trống hoặc không khớp hash.")
    if any(row["group"] not in tuple("ABCDE") or not row["query_id"].strip()
           or not row["query"].strip() or not row["intent"].strip() for row in queries):
        raise ValueError("Truy vấn thiếu nội dung hoặc có nhóm ngoài A–E.")
    seen = set()
    for row in labels:
        qid, bid = row["query_id"], row["blog_id"]
        if qid not in lookup or bid not in posts or (qid, bid) in seen:
            raise ValueError("Bảng nhãn có cặp trùng hoặc ID ngoài dataset.")
        if row["label"] not in ("0", "1", "2"):
            raise ValueError("Mọi nhãn label phải là 0, 1 hoặc 2; không để trống.")
        for field in ("group", "query", "intent"):
            if field in row and row[field] != lookup[qid][field]:
                raise ValueError("Truy vấn trong bảng nhãn khác queries.csv.")
        for field in ("content", "content_sha256"):
            if field in row and row[field] != posts[bid][field]:
                raise ValueError("Nội dung trong bảng nhãn khác corpus.csv.")
        seen.add((qid, bid))
    if len(seen) != len(corpus) * len(queries):
        raise ValueError(f"Thiếu nhãn toàn corpus: có {len(seen)}/{len(corpus) * len(queries)} cặp.")
    digest = fingerprint(corpus)
    info = json.loads((directory / "dataset-info.json").read_text(encoding="utf-8"))
    expected = dict(corpus_count=len(corpus), query_count=len(queries),
                    query_counts=dict(Counter(row["group"] for row in queries)),
                    corpus_fingerprint=digest, queries_sha256=file_hash(directory / "queries.csv"),
                    ground_truth_sha256=file_hash(ground_truth_path))
    if any(info.get(key) != value for key, value in expected.items()):
        raise ValueError("Dataset không khớp số lượng/hash trong dataset-info.json.")
    return corpus, queries, labels, digest
