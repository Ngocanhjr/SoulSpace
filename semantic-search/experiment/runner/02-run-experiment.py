"""Bước 02 — Chạy tìm kiếm keyword, semantic hoặc cả hai trên bộ đã chốt.

Đầu vào: corpus.csv, queries.csv, ground-truth-final.csv và dataset-info.json
trong ../data/. Script dùng chung dữ liệu này và kiểm tra hash trước/sau lượt chạy.
Đầu ra: ../results/keyword-search/<ma-luot>/ và/hoặc
../results/semantic-search/<ma-luot>/, mỗi phương pháp có kết quả riêng.
results.csv kèm câu truy vấn, nội dung bài và nhãn chốt để đọc trực tiếp.
Chạy từ gốc repository, không cần DB/backend nếu chỉ chọn keyword:
    python semantic-search/experiment/runner/02-run-experiment.py --method keyword
Semantic/both: chạy bước 01 --check trước; cần backend, Jina và cookie đăng nhập.
Tiếp theo: 03-evaluate.py với đường dẫn từng thư mục lượt chạy đã hoàn tất.
"""

import argparse
import getpass
import json
import math
import os
import random
import time
import unicodedata
import warnings
from datetime import datetime, timezone
from http.client import HTTPException
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

from experiment_data import file_hash, read_csv, write_csv
from experiment_data import load_dataset as read_dataset

BASE = Path(__file__).resolve().parent.parent / "data"
RESULTS = Path(__file__).resolve().parent.parent / "results"


def normalize(text):
    return " ".join(unicodedata.normalize("NFC", text).lower().split())


def keyword_search(query, corpus):
    tokens = set(normalize(query).split())
    ranked = []
    for post in corpus:
        content = normalize(post["content"])
        score = sum(token in content for token in tokens)
        if score:
            ranked.append({"id": post["blog_id"], "score": score})
    return sorted(ranked, key=lambda row: (-row["score"], row["id"]))[:10]


def load_dataset():
    return read_dataset(BASE)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def get_json(base_url, path, cookie):
    request = Request(base_url + path, headers={"Accept": "application/json", "Cookie": cookie})
    try:
        with build_opener(NoRedirect()).open(request, timeout=90) as response:
            return json.load(response)
    except HTTPError as error:
        raise ValueError(f"Backend trả HTTP {error.code}; kiểm tra backend và phiên đăng nhập.") from None
    except (URLError, TimeoutError, OSError, HTTPException):
        raise ValueError("Không kết nối được backend hoặc yêu cầu hết thời gian.") from None
    except (UnicodeError, json.JSONDecodeError):
        raise ValueError("Backend không trả JSON hợp lệ.") from None


def check_public_feed(data, posts):
    if not isinstance(data, list):
        raise ValueError("Feed không phải danh sách.")
    current = {}
    for row in data:
        if not isinstance(row, dict) or not isinstance(row.get("content"), str):
            raise ValueError("Feed thiếu nội dung văn bản.")
        if row["content"].strip():
            blog_id = row.get("id")
            if not isinstance(blog_id, str) or blog_id in current:
                raise ValueError("Feed có ID không hợp lệ hoặc bị trùng.")
            current[blog_id] = {"content": row["content"]}
    if any(bid not in current or current[bid]["content"] != post["content"] for bid, post in posts.items()):
        raise ValueError("Bài trong corpus bị thiếu hoặc đổi nội dung trên backend.")
    return current


def semantic_results(data, posts, public_posts=None):
    if not isinstance(data, list) or len(data) > 50:
        raise ValueError("Kết quả ngữ nghĩa không đúng danh sách tối đa 50 ứng viên.")
    public_posts = posts if public_posts is None else public_posts
    seen, results = set(), []
    for row in data:
        if not isinstance(row, dict):
            raise ValueError("Bài trả về không hợp lệ.")
        blog_id = row.get("id")
        score = row.get("similarity")
        if not isinstance(blog_id, str) or blog_id not in public_posts or blog_id in seen:
            raise ValueError("Kết quả có ID trùng hoặc ngoài feed đã kiểm tra.")
        if row.get("content") != public_posts[blog_id]["content"]:
            raise ValueError("Nội dung bài trả về đã khác snapshot.")
        if type(score) not in (int, float) or not math.isfinite(score):
            raise ValueError("Điểm tương đồng không hợp lệ.")
        if results and score > results[-1]["score"]:
            raise ValueError("Ứng viên ngữ nghĩa không theo điểm giảm dần.")
        seen.add(blog_id)
        results.append({"id": blog_id, "score": score})
    scoped = [row for row in results if row["id"] in posts]
    # A full candidate page with fewer than ten targets cannot prove their top 10.
    if len(data) == 50 and len(scoped) < 10:
        raise ValueError("50 ứng viên chưa đủ xác định top 10 trong corpus; cần truy vấn DB giới hạn đúng tập bài.")
    return scoped[:10]


