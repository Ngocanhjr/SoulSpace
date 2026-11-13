"""Kiểm tra dataset bằng thư viện chuẩn. Integration dùng PostgreSQL demo riêng."""
from pathlib import Path
import tempfile
import unittest

import generate


ROOT = Path(__file__).resolve().parent


class DatasetTests(unittest.TestCase):
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
