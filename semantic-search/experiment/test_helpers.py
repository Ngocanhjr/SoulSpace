"""Frozen fixture with an unreturned relevant post and empty D/E rankings."""

import csv
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "runner"))


def read(path):
    with path.open(encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def write(path, rows, fields=None):
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields or list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def seal_run(path):
    corpus, queries = read(path / "corpus.csv"), read(path / "queries.csv")
    pairs = sorted((r["blog_id"], r["content_sha256"]) for r in corpus)
    metadata = dict(corpus_count=len(corpus), query_count=len(queries),
                    query_counts=dict(Counter(r["group"] for r in queries)),
                    corpus_fingerprint=hashlib.sha256(json.dumps(pairs, separators=(",", ":")).encode()).hexdigest(),
                    queries_sha256=hashlib.sha256((path / "queries.csv").read_bytes()).hexdigest(),
                    ground_truth_sha256=hashlib.sha256((path / "ground-truth-final.csv").read_bytes()).hexdigest())
    (path / "dataset-info.json").write_text(json.dumps(metadata), encoding="utf-8")
    info = dict(complete=True, method="keyword", repeats=1, **metadata)
    for name in ("results", "timings"):
        info[name + "_sha256"] = hashlib.sha256((path / (name + ".csv")).read_bytes()).hexdigest()
    (path / "run-info.json").write_text(json.dumps(info), encoding="utf-8")


def fixture(path):
    queries = [dict(query_id=qid, group=group, query="học", intent="Học chủ động")
               for qid, group in (("Q01", "A"), ("Q02", "D"), ("Q03", "E"))]
    corpus = [dict(blog_id=bid, content="học " + bid,
                   content_sha256=hashlib.sha256(("học " + bid).encode()).hexdigest()) for bid in "abcd"]
    write(path / "queries.csv", queries)
    write(path / "corpus.csv", corpus)
    labels = [dict(query_id=q["query_id"], blog_id=p["blog_id"], label=label)
              for q, values in zip(queries, ((0, 2, 1, 1), (0, 2, 0, 0), (0, 0, 0, 0)))
              for p, label in zip(corpus, values)]
    write(path / "ground-truth-final.csv", labels)
    write(path / "results.csv", [dict(query_id="Q01", method="keyword", rank=i, blog_id=bid, score=1)
                                 for i, bid in enumerate("abc", 1)])
    write(path / "timings.csv", [], ("query_id", "repeat", "elapsed_ms", "status", "error"))
    seal_run(path)
    return path
