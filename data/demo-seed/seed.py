"""Seed demo theo yêu cầu tường minh, chỉ ghi vào database demo sạch hoặc đúng bộ seed."""
import argparse
import csv
from datetime import date, datetime, timezone
import json
import os
from pathlib import Path
import sys
import unicodedata
from urllib.parse import parse_qsl, urlsplit, urlunsplit
import uuid

import generate
import demo01_posts


ROOT = Path(__file__).resolve().parent
PHASES = (
    ("users", "categories", "blogs", "blog_categories"),
    ("comments",),
    ("blog_likes", "comment_likes", "blog_shares"),
)
EMPTY_TABLES = ("external_identities", "media_assets", "blog_images", "media_cleanup_jobs")
TABLES = tuple(name for group in PHASES for name in group) + EMPTY_TABLES + ("notifications",)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def load_dataset():
    expected = generate.build_dataset()
    for table, rows in expected.items():
        with (ROOT / f"{table}.csv").open(encoding="utf-8", newline="") as stream:
            reader = csv.DictReader(stream)
            require(reader.fieldnames == list(rows[0]), f"Header CSV không khớp: {table}")
            require(list(reader) == rows, f"CSV khác nguồn cố định: {table}. Chạy generate.py rồi kiểm tra thay đổi.")
    return expected


def canonical(value):
    if value is None:
        return ""
    if isinstance(value, bool):
        return str(value).lower()
    if isinstance(value, datetime):
        return generate.stamp(value.astimezone(timezone.utc))
    if isinstance(value, date):
        return value.isoformat()
    return str(value)


def expected_notifications(data, phase):
    blogs = {r["id"]: r for r in data["blogs"]}
    comments = {r["id"]: r for r in data["comments"]}
    result = set()

    def add(recipient, actor, blog, comment, kind):
        if recipient and recipient != actor:
            result.add((recipient, actor, blog, comment, kind))

    if phase >= 2:
        for c in comments.values():
            owner = blogs[c["blog_id"]]["author_id"]
            add(owner, c["author_id"], c["blog_id"], c["id"], "BLOG_COMMENT")
            if c["parent_id"]:
                parent_author = comments[c["parent_id"]]["author_id"]
                if parent_author != owner:
                    add(parent_author, c["author_id"], c["blog_id"], c["id"], "COMMENT_REPLY")
    if phase >= 3:
        for table, kind in (("blog_likes", "BLOG_LIKE"), ("blog_shares", "BLOG_SHARE")):
            for r in data[table]:
                add(blogs[r["blog_id"]]["author_id"], r["user_id"], r["blog_id"], "", kind)
        for r in data["comment_likes"]:
            c = comments[r["comment_id"]]
            add(c["author_id"], r["user_id"], c["blog_id"], c["id"], "COMMENT_LIKE")
    return result


