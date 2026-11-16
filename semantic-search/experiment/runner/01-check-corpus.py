"""Bước 01 — Kiểm tra corpus trên DB trước khi chạy tìm kiếm ngữ nghĩa.

Mục đích: xác nhận các bài trong ../data/corpus.csv có nội dung và embedding
khớp DB. Chỉ đọc DB; không gọi Jina. Chạy keyword đơn thuần thì bỏ qua bước này.
Đầu vào: dữ liệu trong ../data/ và cấu hình .env.neon ở gốc repository.
Kết quả: thông báo kiểm tra đạt hoặc lỗi; --check không ghi đè dữ liệu.
Chạy từ gốc repository bằng Python có psycopg:
    python semantic-search/experiment/runner/01-check-corpus.py --check
Tiếp theo: 02-run-experiment.py. Chế độ không có --check chỉ dùng để xuất
snapshot ban đầu khi chưa có corpus/metadata; không dùng với bộ đã chốt.
"""

import argparse
import csv
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import parse_qsl, urlsplit, urlunsplit

from experiment_data import load_dataset

BASE = Path(__file__).resolve().parent.parent / "data"
ROOT = BASE.parents[2]
SQL = """
SELECT p.blog_id::text, p.content, e.content_sha256
FROM public.v_public_blog_feed p
JOIN public.blog_embeddings e USING (blog_id)
WHERE p.content IS NOT NULL AND btrim(p.content) <> ''
  AND e.content_sha256 = encode(sha256(convert_to(p.content, 'UTF8')), 'hex')
ORDER BY p.blog_id
"""


def fingerprint(rows):
    pairs = sorted((row["blog_id"], row["content_sha256"]) for row in rows)
    return hashlib.sha256(json.dumps(pairs, separators=(",", ":")).encode()).hexdigest()


def check_snapshot(frozen, current):
    expected = {row["blog_id"]: row["content_sha256"] for row in frozen}
    eligible = {row["blog_id"]: row["content_sha256"] for row in current}
    if len(eligible) != len(current) or len(expected) != len(frozen):
        raise ValueError("Snapshot hoặc database trùng ID.")
    if any(eligible.get(bid) != digest for bid, digest in expected.items()):
        raise ValueError("Bài trong corpus bị thiếu, đổi nội dung hoặc có embedding thiếu/cũ trên DB.")
    return len(eligible.keys() - expected.keys())


def connect_config(env_file):
    settings = {}
    for line in env_file.read_text(encoding="utf-8-sig").splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            key, sep, value = line.partition("=")
            if sep:
                settings[key.strip()] = value.strip().strip("\"'")
    settings.update({key: os.environ[key] for key in ("DB_URL", "DB_USER", "DB_PASSWORD") if key in os.environ})
    if not all(settings.get(key) for key in ("DB_URL", "DB_USER", "DB_PASSWORD")):
        raise ValueError("Thiếu DB_URL, DB_USER hoặc DB_PASSWORD trong file env/môi trường.")
    url = urlsplit(settings["DB_URL"].removeprefix("jdbc:"))
    if url.scheme not in ("postgres", "postgresql") or not url.hostname or "@" in url.netloc:
        raise ValueError("DB_URL cần là URL PostgreSQL; tách riêng user/password.")
    options = dict(parse_qsl(url.query))
    options.pop("sslfactory", None)
    if "channelBinding" in options:
        options["channel_binding"] = options.pop("channelBinding")
    if options.get("sslmode") == "verify-full" and not options.get("sslrootcert") and not os.environ.get("PGSSLROOTCERT"):
        try:
            import certifi
        except ImportError:
            from pip._vendor import certifi
        options["sslrootcert"] = certifi.where()
    options.update(user=settings["DB_USER"], password=settings["DB_PASSWORD"], connect_timeout=15)
    return urlunsplit((url.scheme, url.netloc, url.path, "", "")), options


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env.neon")
    parser.add_argument("--check", action="store_true", help="Compare DB with snapshot; do not overwrite files")
    args = parser.parse_args()
    import psycopg

    corpus = BASE / "corpus.csv"
    info = BASE / "dataset-info.json"
    if not args.check and (corpus.exists() or info.exists()):
        raise ValueError("Snapshot đã tồn tại. Dùng --check; không ghi đè bộ đánh giá đã chốt.")
    frozen = load_dataset(BASE)[0] if args.check else None
    url, options = connect_config(args.env_file)
    try:
        with psycopg.connect(url, **options) as conn:
            conn.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY")
            total = conn.execute("SELECT count(*) FROM public.v_public_blog_feed WHERE content IS NOT NULL AND btrim(content) <> ''").fetchone()[0]
            rows = [dict(zip(("blog_id", "content", "content_sha256"), row)) for row in conn.execute(SQL).fetchall()]
    except psycopg.Error:
        raise ValueError("Không đọc được PostgreSQL; kiểm tra kết nối, TLS và quyền SELECT.") from None
    if not args.check and (not rows or len(rows) != total):
        raise ValueError(f"Có {len(rows)}/{total} bài đủ điều kiện; đồng bộ toàn bộ embedding trước khi chạy.")
    digest = fingerprint(rows)
    if args.check:
        extra = check_snapshot(frozen, rows)
        print(f"OK: đủ {len(frozen)} bài trong corpus có ID/hash và embedding khớp DB; {extra} bài đủ điều kiện ngoài corpus.")
        return
    with corpus.open("x", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=("blog_id", "content", "content_sha256"))
        writer.writeheader()
        writer.writerows(rows)
    metadata = {
        "exported_at_utc": datetime.now(timezone.utc).isoformat(),
        "source": "Neon PostgreSQL public.v_public_blog_feed + blog_embeddings",
        "public_text_posts": total, "eligible_posts": len(rows), "corpus_fingerprint": digest,
        "model_config_from_source": {"model": "jina-embeddings-v3", "dimensions": 1024,
                                     "query_task": "retrieval.query", "passage_task": "retrieval.passage"},
        "embedding_origin_note": "Cấu hình lấy từ source; không suy ra lịch sử tạo vector chỉ từ hash.",
    }
    info.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Đã xuất {len(rows)} bài công khai; không thay đổi DB và không gọi Jina.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, ImportError) as error:
        raise SystemExit(str(error)) from None
