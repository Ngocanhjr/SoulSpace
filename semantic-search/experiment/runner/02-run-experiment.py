"""Bước 02 — Chạy keyword, semantic hoặc cả hai trên cùng corpus đã chốt nhãn.

Keyword là baseline đếm token theo chuỗi con. Semantic gọi Jina cho query rồi
xếp hạng cosine trực tiếp trên đúng các ID corpus trong PostgreSQL, trước LIMIT.
Không cần backend/cookie, không sửa DB và không tạo lại vector bài đăng.
Mỗi lượt tạo đúng một folder results/<method>-search_<timestamp>/.
Both lưu chung hai phương pháp; tiếp theo chạy 03-evaluate.py một lần cho folder đó.
"""
import argparse
import json
import math
import random
import sys
import time
import unicodedata
from contextlib import ExitStack
from datetime import datetime, timezone
from pathlib import Path

from experiment_data import database_config, database_snapshot, file_hash, load_dataset, write_csv

BASE = Path(__file__).resolve().parent.parent / "data"
ROOT = Path(__file__).resolve().parents[3]
RESULTS = BASE.parent / "results"
SEMANTIC_SQL = """
WITH scoped AS MATERIALIZED (
    SELECT p.blog_id, p.content, e.embedding
    FROM public.v_public_blog_feed p
    JOIN public.blog_embeddings e USING (blog_id)
    WHERE p.blog_id = ANY(%s::uuid[])
      AND e.content_sha256 = encode(sha256(convert_to(p.content, 'UTF8')), 'hex')
)
SELECT blog_id::text, content, 1 - (embedding <=> %s::vector) AS similarity
FROM scoped
ORDER BY similarity DESC, blog_id
LIMIT 10
"""


def normalize(text):
    return " ".join(unicodedata.normalize("NFC", text).lower().split())


def keyword_search(query, corpus):
    tokens = set(normalize(query).split())
    ranked = []
    for post in corpus:
        content = normalize(post["content"])
        score = sum(token in content for token in tokens)
        if score:
            ranked.append(dict(id=post["blog_id"], score=score))
    return sorted(ranked, key=lambda r: (-r["score"], r["id"]))[:10]


def embed_query(api_key, query):
    scripts = str(ROOT / "semantic-search" / "scripts")
    if scripts not in sys.path:
        sys.path.insert(0, scripts)
    from embeddings import embed_texts
    return embed_texts(api_key, [query], "retrieval.query")[0]


def open_semantic(env_file):
    import psycopg
    url, options, key = database_config(env_file, require_jina=True)
    try:
        connection = psycopg.connect(url, **options, autocommit=True)
    except psycopg.Error:
        raise ValueError("Không kết nối được PostgreSQL; kiểm tra Docker, file env và quyền SELECT.") from None
    return connection, key


def semantic_search(connection, query, api_key, posts):
    vector = embed_query(api_key, query)
    rows = connection.execute(SEMANTIC_SQL, (list(posts), vector)).fetchall()
    if len(rows) > 10:
        raise ValueError("Truy vấn semantic trả quá 10 bài.")
    results, seen = [], set()
    for bid, content, score in rows:
        bid = str(bid)
        if bid not in posts or bid in seen or content != posts[bid]["content"]:
            raise ValueError("Semantic trả bài ngoài corpus, trùng ID hoặc nội dung đã đổi.")
        if type(score) not in (int, float) or not math.isfinite(score):
            raise ValueError("Điểm cosine không hợp lệ.")
        if results and score > results[-1]["score"]:
            raise ValueError("Kết quả không theo điểm giảm dần.")
        seen.add(bid)
        results.append(dict(id=bid, score=score))
    return results


def error_message(error):
    return str(error) if isinstance(error, ValueError) else f"Lượt chạy gặp {type(error).__name__}; kiểm tra DB/mạng/cấu hình."


