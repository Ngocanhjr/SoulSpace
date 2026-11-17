"""Bước 01 — Đối chiếu ID/nội dung/embedding của corpus với PostgreSQL local.

Chỉ đọc DB, không cần ground truth, không gọi Jina và không xuất lại dữ liệu.
Chạy trước bước 02 nếu dùng semantic hoặc both; keyword có thể bỏ qua.
Ví dụ: python semantic-search/experiment/runner/01-check-corpus.py --env-file .env.docker
"""
import argparse
from pathlib import Path
from experiment_data import database_config, database_snapshot, load_inputs

BASE = Path(__file__).resolve().parent.parent / "data"
ROOT = BASE.parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env.docker")
    parser.add_argument("--check", action="store_true", help="Tương thích lệnh cũ; script luôn chỉ kiểm tra.")
    args = parser.parse_args()
    corpus, _, _ = load_inputs(BASE)
    url, options, _ = database_config(args.env_file)
    import psycopg
    try:
        with psycopg.connect(url, **options, autocommit=True) as connection:
            connection.execute("SET default_transaction_read_only = on")
            database_snapshot(connection, corpus)
    except psycopg.Error:
        raise ValueError("Không đọc được PostgreSQL; kiểm tra Docker, env, quyền và bảng embedding.") from None
    print(f"OK: đủ {len(corpus)} bài công khai, nội dung và embedding khớp corpus; không thay đổi DB.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, ImportError) as error:
        raise SystemExit(str(error)) from None
