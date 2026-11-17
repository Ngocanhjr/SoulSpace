"""Kiểm tra dataset bằng thư viện chuẩn. Integration dùng PostgreSQL demo riêng."""
from pathlib import Path
from collections import Counter
import tempfile
import unittest

import generate
import demo01_posts


ROOT = Path(__file__).resolve().parent


class DatasetTests(unittest.TestCase):
    def test_usernames_are_natural_and_keep_user_order(self):
        users = generate.build_dataset()["users"]
        self.assertTrue(all(not user["username"].startswith("demo") for user in users[:40]))
        self.assertEqual(users[0]["username"], "minhkhoi")
        self.assertEqual(users[39]["username"], "vinhan")
        self.assertEqual(users[40]["username"], "demo41")
        self.assertEqual([user["id"] for user in users], sorted(user["id"] for user in users))

    def test_profile_extension_uses_user_identity_after_username_change(self):
        data = generate.build_dataset()
        original = demo01_posts.build_posts(data)
        data["users"][0]["username"] = "changedusername"
        posts = demo01_posts.build_posts(data)
        self.assertEqual({post["author_id"] for post in posts}, {data["users"][0]["id"]})
        self.assertEqual(posts, original)

    def test_both_profile_extensions_have_separate_ids_and_content(self):
        data = generate.build_dataset()
        first = demo01_posts.build_posts(data)
        extra = demo01_posts.build_posts(data, "demo41")
        self.assertEqual(len(first), 1284)
        self.assertEqual(len(extra), 1300)
        blogs = data["blogs"] + first + extra
        self.assertEqual(len({post["id"] for post in blogs}), len(blogs))
        self.assertEqual(len({post["content"] for post in blogs}), len(blogs))
        for user in (data["users"][0], data["users"][-1]):
            counts = Counter(post["published"] for post in blogs if post["author_id"] == user["id"])
            self.assertEqual(counts, {"true": 1000, "false": 300})

    def test_dataset_and_relationships(self):
        data = generate.build_dataset()
        self.assertEqual({name: len(rows) for name, rows in data.items()}, {
            "users": 41, "categories": 16, "blogs": 400,
            "blog_categories": 640, "comments": 700,
            "blog_likes": 1000, "comment_likes": 200, "blog_shares": 150,
        })
        generate.validate(data)
        with tempfile.TemporaryDirectory() as directory:
            generate.write_dataset(Path(directory), data)
            for name in data:
                self.assertEqual((ROOT / f"{name}.csv").read_bytes(),
                                 (Path(directory) / f"{name}.csv").read_bytes())

    def test_comment_cannot_be_its_own_parent(self):
        data = generate.build_dataset()
        comment = data["comments"][0]
        comment["parent_id"] = comment["id"]
        with self.assertRaisesRegex(ValueError, "Parent"):
            generate.validate(data)


if __name__ == "__main__":
    unittest.main()