def check_state(conn, data, hasher, password, extra_posts):
    from psycopg import sql
    from argon2.exceptions import VerificationError, InvalidHashError

    counts = {name: conn.execute(sql.SQL("SELECT count(*) FROM public.{}").format(sql.Identifier(name))).fetchone()[0]
              for name in TABLES}
    phase = 0
    if counts["users"]:
        phase = 1
    if counts["comments"]:
        phase = 2
    if any(counts[name] for name in PHASES[2]):
        phase = 3
    for stage, tables in enumerate(PHASES, 1):
        for name in tables:
            expected = data[name] if stage <= phase else []
            if name == "blogs" and counts[name] == len(expected) + len(extra_posts) and phase:
                expected = expected + extra_posts
            require(counts[name] == len(expected),
                    f"Database có dữ liệu khác hoặc seed dở dang ở {name}. Dùng database/branch demo riêng.")
            columns = list(data[name][0])
            rows = conn.execute(sql.SQL("SELECT {} FROM public.{}").format(
                sql.SQL(", ").join(map(sql.Identifier, columns)), sql.Identifier(name))).fetchall()
            actual = {tuple(map(canonical, row)) for row in rows}
            wanted = {tuple(row[col] for col in columns) for row in expected}
            require(actual == wanted, f"Dữ liệu {name} đã khác bộ demo. Dừng, giữ nguyên dữ liệu hiện có.")
    for name in EMPTY_TABLES:
        require(counts[name] == 0, f"{name} có dữ liệu ngoài bộ demo. Dùng database/branch demo riêng.")
    if phase:
        for user, hash_value, avatar in conn.execute("SELECT username,password_hash,avatar_media_id FROM public.users"):
            require(avatar is None, "Avatar đã thay đổi. Dừng seed để giữ dữ liệu hiện có.")
            try:
                require(hash_value and hash_value.startswith("$argon2id$"), f"Hash không hợp lệ cho {user}")
                hasher.verify(hash_value, password)
            except (VerificationError, InvalidHashError):
                raise ValueError("DEMO_USER_PASSWORD không khớp dữ liệu hiện có. Seed không đổi mật khẩu.") from None
    check_counters(conn)
    notices = conn.execute("SELECT recipient_id,actor_id,blog_id,comment_id,type,id,created_at FROM public.notifications").fetchall()
    keys = {tuple(map(canonical, row[:5])) for row in notices}
    require(keys == expected_notifications(data, phase), "Thông báo không khớp các tương tác demo.")
    require(len(keys) == len(notices), "Thông báo trùng")
    for row in notices:
        require(uuid.UUID(str(row[5])).version == 7, "DEFAULT notifications.id không tạo UUID v7")
        require(row[6] <= datetime.now(timezone.utc), "Thông báo ở tương lai")
    return phase, counts


def check_counters(conn):
    require(conn.execute("""
        SELECT NOT EXISTS (
            SELECT FROM public.blogs b WHERE
            b.likes_count <> (SELECT count(*) FROM public.blog_likes l WHERE l.blog_id=b.id)
            OR b.comments_count <> (SELECT count(*) FROM public.comments c WHERE c.blog_id=b.id AND c.deleted_at IS NULL)
            OR b.shares_count <> (SELECT count(*) FROM public.blog_shares s WHERE s.blog_id=b.id)
        ) AND NOT EXISTS (
            SELECT FROM public.comments c WHERE c.likes_count <>
                (SELECT count(*) FROM public.comment_likes l WHERE l.comment_id=c.id)
            OR c.deleted_at IS NOT NULL OR c.version <> 0
        )
    """).fetchone()[0], "Counter hoặc trạng thái bình luận khác bộ seed. Dừng, không tự sửa dữ liệu.")


