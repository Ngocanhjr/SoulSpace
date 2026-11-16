"""Tests for choosing local/Neon embedding targets; no DB or Jina calls."""

import contextlib
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import embeddings


class EmbeddingConfigTests(unittest.TestCase):
    def env_file(self, directory, url):
        path = Path(directory) / ".env.test"
        path.write_text("\ufeff# test config\nDB_URL=" + url + "\nDB_USER=soulspace_runtime\n"
                        'DB_PASSWORD="test-password"\nJINA_API_KEY=\'test-key\'\n', encoding="utf-8")
        return path

    def test_loopback_postgres_does_not_require_neon_tls(self):
        for host in ("127.0.0.1", "localhost", "[::1]"):
            with self.subTest(host=host), tempfile.TemporaryDirectory() as directory:
                path = self.env_file(directory, f"jdbc:postgresql://{host}:5432/soulspace")
                with patch.dict("os.environ", {}, clear=True):
                    url, options, key = embeddings.database_config(path)
                self.assertEqual(url, f"postgresql://{host}:5432/soulspace")
                self.assertNotIn("sslmode", options)
                self.assertEqual(options["password"], "test-password")
                self.assertEqual(key, "test-key")

    def test_remote_database_still_requires_verified_tls(self):
        for suffix in ("", "?sslmode=disable", "?sslmode=require&channelBinding=require",
                       "?sslmode=verify-full"):
            with self.subTest(suffix=suffix), tempfile.TemporaryDirectory() as directory:
                path = self.env_file(directory, "jdbc:postgresql://db.example/soulspace" + suffix)
                with patch.dict("os.environ", {}, clear=True), self.assertRaisesRegex(ValueError, "verify-full"):
                    embeddings.database_config(path)

    def test_remote_neon_options_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.env_file(directory, "jdbc:postgresql://db.example/soulspace?sslmode=verify-full&channelBinding=require&sslfactory=jdbc-only")
            with patch.dict("os.environ", {}, clear=True):
                url, options, _ = embeddings.database_config(path)
            self.assertEqual(url, "postgresql://db.example/soulspace")
            self.assertEqual(options["sslmode"], "verify-full")
            self.assertEqual(options["channel_binding"], "require")
            self.assertNotIn("sslfactory", options)

    def test_environment_overrides_keep_existing_behavior(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.env_file(directory, "jdbc:postgresql://127.0.0.1:5432/soulspace")
            with patch.dict("os.environ", {"DB_PASSWORD": "environment-password", "JINA_API_KEY": "environment-key"}, clear=True):
                _, options, key = embeddings.database_config(path)
            self.assertEqual(options["password"], "environment-password")
            self.assertEqual(key, "environment-key")

    def test_password_quote_characters_are_preserved(self):
        for value, expected in (("abc'", "abc'"), ('"abc\'"', "abc'"), ("'abc\"'", 'abc"')):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as directory:
                path = self.env_file(directory, "jdbc:postgresql://127.0.0.1:5432/soulspace")
                text = path.read_text(encoding="utf-8").replace('DB_PASSWORD="test-password"', "DB_PASSWORD=" + value)
                path.write_text(text, encoding="utf-8")
                with patch.dict("os.environ", {}, clear=True):
                    _, options, _ = embeddings.database_config(path)
                self.assertEqual(options["password"], expected)

    def test_cli_uses_selected_config_and_keeps_passage_backfill(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.env_file(directory, "jdbc:postgresql://127.0.0.1:5432/soulspace")
            with patch.dict("os.environ", {}, clear=True), \
                    patch("sys.argv", ["embeddings.py", "--env-file", str(path), "--limit", "3"]), \
                    patch.object(embeddings.psycopg, "connect") as connect, \
                    patch.object(embeddings, "backfill") as backfill:
                self.assertEqual(embeddings.main(), 0)
            self.assertEqual(connect.call_args.args[0], "postgresql://127.0.0.1:5432/soulspace")
            self.assertEqual(backfill.call_args.args[1:], ("test-key", 16, 3))

    def test_query_mode_uses_selected_database(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.env_file(directory, "jdbc:postgresql://127.0.0.1:5432/soulspace")
            with patch.dict("os.environ", {}, clear=True), \
                    patch("sys.argv", ["embeddings.py", "--env-file", str(path), "--query", " học chủ động "]), \
                    patch.object(embeddings.psycopg, "connect"), \
                    patch.object(embeddings, "search") as search:
                self.assertEqual(embeddings.main(), 0)
            self.assertEqual(search.call_args.args[1:], ("test-key", "học chủ động"))

    def test_missing_config_reports_selected_filename_without_secrets(self):
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as directory, \
                patch("sys.argv", ["embeddings.py", "--env-file", str(Path(directory)/"missing.env")]), \
                contextlib.redirect_stderr(output):
            self.assertEqual(embeddings.main(), 1)
        self.assertIn("missing.env", output.getvalue())

    def test_backfill_only_embeds_missing_or_changed_nonblank_public_posts(self):
        import hashlib
        content = "đã có vector"
        valid_hash = hashlib.sha256(content.encode()).hexdigest()
        with patch.object(embeddings, "embed_texts", return_value=["[1]"]) as embed:
            from unittest.mock import MagicMock
            conn = MagicMock()
            conn.execute.return_value.fetchall.return_value = [
                ("valid", content, valid_hash), ("empty", " ", None),
                ("missing", "cần embedding", None), ("changed", "nội dung mới", "old-hash")]
            conn.cursor.return_value.__enter__.return_value.rowcount = 1
            with patch("builtins.print"):
                embeddings.backfill(conn, "test-key", 16, 1)
            embed.assert_called_once_with("test-key", ["cần embedding"], "retrieval.passage")


if __name__ == "__main__":
    unittest.main()
