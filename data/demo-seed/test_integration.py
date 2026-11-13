"""Tự tạo PostgreSQL 17 tạm, chỉ dùng trong bài kiểm tra này, không dùng .env dự án."""
import concurrent.futures
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import unittest
import uuid

import psycopg
from psycopg import sql

import generate


ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]


class SeedIntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = "soulspace-demo-seed-test-" + uuid.uuid4().hex[:10]
        cls.password = secrets.token_urlsafe(24)
        cls.data = generate.build_dataset()
        subprocess.run([
            "docker", "run", "--rm", "-d", "--name", cls.container,
            "--label", "purpose=soulspace-demo-seed-test",
            "--tmpfs", "/var/lib/postgresql/data",
            "-e", "POSTGRES_HOST_AUTH_METHOD=trust",
            "-p", "127.0.0.1::5432", "postgres:17.11-bookworm",
        ], check=True, capture_output=True)
        try:
            info = json.loads(subprocess.check_output(["docker", "inspect", cls.container]))[0]
            port = info["NetworkSettings"]["Ports"]["5432/tcp"][0]["HostPort"]
            cls.admin = f"host=127.0.0.1 port={port} dbname=postgres user=postgres"
            for _ in range(60):
                try:
                    with psycopg.connect(cls.admin, autocommit=True) as conn:
                        conn.execute("SELECT 1")
                    break
                except psycopg.OperationalError:
                    time.sleep(0.5)
            else:
                raise RuntimeError("PostgreSQL tạm chưa sẵn sàng")
            with psycopg.connect(cls.admin, autocommit=True) as conn:
                conn.execute("CREATE ROLE soulspace_runtime LOGIN")
                conn.execute("CREATE ROLE soulspace_migrator LOGIN")
            spec = importlib.util.spec_from_file_location("demo_seed", ROOT / "seed.py")
            cls.seed_module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(cls.seed_module)
        except BaseException:
            cls.tearDownClass()
            raise

    @classmethod
    def tearDownClass(cls):
        subprocess.run(["docker", "rm", "-f", cls.container], capture_output=True, check=False)

    def setUp(self):
        self.database = "demo_" + uuid.uuid4().hex[:12]
        with psycopg.connect(self.admin, autocommit=True) as conn:
            conn.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(self.database)))
        self.dsn = self.admin.replace("dbname=postgres", "dbname=" + self.database)
        with psycopg.connect(self.dsn, autocommit=True) as conn:
            conn.execute("ALTER SCHEMA public OWNER TO soulspace_migrator")
            conn.execute("GRANT USAGE ON SCHEMA public TO soulspace_runtime")
            conn.execute("SET ROLE soulspace_migrator")
            for file in sorted((REPO / "soulspace/src/main/resources/db/migration").glob("V[1-5]__*.sql")):
                with conn.transaction():
                    conn.execute(file.read_text(encoding="utf-8"))
        self.runtime = self.dsn.replace("user=postgres", "user=soulspace_runtime")

    def seed(self, phase=3, verify=False):
        with psycopg.connect(self.runtime, autocommit=True) as conn:
            return self.seed_module.run(conn, self.data, self.password, phase=phase,
                                        verify_only=verify, all_phases=phase == 3)

    def test_all_then_rerun_preserves_ids_hashes_and_counts(self):
        result = self.seed(3)
        self.assertEqual(result["phase"], 3)
        print("\nSeed counts:", result["counts"])
        with psycopg.connect(self.runtime) as conn:
            before = conn.execute("SELECT id,password_hash FROM users ORDER BY id").fetchall()
            notices = conn.execute("SELECT id FROM notifications ORDER BY id").fetchall()
        self.assertEqual(self.seed(3)["status"], "already-seeded")
        self.assertEqual(self.seed(3, verify=True)["status"], "verified")
        with psycopg.connect(self.runtime) as conn:
            self.assertEqual(before, conn.execute("SELECT id,password_hash FROM users ORDER BY id").fetchall())
            self.assertEqual(notices, conn.execute("SELECT id FROM notifications ORDER BY id").fetchall())

    def test_phases_resume_and_order(self):
        with self.assertRaises(ValueError):
            self.seed(verify=True)
        with self.assertRaises(ValueError):
            self.seed(2)
        for phase in (1, 2, 3):
            self.assertEqual(self.seed(phase)["phase"], phase)
            self.assertEqual(self.seed(phase)["status"], "already-seeded")

    def test_changed_content_or_password_is_not_overwritten(self):
        self.seed()
        with psycopg.connect(self.runtime, autocommit=True) as conn:
            with self.assertRaises(ValueError):
                self.seed_module.run(conn, self.data, self.password + "different")
            blog_id = self.data["blogs"][0]["id"]
            conn.execute("UPDATE blogs SET content='Nội dung nhóm đã sửa' WHERE id=%s", (blog_id,))
        with self.assertRaises(ValueError):
            self.seed()
        with psycopg.connect(self.runtime) as conn:
            self.assertEqual(conn.execute("SELECT content FROM blogs WHERE id=%s", (blog_id,)).fetchone()[0],
                             "Nội dung nhóm đã sửa")

    def test_existing_user_is_preserved_and_seed_refused(self):
        with psycopg.connect(self.runtime, autocommit=True) as conn:
            conn.execute("INSERT INTO users(id,username,fullname) VALUES (%s,'someoneelse','Dữ liệu có sẵn')", (uuid.uuid4(),))
        with self.assertRaises(ValueError):
            self.seed()
        with psycopg.connect(self.runtime) as conn:
            self.assertEqual(conn.execute("SELECT username FROM users").fetchall(), [("someoneelse",)])
            self.assertEqual(conn.execute("SELECT count(*) FROM blogs").fetchone()[0], 0)

    def test_failure_in_last_phase_rolls_back_everything(self):
        with psycopg.connect(self.dsn, autocommit=True) as conn:
            conn.execute("""CREATE FUNCTION seed_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected seed failure'; END; $$;
                CREATE TRIGGER zz_seed_test_fail BEFORE INSERT ON blog_shares
                FOR EACH ROW EXECUTE FUNCTION seed_test_fail();""")
        with self.assertRaises(psycopg.Error) as error:
            self.seed()
        self.assertEqual(error.exception.sqlstate, "P0001")
        with psycopg.connect(self.runtime) as conn:
            for table in self.seed_module.TABLES:
                self.assertEqual(conn.execute(sql.SQL("SELECT count(*) FROM public.{}").format(sql.Identifier(table))).fetchone()[0], 0)

    def test_two_seeders_do_not_duplicate(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: self.seed(), range(2)))
        self.assertEqual(sorted(r["status"] for r in results), ["already-seeded", "seeded"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