def schema_check(conn):
    require(conn.info.server_version // 10000 == 17, "Cần PostgreSQL major 17, cùng môi trường dự án.")
    tables = {row[0] for row in conn.execute("SELECT tablename FROM pg_tables WHERE schemaname='public'")}
    require(tables - {"flyway_schema_history"} == set(TABLES),
            "Schema khác V1–V5 hiện tại. Chạy Flyway trên database demo riêng trước.")
    required = {
        "comments_validate", "blog_likes_counter", "comment_likes_counter", "blog_shares_counter",
        "blog_likes_immutable", "comment_likes_immutable", "blog_shares_immutable", "comments_counter",
        "comment_like_active", "deleted_comment_no_likes", "image_blog_lock", "blogs_nonempty",
        "blog_images_nonempty", "media_identity_immutable", "blog_likes_notify", "comment_likes_notify",
        "blog_shares_notify", "comments_notify",
    }
    enabled = {row[0] for row in conn.execute("""
        SELECT t.tgname FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
        JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='public' AND NOT t.tgisinternal AND t.tgenabled='O'
    """)}
    require(required <= enabled, "Thiếu trigger đang bật của V3. Seed không tắt hoặc sửa trigger.")
    require(conn.execute("SELECT to_regclass('public.v_public_blog_feed') IS NOT NULL").fetchone()[0], "Chưa có V5")
    require(conn.execute("SHOW session_replication_role").fetchone()[0] == "origin", "Trigger phải chạy ở chế độ origin")


def insert_rows(conn, table, rows):
    from psycopg import sql

    columns = list(rows[0])
    command = sql.SQL("INSERT INTO public.{} ({}) VALUES ({})").format(
        sql.Identifier(table), sql.SQL(", ").join(map(sql.Identifier, columns)),
        sql.SQL(", ").join(sql.Placeholder() for _ in columns))
    with conn.cursor() as cur:
        cur.executemany(command, [tuple(None if row[c] == "" else row[c] for c in columns) for row in rows])


def run(conn, data, password, phase=3, verify_only=False, all_phases=True, extend_demo01=False):
    from argon2 import PasswordHasher
    from psycopg import sql

    require(conn.autocommit, "Seed cần kết nối autocommit để tự quản lý một transaction hoàn chỉnh.")
    require(phase in (1, 2, 3), "Đợt phải là 1, 2 hoặc 3")
    require(not extend_demo01 or (all_phases and phase == 3), "--extend-demo01 đi cùng --phase all.")
    require(15 <= len(password) <= 128 and not any(unicodedata.category(ch) == "Cc" for ch in password),
            "DEMO_USER_PASSWORD cần 15–128 ký tự Unicode, không chứa ký tự điều khiển.")
    require(datetime.now(timezone.utc) >= generate.END, "Dataset chỉ dùng từ 01/11/2026 trở đi.")
    generate.validate(data)
    extra_posts = demo01_posts.build_posts(data)
    hasher = PasswordHasher(time_cost=2, memory_cost=19456, parallelism=1, salt_len=16, hash_len=32)
    # Salt sinh riêng từng tài khoản, làm trước khi giữ khóa database.
    hashes = [hasher.hash(password) for _ in data["users"]] if not verify_only else []
    with conn.transaction():
        if verify_only:
            conn.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
        conn.execute("SELECT set_config('lock_timeout','30s',true)")
        conn.execute("SELECT set_config('statement_timeout','120s',true)")
        conn.execute("SET LOCAL search_path = public, pg_temp")
        if not verify_only:
            conn.execute("SELECT pg_advisory_xact_lock(736857001)")
            # Khóa các nguồn ghi trước khi kiểm tra. V4 không cấp runtime quyền khóa mạnh notifications;
            # mọi trigger tạo thông báo đều bắt đầu từ các bảng nguồn đã khóa.
            conn.execute(sql.SQL("LOCK TABLE {} IN SHARE ROW EXCLUSIVE MODE").format(
                sql.SQL(", ").join(sql.Identifier("public", name) for name in sorted(set(TABLES)-{"notifications"}))))
        schema_check(conn)
        current, counts = check_state(conn, data, hasher, password, extra_posts)
        extended = counts["blogs"] == len(data["blogs"]) + len(extra_posts)
        if verify_only:
            require(current >= phase, "Database chưa đủ đợt yêu cầu. Nạp đợt còn thiếu trước khi xác nhận.")
            require(not extend_demo01 or extended, "Database chưa có bộ bài mở rộng cho demo01.")
            return {"status": "verified", "phase": current, "counts": counts}
        if current >= phase and (not extend_demo01 or extended):
            return {"status": "already-seeded", "phase": current, "counts": counts}
        require(current == phase-1 or all_phases,
                "Chạy đợt trước trước khi nạp đợt này, hoặc dùng --phase all.")
        # --phase all đi từ trạng thái hiện có tới đợt 3 trong cùng transaction.
        for stage in range(current+1, phase+1):
            for table in PHASES[stage-1]:
                rows = data[table]
                if table == "users":
                    rows = [dict(row, password_hash=value) for row, value in zip(rows, hashes)]
                insert_rows(conn, table, rows)
        if extend_demo01 and not extended:
            insert_rows(conn, "blogs", extra_posts)
        conn.execute("SET CONSTRAINTS ALL IMMEDIATE")
        final, counts = check_state(conn, data, hasher, password, extra_posts)
        require(final == phase, "Seed chưa đạt đợt yêu cầu")
    return {"status": "seeded", "phase": final, "counts": counts}


def connection_settings(env):
    from psycopg.conninfo import make_conninfo

    url = env.get("DB_URL", "").removeprefix("jdbc:")
    parts = urlsplit(url)
    require(parts.scheme in ("postgres", "postgresql") and parts.hostname, "DB_URL phải là PostgreSQL URL hợp lệ.")
    require(not parts.username and not parts.password, "Đặt tài khoản trong DB_USER/DB_PASSWORD, tách khỏi URL.")
    options = dict(parse_qsl(parts.query, keep_blank_values=True))
    ssl_factory = options.pop("sslfactory", None)
    require(ssl_factory in (None, "org.postgresql.ssl.DefaultJavaSSLFactory"), "sslfactory JDBC chưa hỗ trợ")
    if "channelBinding" in options:
        options["channel_binding"] = options.pop("channelBinding")
    if parts.hostname not in ("localhost", "127.0.0.1", "::1"):
        require(options.get("sslmode") == "verify-full", "Kết nối từ xa cần sslmode=verify-full.")
        require(options.get("channel_binding") == "require", "Kết nối từ xa cần channel_binding=require.")
    base = urlunsplit((parts.scheme, parts.netloc, parts.path, "", ""))
    require(env.get("DB_USER") and env.get("DB_PASSWORD"), "Thiếu DB_USER hoặc DB_PASSWORD")
    return make_conninfo(base, user=env["DB_USER"], password=env["DB_PASSWORD"],
                         connect_timeout=15, application_name="soulspace-demo-seed", **options)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="Chỉ kiểm CSV, không kết nối database")
    mode.add_argument("--verify", action="store_true", help="Đối soát database, không thêm dữ liệu")
    mode.add_argument("--apply", action="store_true", help="Nạp vào database demo đã xác nhận")
    parser.add_argument("--target-database", help="Tên database demo phải khớp kết nối")
    parser.add_argument("--env-file", type=Path, help="File KEY=value theo mẫu dự án, env đang có được ưu tiên")
    parser.add_argument("--phase", choices=("1", "2", "3", "all"), default="all")
    parser.add_argument("--extend-demo01", action="store_true", help="Bổ sung demo01 tới 1.000 bài public và 300 bài private")
    args = parser.parse_args()
    data = load_dataset()
    if args.check:
        counts = {k: len(v) for k,v in data.items()}
        if args.extend_demo01:
            counts["blogs"] += len(demo01_posts.build_posts(data))
        print(json.dumps({"status": "dataset-valid", "counts": counts}, ensure_ascii=False))
        return
    require(args.target_database, "Cần --target-database tên_database_demo để xác nhận đích.")
    env = dict(os.environ)
    if args.env_file:
        for line in args.env_file.read_text(encoding="utf-8-sig").splitlines():
            if line.strip() and not line.lstrip().startswith("#"):
                key, sep, value = line.partition("=")
                require(sep and key.strip(), "File môi trường cần dòng KEY=value")
                env.setdefault(key.strip(), value)
    password = env.get("DEMO_USER_PASSWORD", "")
    require(password, "Thiếu DEMO_USER_PASSWORD trong môi trường.")
    import psycopg
    with psycopg.connect(connection_settings(env), autocommit=True) as conn:
        require(conn.info.dbname == args.target_database, "Tên database kết nối khác --target-database. Dừng seed.")
        result = run(conn, data, password, phase=3 if args.phase == "all" else int(args.phase),
                     verify_only=args.verify, all_phases=args.phase == "all", extend_demo01=args.extend_demo01)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    try:
        main()
    except ValueError as error:
        print(f"DỪNG: {error}", file=sys.stderr)
        sys.exit(1)
    except Exception as error:
        # Thông báo từ PostgreSQL có thể chứa dữ liệu và giá trị đầu vào.
        code = getattr(error, "sqlstate", None)
        print(f"DỪNG: {type(error).__name__}, SQLSTATE={code or 'n/a'}. Giao dịch chưa xác nhận được rollback; nếu mất kết nối lúc COMMIT, chạy --verify trước khi thử lại.", file=sys.stderr)
        sys.exit(1)
