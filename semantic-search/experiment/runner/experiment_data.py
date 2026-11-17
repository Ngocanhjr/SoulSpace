"""Thư viện chung: đọc dữ liệu, kiểm tra nhãn/hash và kiểm tra DB chỉ đọc.

Không chạy trực tiếp. load_inputs dùng trước khi có nhãn; load_dataset yêu cầu
ground truth đã chốt. database_snapshot chỉ kiểm tra đúng các ID trong corpus.
"""
import csv
import hashlib
import json
import os
from collections import Counter
from urllib.parse import parse_qsl, urlsplit, urlunsplit


def file_hash(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_csv(path, required=()):
    with path.open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        fields = reader.fieldnames or []
        if len(set(fields)) != len(fields) or not set(required) <= set(fields):
            raise ValueError(f"CSV thiếu cột hoặc trùng cột: {path.name}.")
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


def load_inputs(directory):
    corpus = read_csv(directory / "corpus.csv", ("blog_id", "content", "content_sha256"))
    queries = read_csv(directory / "queries.csv", ("query_id", "group", "query", "intent"))
    if (not corpus or not queries
            or len({p["blog_id"] for p in corpus}) != len(corpus)
            or len({q["query_id"] for q in queries}) != len(queries)):
        raise ValueError("Dataset trống hoặc trùng ID bài/truy vấn.")
    if any(not p["blog_id"].strip() or not p["content"].strip()
           or hashlib.sha256(p["content"].encode()).hexdigest() != p["content_sha256"] for p in corpus):
        raise ValueError("Nội dung corpus trống hoặc không khớp hash.")
    if any(q["group"] not in "ABCDE" or len(q["group"]) != 1
           or any(not q[k].strip() for k in ("query_id", "query", "intent")) for q in queries):
        raise ValueError("Truy vấn thiếu nội dung hoặc nhóm ngoài A–E.")
    info = json.loads((directory / "dataset-info.json").read_text(encoding="utf-8-sig"))
    expected = dict(corpus_count=len(corpus), query_count=len(queries),
                    query_counts=dict(Counter(q["group"] for q in queries)),
                    corpus_fingerprint=fingerprint(corpus),
                    queries_sha256=file_hash(directory / "queries.csv"))
    if "corpus_sha256" in info:
        expected["corpus_sha256"] = file_hash(directory / "corpus.csv")
    if any(info.get(key) != value for key, value in expected.items()):
        raise ValueError("Dataset không khớp số lượng/hash trong dataset-info.json.")
    return corpus, queries, info


def validate_labels(corpus, queries, labels):
    posts = {p["blog_id"]: p for p in corpus}
    lookup = {q["query_id"]: q for q in queries}
    seen = set()
    for row in labels:
        qid, bid = row["query_id"], row["blog_id"]
        if qid not in lookup or bid not in posts or (qid, bid) in seen:
            raise ValueError("Bảng nhãn có cặp trùng hoặc ID ngoài dataset.")
        if row["label"] not in ("0", "1", "2"):
            raise ValueError("Mọi label phải là 0, 1 hoặc 2; không để trống.")
        if any(k in row and row[k] != lookup[qid][k] for k in ("group", "query", "intent")):
            raise ValueError("Truy vấn trong bảng nhãn khác queries.csv.")
        if any(k in row and row[k] != posts[bid][k] for k in ("content", "content_sha256")):
            raise ValueError("Nội dung trong bảng nhãn khác corpus.csv.")
        seen.add((qid, bid))
    if len(seen) != len(corpus) * len(queries):
        raise ValueError(f"Thiếu nhãn toàn corpus: {len(seen)}/{len(corpus) * len(queries)} cặp.")


def load_dataset(directory, ground_truth_path=None):
    corpus, queries, info = load_inputs(directory)
    ground_truth_path = ground_truth_path or directory / "ground-truth-final.csv"
    if not ground_truth_path.is_file():
        raise ValueError("Chưa có nhãn chốt. Điền ground-truth-template.csv rồi chạy 00-finalize-ground-truth.py.")
    labels = read_csv(ground_truth_path, ("query_id", "blog_id", "label"))
    validate_labels(corpus, queries, labels)
    if info.get("ground_truth_sha256") != file_hash(ground_truth_path) or info.get("ground_truth_status") != "finalized":
        raise ValueError("Nhãn chưa khớp metadata; chạy 00-finalize-ground-truth.py trước khi thực nghiệm.")
    return corpus, queries, labels, info["corpus_fingerprint"]


def database_config(env_file, require_jina=False):
    settings = {}
    for line in env_file.read_text(encoding="utf-8-sig").splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            key, sep, value = line.partition("=")
            if not sep or not key.strip():
                raise ValueError("File env cần định dạng KEY=value.")
            value = value.strip()
            if len(value) >= 2 and value[0] in ("'", '"') and value[-1] == value[0]:
                value = value[1:-1]
            settings[key.strip()] = value
    for key in ("DB_URL", "DB_USER", "DB_PASSWORD", "JINA_API_KEY"):
        if key in os.environ:
            settings[key] = os.environ[key]
    if not all(settings.get(k) for k in ("DB_URL", "DB_USER", "DB_PASSWORD")):
        raise ValueError("Thiếu DB_URL, DB_USER hoặc DB_PASSWORD.")
    url = urlsplit(settings["DB_URL"].removeprefix("jdbc:"))
    if url.scheme not in ("postgres", "postgresql") or not url.hostname or "@" in url.netloc:
        raise ValueError("DB_URL phải là PostgreSQL URL, tách riêng user/password.")
    options = dict(parse_qsl(url.query))
    options.pop("sslfactory", None)
    if "channelBinding" in options:
        options["channel_binding"] = options.pop("channelBinding")
    if url.hostname.lower() not in ("localhost", "127.0.0.1", "::1"):
        if options.get("sslmode") != "verify-full" or options.get("channel_binding") != "require":
            raise ValueError("DB từ xa cần sslmode=verify-full và channelBinding=require.")
        if "sslrootcert" not in options:
            try:
                import certifi
            except ImportError:
                from pip._vendor import certifi
            options["sslrootcert"] = certifi.where()
    key = settings.get("JINA_API_KEY", "").strip()
    if require_jina and not key:
        raise ValueError("Thiếu JINA_API_KEY.")
    options.update(user=settings["DB_USER"], password=settings["DB_PASSWORD"], connect_timeout=15)
    return urlunsplit((url.scheme, url.netloc, url.path, "", "")), options, key


def database_snapshot(connection, corpus):
    rows = connection.execute("""
        SELECT p.blog_id::text, p.content, e.content_sha256, md5(e.embedding::text)
        FROM public.v_public_blog_feed p
        JOIN public.blog_embeddings e USING (blog_id)
        WHERE p.blog_id = ANY(%s::uuid[])
          AND e.content_sha256 = encode(sha256(convert_to(p.content, 'UTF8')), 'hex')
        ORDER BY p.blog_id
    """, ([p["blog_id"] for p in corpus],)).fetchall()
    actual = {bid: (content, digest, vector_hash) for bid, content, digest, vector_hash in rows}
    expected = {p["blog_id"]: p for p in corpus}
    if (len(rows) != len(actual) or set(actual) != set(expected)
            or any(actual[bid][:2] != (p["content"], p["content_sha256"]) for bid, p in expected.items())):
        raise ValueError("Corpus thiếu bài công khai, nội dung đã đổi hoặc embedding thiếu/cũ trên DB.")
    return actual
