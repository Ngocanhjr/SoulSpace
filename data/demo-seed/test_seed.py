"""Kiểm tra dataset bằng thư viện chuẩn. Integration dùng PostgreSQL demo riêng."""
from pathlib import Path
import tempfile
import unittest

import generate
import demo01_posts


ROOT = Path(__file__).resolve().parent


class DatasetTests(unittest.TestCase):
    def test_usernames_are_natural_and_keep_user_order(self):
        users = generate.build_dataset()["users"]
        self.assertTrue(all(not user["username"].startswith("demo") for user in users))
        self.assertEqual(users[0]["username"], "minhkhoi")
        self.assertEqual(users[-1]["username"], "vinhan")
        self.assertEqual([user["id"] for user in users], sorted(user["id"] for user in users))

    def test_profile_extension_uses_user_identity_after_username_change(self):
        data = generate.build_dataset()
        data["users"][0]["username"] = "changedusername"
        posts = demo01_posts.build_posts(data)
        self.assertEqual({post["author_id"] for post in posts}, {data["users"][0]["id"]})

    def test_dataset_and_relationships(self):
        data = generate.build_dataset()
        self.assertEqual({name: len(rows) for name, rows in data.items()}, {
            "users": 40, "categories": 16, "blogs": 400,
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