def run_method(args, output, stamp):
    input_hashes = {name: file_hash(BASE / name) for name in
                    ("corpus.csv", "queries.csv", "ground-truth-final.csv", "dataset-info.json")}
    corpus, queries, labels, digest = load_dataset()
    posts = {post["blog_id"]: post for post in corpus}
    run_semantic = args.method == "semantic"
    cookie = ""
    base_url = args.base_url.rstrip("/")
    if run_semantic:
        url = urlsplit(base_url)
        if (url.scheme not in ("http", "https") or not url.hostname or url.username or url.password
                or url.path or url.query or url.fragment
                or (url.scheme == "http" and url.hostname not in ("localhost", "127.0.0.1", "::1"))):
            raise ValueError("Backend cần URL gốc localhost/127.0.0.1 qua HTTP, hoặc HTTPS.")
        token = os.environ.get("SOULSPACE_SESSION")
        if not token:
            try:
                with warnings.catch_warnings():
                    warnings.simplefilter("error", getpass.GetPassWarning)
                    token = getpass.getpass("Giá trị cookie SOULSPACESESSION (ẩn): ")
            except (getpass.GetPassWarning, EOFError):
                raise ValueError("Terminal không hỗ trợ nhập ẩn; cấu hình SOULSPACE_SESSION rồi chạy lại.") from None
        if not token or any(char in token for char in "\r\n;"):
            raise ValueError("Chỉ nhập giá trị cookie phiên, không nhập cả header Cookie.")
        cookie = "SOULSPACESESSION=" + token
        get_json(base_url, "/api/v1/auth/me", cookie)
        public_posts = check_public_feed(get_json(base_url, "/api/v1/soulspaces", cookie), posts)
    output.mkdir(parents=True, exist_ok=False)
    results, timings = [], []
    failure = None
    completed = False
    try:
        if args.method == "keyword":
            for query in queries:
                for rank, result in enumerate(keyword_search(query["query"], corpus), 1):
                    results.append(dict(query_id=query["query_id"], method="keyword", rank=rank,
                                        blog_id=result["id"], score=result["score"]))
        if run_semantic:
            for repeat in range(args.repeats):
                order = list(queries)
                random.Random(42 + repeat).shuffle(order)
                for query in order:
                    start = time.perf_counter()
                    try:
                        data = get_json(base_url, "/api/v1/search?" + urlencode({"q": query["query"], "limit": 50}), cookie)
                        elapsed = (time.perf_counter() - start) * 1000
                        ranked = semantic_results(data, posts, public_posts)
                    except ValueError as error:
                        timings.append(dict(query_id=query["query_id"], repeat=repeat + 1,
                                            elapsed_ms=round((time.perf_counter() - start) * 1000, 3),
                                            status="error", error=str(error)))
                        raise
                    timings.append(dict(query_id=query["query_id"], repeat=repeat + 1,
                                        elapsed_ms=round(elapsed, 3), status="ok", error=""))
                    if repeat == 0:
                        for rank, result in enumerate(ranked, 1):
                            results.append(dict(query_id=query["query_id"], method="semantic", rank=rank,
                                                blog_id=result["id"], score=result["score"]))
                    print(f"{query['query_id']} lượt {repeat + 1}: {len(ranked)} bài, {elapsed:.0f} ms")
            if check_public_feed(get_json(base_url, "/api/v1/soulspaces", cookie), posts) != public_posts:
                raise ValueError("Feed công khai thay đổi trong khi chạy; không dùng lượt này để đánh giá.")
        if any(file_hash(BASE / name) != expected for name, expected in input_hashes.items()):
            raise ValueError("Dữ liệu trong data/ thay đổi trong khi chạy; không dùng lượt này để đánh giá.")
        completed = True
    except (ValueError, OSError, KeyError, TypeError, KeyboardInterrupt) as error:
        failure = str(error) or "Lượt chạy bị ngắt."
    finally:
        query_lookup = {query["query_id"]: query for query in queries}
        label_lookup = {(row["query_id"], row["blog_id"]): row["label"] for row in labels}
        readable_results = [dict(row, group=query_lookup[row["query_id"]]["group"],
                                 query=query_lookup[row["query_id"]]["query"],
                                 content=posts[row["blog_id"]]["content"],
                                 label=label_lookup[row["query_id"], row["blog_id"]]) for row in results]
        write_csv(output / "results.csv", ("group", "query_id", "query", "method", "rank",
                                           "blog_id", "content", "score", "label"), readable_results)
        write_csv(output / "timings.csv", ("query_id", "repeat", "elapsed_ms", "status", "error"), timings)
        metadata = {"created_at_utc": stamp, "method": args.method, "complete": completed,
                    "schema_version": 2, "corpus_count": len(corpus), "query_count": len(queries),
                    "corpus_fingerprint": digest, "repeats": args.repeats if run_semantic else 1,
                    "queries_sha256": input_hashes["queries.csv"],
                    "ground_truth_sha256": input_hashes["ground-truth-final.csv"],
                    "results_sha256": file_hash(output / "results.csv"),
                    "timings_sha256": file_hash(output / "timings.csv"),
                    "semantic_candidate_limit": 50 if run_semantic else None,
                    "public_post_count": len(public_posts) if run_semantic else None,
                    "base_url": base_url if run_semantic else None,
                    "shuffle_seed": 42, "quality_run": "repeat 1 only",
                    "error": failure or (None if completed else "Lượt chạy bị ngắt trước khi xác minh hoàn tất.")}
        (output / "run-info.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if failure:
        raise ValueError(f"Lượt chạy chưa hoàn tất: {failure} Kết quả một phần ở {output}; chưa dùng để tính chỉ số.")
    print(f"Đã lưu kết quả ở {output}; chạy 03-evaluate.py để tính bốn metrics bằng dữ liệu trong data/.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--method", choices=("keyword", "semantic", "both"), default="keyword")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--output", type=Path, help="Thư mục gốc kết quả; mặc định experiment/results, luôn tách hai phương pháp")
    args = parser.parse_args()
    if args.repeats < 1:
        raise ValueError("repeats phải >= 1.")
    root = args.output or RESULTS
    for folder in ("keyword-search", "semantic-search"):
        (root / folder).mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    methods = ("keyword", "semantic") if args.method == "both" else (args.method,)
    for method in methods:
        method_args = argparse.Namespace(**vars(args))
        method_args.method = method
        run_method(method_args, root / f"{method}-search" / stamp, stamp)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        raise SystemExit(str(error)) from None
