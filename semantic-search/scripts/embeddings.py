"""Backfill Jina v3 embeddings for public SoulSpace posts."""

import argparse
import hashlib
import json
import math
import os
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qsl, urlsplit, urlunsplit
from urllib.request import Request, urlopen

import psycopg


ENV_FILE = Path(__file__).resolve().parents[2] / ".env.neon"
DIMENSIONS = 1024
UPSERT = """
    INSERT INTO public.blog_embeddings AS e (blog_id, embedding, content_sha256)
    SELECT %s, %s::vector, %s
    FROM public.v_public_blog_feed AS p
    WHERE p.blog_id = %s AND p.content = %s
    ON CONFLICT (blog_id) DO UPDATE
    SET embedding = EXCLUDED.embedding, content_sha256 = EXCLUDED.content_sha256
    WHERE e.content_sha256 IS DISTINCT FROM EXCLUDED.content_sha256
"""


def database_config():
    settings = {}
    for line in ENV_FILE.read_text(encoding="utf-8-sig").splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            key, separator, value = line.partition("=")
            if not separator or not key.strip():
                raise ValueError(".env.neon cần dùng định dạng KEY=value.")
            settings[key.strip()] = value
    for key in ("DB_URL", "DB_USER", "DB_PASSWORD"):
        settings[key] = os.environ.get(key, settings.get(key))
        if not settings[key]:
            raise ValueError(f"Thiếu {key}.")

    url = urlsplit(settings["DB_URL"].removeprefix("jdbc:"))
    if url.scheme not in ("postgres", "postgresql") or not url.hostname or "@" in url.netloc:
        raise ValueError("DB_URL phải là URL PostgreSQL, tách riêng DB_USER và DB_PASSWORD.")
    options = dict(parse_qsl(url.query))
    options.pop("sslfactory", None)  # JDBC only.
    if "channelBinding" in options:
        options["channel_binding"] = options.pop("channelBinding")
    if options.get("sslmode") != "verify-full" or options.get("channel_binding") != "require":
        raise ValueError("DB_URL cần sslmode=verify-full và channelBinding=require.")
    api_key = os.environ.get("JINA_API_KEY", settings.get("JINA_API_KEY", "")).strip()
    if not api_key:
        raise ValueError("Thiếu JINA_API_KEY trong .env.neon hoặc môi trường.")

    url_without_options = urlunsplit((url.scheme, url.netloc, url.path, "", ""))
    options = {
        **options,
        "user": settings["DB_USER"],
        "password": settings["DB_PASSWORD"],
        "connect_timeout": 15,
    }
    return url_without_options, options, api_key


def embed_texts(api_key, texts, task):
    request = Request(
        "https://api.jina.ai/v1/embeddings",
        data=json.dumps({
            "model": "jina-embeddings-v3",
            "task": task,
            "dimensions": DIMENSIONS,
            "input": texts,
        }, ensure_ascii=False).encode("utf-8"),
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
    )
    try:
        with urlopen(request, timeout=60) as response:
            items = json.load(response)["data"]
    except HTTPError as error:
        raise ValueError(f"Jina trả HTTP {error.code}.") from None
    except (URLError, TimeoutError):
        raise ValueError("Không kết nối được Jina hoặc yêu cầu hết thời gian.") from None
    except (ValueError, KeyError, TypeError):
        raise ValueError("Jina trả phản hồi không hợp lệ.") from None

    vectors = {}
    for item in items if isinstance(items, list) else ():
        index = item.get("index") if isinstance(item, dict) else None
        vector = item.get("embedding") if isinstance(item, dict) else None
        if (type(index) is not int or not 0 <= index < len(texts) or index in vectors
                or not isinstance(vector, list) or len(vector) != DIMENSIONS
                or any(type(value) not in (int, float) or not math.isfinite(value) for value in vector)):
            raise ValueError("Jina trả chỉ số hoặc vector 1024 chiều không hợp lệ.")
        vectors[index] = json.dumps(vector, separators=(",", ":"))
    if len(vectors) != len(texts):
        raise ValueError("Jina trả thiếu vector.")
    return [vectors[index] for index in range(len(texts))]


def backfill(conn, api_key, batch_size, limit):
    rows = conn.execute("""
        SELECT p.blog_id, p.content, e.content_sha256
        FROM public.v_public_blog_feed AS p
        LEFT JOIN public.blog_embeddings AS e USING (blog_id)
        WHERE p.content IS NOT NULL
        ORDER BY p.blog_id
    """).fetchall()
    pending = []
    for blog_id, content, existing_hash in rows:
        if not content.strip():
            continue
        digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
        if digest != existing_hash:
            pending.append((blog_id, content, digest))
    if limit is not None:
        pending = pending[:limit]

    saved = 0
    for offset in range(0, len(pending), batch_size):
        batch = pending[offset:offset + batch_size]
        vectors = embed_texts(api_key, [content for _, content, _ in batch], "retrieval.passage")
        with conn.transaction(), conn.cursor() as cursor:
            cursor.executemany(UPSERT, [
                (blog_id, vector, digest, blog_id, content)
                for (blog_id, content, digest), vector in zip(batch, vectors)
            ])
            saved += cursor.rowcount
        print(f"Đã lưu {saved} vector ({offset + len(batch)}/{len(pending)} bài đã xử lý).")
    print(f"Hoàn tất: {saved} vector được lưu, {len(pending) - saved} bài thay đổi hoặc đã được xử lý.")


def search(conn, api_key, query):
    vector, = embed_texts(api_key, [query], "retrieval.query")
    results = conn.execute("""
        SELECT p.blog_id, p.username, p.content, e.embedding <=> %s::vector AS distance
        FROM public.blog_embeddings AS e
        JOIN public.v_public_blog_feed AS p USING (blog_id)
        WHERE e.content_sha256 = encode(sha256(convert_to(p.content, 'UTF8')), 'hex')
        ORDER BY distance, p.blog_id
        LIMIT 10
    """, (vector,)).fetchall()
    if not results:
        print("Không tìm thấy bài công khai có embedding khớp nội dung hiện tại.")
    for rank, (blog_id, username, content, distance) in enumerate(results, start=1):
        preview = " ".join(content.split())[:160]
        print(f"{rank}. @{username} | cosine similarity={1 - distance:.3f} | {blog_id}\n   {preview}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--limit", type=int, help="Giới hạn số bài để chạy thử.")
    parser.add_argument("--query", help="Tìm 10 bài công khai gần nghĩa.")
    args = parser.parse_args()
    if args.query is not None and not args.query.strip():
        parser.error("--query cần có nội dung.")
    if args.batch_size < 1 or (args.limit is not None and args.limit < 1):
        parser.error("--batch-size và --limit phải lớn hơn 0.")
    try:
        url, options, api_key = database_config()
        with psycopg.connect(url, **options, autocommit=True) as conn:
            if args.query is None:
                backfill(conn, api_key, args.batch_size, args.limit)
            else:
                search(conn, api_key, args.query.strip())
    except ValueError as error:
        print(f"DỪNG: {error}", file=sys.stderr)
        return 1
    except OSError:
        print("DỪNG: Không đọc được .env.neon.", file=sys.stderr)
        return 1
    except psycopg.Error as error:
        print(f"DỪNG: Lỗi PostgreSQL ({type(error).__name__}, SQLSTATE={error.sqlstate or 'n/a'}).",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    sys.exit(main())