def run_experiment(args):
    if args.repeats < 1:
        raise ValueError("repeats phải >= 1.")
    corpus, queries, labels, digest = load_dataset(BASE)
    inputs = {name: file_hash(BASE / name) for name in
              ("corpus.csv", "queries.csv", "ground-truth-final.csv", "dataset-info.json")}
    methods = ("keyword", "semantic") if args.method == "both" else (args.method,)
    posts = {p["blog_id"]: p for p in corpus}
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output = (args.output or RESULTS) / f"{args.method}-search_{stamp}"
    with ExitStack() as stack:
        connection, api_key, before = None, None, None
        if "semantic" in methods:
            context, api_key = open_semantic(args.env_file)
            connection = stack.enter_context(context)
            try:
                connection.execute("SET default_transaction_read_only = on")
                before = database_snapshot(connection, corpus)
            except Exception as error:
                raise ValueError(error_message(error)) from None
        output.mkdir(parents=True, exist_ok=False)
        rankings, timings = [], []
        completed, failure = False, None
        try:
            for method in methods:
                repeats = args.repeats if method == "semantic" else 1
                for repeat in range(repeats):
                    order = list(queries)
                    if method == "semantic":
                        random.Random(42 + repeat).shuffle(order)
                    for query in order:
                        start = time.perf_counter()
                        try:
                            ranked = (keyword_search(query["query"], corpus) if method == "keyword"
                                      else semantic_search(connection, query["query"], api_key, posts))
                        except Exception as error:
                            if method == "semantic":
                                timings.append(dict(query_id=query["query_id"], repeat=repeat + 1,
                                                    elapsed_ms=round((time.perf_counter() - start) * 1000, 3),
                                                    status="error", error=error_message(error)))
                            raise ValueError(error_message(error)) from None
                        elapsed = (time.perf_counter() - start) * 1000
                        if method == "semantic":
                            timings.append(dict(query_id=query["query_id"], repeat=repeat + 1,
                                                elapsed_ms=round(elapsed, 3), status="ok", error=""))
                            print(f"{query['query_id']} lượt {repeat + 1}: {len(ranked)} bài, {elapsed:.0f} ms")
                        if repeat == 0:
                            rankings.extend(dict(query_id=query["query_id"], method=method, rank=rank,
                                                 blog_id=r["id"], score=r["score"])
                                            for rank, r in enumerate(ranked, 1))
            if connection is not None and database_snapshot(connection, corpus) != before:
                raise ValueError("Nội dung hoặc vector corpus thay đổi trong lúc chạy; không dùng lượt này.")
            if any(file_hash(BASE / name) != value for name, value in inputs.items()):
                raise ValueError("Dữ liệu trong data/ thay đổi trong lúc chạy; không dùng lượt này.")
            completed = True
        except (Exception, KeyboardInterrupt) as error:
            failure = error_message(error)
        finally:
            query_lookup = {q["query_id"]: q for q in queries}
            label_lookup = {(r["query_id"], r["blog_id"]): r["label"] for r in labels}
            readable = [dict(row, group=query_lookup[row["query_id"]]["group"],
                             query=query_lookup[row["query_id"]]["query"],
                             content=posts[row["blog_id"]]["content"],
                             label=label_lookup[row["query_id"], row["blog_id"]]) for row in rankings]
            write_csv(output / "results.csv", ("group", "query_id", "query", "method", "rank",
                                               "blog_id", "content", "score", "label"), readable)
            write_csv(output / "timings.csv", ("query_id", "repeat", "elapsed_ms", "status", "error"), timings)
            info = dict(created_at_utc=stamp, method=args.method, complete=completed, schema_version=3,
                        corpus_count=len(corpus), query_count=len(queries), corpus_fingerprint=digest,
                        repeats=args.repeats if "semantic" in methods else 1,
                        queries_sha256=inputs["queries.csv"],
                        ground_truth_sha256=inputs["ground-truth-final.csv"],
                        dataset_info_sha256=inputs["dataset-info.json"],
                        results_sha256=file_hash(output / "results.csv"),
                        timings_sha256=file_hash(output / "timings.csv"),
                        semantic_scope="Exact cosine on corpus IDs before LIMIT 10" if connection is not None else None,
                        model="jina-embeddings-v3" if connection is not None else None,
                        dimensions=1024 if connection is not None else None,
                        shuffle_seed=42, quality_run="repeat 1 only", error=failure)
            (output / "run-info.json").write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if failure:
            raise ValueError(f"Lượt chưa hoàn tất: {failure} Kết quả một phần ở {output}.")
    print(f"Đã lưu {output}; chạy 03-evaluate.py với thư mục này để tính metrics.")
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--method", choices=("keyword", "semantic", "both"), default="keyword")
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env.docker")
    parser.add_argument("--repeats", type=int, default=3, help="Số lượt semantic; keyword chạy một lượt.")
    parser.add_argument("--output", type=Path, default=RESULTS, help="Thư mục gốc kết quả, tạo một folder cho mỗi lượt.")
    run_experiment(parser.parse_args())


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, ImportError, KeyError) as error:
        raise SystemExit(str(error)) from None
