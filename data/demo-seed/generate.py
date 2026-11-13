"""Sinh CSV cố định từ nội dung hư cấu đã biên soạn, không cần thư viện ngoài."""
import csv
from collections import Counter
from datetime import date, datetime, timedelta, timezone
import hashlib
from pathlib import Path
import random
import re
import sys
import unicodedata
import uuid


ROOT = Path(__file__).resolve().parent
END = datetime(2026, 11, 1, tzinfo=timezone.utc)
TOPICS = (
    ("hoc-tap", "Học tập"), ("cong-viec", "Công việc"),
    ("tinh-cam", "Tình cảm"), ("gia-dinh", "Gia đình"),
    ("suc-khoe", "Sức khỏe"), ("cuoc-song", "Cuộc sống"),
    ("tai-chinh", "Tài chính"), ("cong-nghe", "Công nghệ"),
    ("giai-tri", "Giải trí"), ("the-thao", "Thể thao"),
    ("du-lich", "Du lịch"), ("am-thuc", "Ẩm thực"),
    ("lam-dep", "Làm đẹp"), ("mua-sam", "Mua sắm"),
    ("xa-hoi", "Xã hội"), ("so-thich", "Sở thích"),
)
NAMES = (
    "Nguyễn Minh Khôi", "Trần Ngọc An", "Lê Hoài Phương", "Phạm Gia Bảo",
    "Võ Thanh Vy", "Đặng Đức Hòa", "Bùi Mai Chi", "Đỗ Nhật Nam",
    "Hồ Bảo Châu", "Dương Tuấn Kiệt", "Ngô Hải Yến", "Đinh Phúc Lâm",
    "Huỳnh Thảo Nhi", "Phan Quang Huy", "Lý Diệu Linh", "Mai Anh Khoa",
    "Tạ Thu Ngân", "Cao Minh Trí", "Đoàn Kim Oanh", "Vũ Thành Đạt",
    "Trịnh An Nhiên", "Nguyễn Hữu Duy", "Trần Khánh Vân", "Lê Đông Quân",
    "Phạm Tường Vi", "Võ Quốc Bình", "Đặng Hà My", "Bùi Thiên Sơn",
    "Đỗ Cẩm Tú", "Hồ Duy Khang", "Dương Hồng Đào", "Ngô Bảo Long",
    "Đinh Trúc Lam", "Huỳnh Hoàng Phúc", "Phan Ngọc Hân", "Lý Thanh Tùng",
    "Mai Khánh Ly", "Tạ Anh Dũng", "Cao Tú Uyên", "Đoàn Vĩnh An",
)
EXTRA_THREADS = (
    (
        "Bạn có ví dụ JOIN ba bảng để mình thử thêm không?",
        "Có thể thêm bảng đăng ký môn, rồi nối sinh viên với môn thông qua bảng đó.",
        "Nếu một sinh viên đăng ký hai môn thì kết quả có hai dòng đúng không?",
        "Đúng, mỗi dòng là một lượt đăng ký. Cần xác định mình muốn đếm sinh viên hay lượt đăng ký.",
        "Mình từng đếm sai chỗ này khi tổng hợp số sinh viên theo lớp.",
        "Thử chọn riêng khóa của từng bảng trước, nhìn xem dòng nào bị lặp rồi mới viết phần tổng hợp.",
        "Nếu chưa có lượt đăng ký thì sinh viên đó có biến mất không?",
        "Với INNER JOIN thì có. Nếu muốn giữ cả sinh viên chưa đăng ký, bạn xem trường hợp LEFT JOIN.",
        "Mình chạy bảng nhỏ thấy khác biệt rồi, nhất là phần giá trị NULL.",
        "Bạn thử thêm điều kiện lọc ở hai vị trí khác nhau để quan sát tiếp nhé, rồi đối chiếu từng dòng.",
    ),
    (
        "Bạn ghi hướng dẫn cài theo hệ điều hành hay theo bước chung?",
        "Mình ghi bước chung trước, chỗ lệnh khác nhau thì ghi riêng cho Windows và Linux.",
        "Có cần ghi phiên bản chính xác đã dùng không?",
        "Mình ghi bản đã thử và yêu cầu phiên bản của dự án, để người sau có cơ sở kiểm tra.",
        "Phần biến môi trường có nhiều giá trị riêng từng máy không?",
        "Có, nên mình ghi ý nghĩa từng biến và để người dùng tự điền, không đưa giá trị thật vào tài liệu.",
        "Mình thường quên mở cửa sổ lệnh mới sau khi đổi môi trường.",
        "Mình sẽ thêm cách kiểm tra cấu hình đã được nhận, chỉ hiển thị trạng thái chứ không in bí mật.",
        "Nhờ người khác chạy lại từ đầu chắc bắt được nhiều lỗi hơn tự đọc.",
        "Ừ, mình nhờ một bạn làm theo tài liệu và ghi chỗ phải hỏi. Những câu hỏi đó thành phần cần sửa.",
    ),
)


def stamp(value):
    return value.isoformat(timespec="seconds").replace("+00:00", "Z")


def fixed_id(table, number, created):
    """UUID v7 cố định cho fixture, 74 bit còn lại lấy từ SHA-256."""
    bits = int.from_bytes(hashlib.sha256(f"soulspace-demo-v1/{table}/{number}".encode()).digest()[:10], "big")
    value = (int(created.timestamp() * 1000) << 80) | (bits & ((1 << 76) - 1))
    value |= 7 << 76
    value = (value & ~(3 << 62)) | (2 << 62)
    return str(uuid.UUID(int=value))


def read_content():
    groups = {}
    current = None
    for line in (ROOT / "content.txt").read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        if line.startswith("["):
            current = line[1:-1]
            groups[current] = []
            continue
        fields = line.split("|")
        if len(fields) != 4 or current is None:
            raise ValueError("content.txt: mỗi dòng cần nhãn phụ, bài và hai lượt thoại")
        groups[current].append([value.replace("\\n", "\n") for value in fields])
    if set(groups) != {slug for slug, _ in TOPICS} or any(len(g) != 25 for g in groups.values()):
        raise ValueError(f"Mỗi chủ đề cần 25 bài: { {k: len(v) for k, v in groups.items()} }")
    return [(slug, *groups[slug][i]) for i in range(25) for slug, _ in TOPICS]


def build_dataset():
    rng = random.Random(20261113)
    data = {name: [] for name in (
        "users", "categories", "blogs", "blog_categories", "comments",
        "blog_likes", "comment_likes", "blog_shares",
    )}
    for i, name in enumerate(NAMES):
        created = datetime(2026, 5, 1, tzinfo=timezone.utc) + timedelta(hours=i * 29 + rng.randrange(20))
        data["users"].append(dict(id=fixed_id("users", i, created), username=f"demo{i+1:02d}",
                                  fullname=name, dob=date(1988 + i % 16, i % 12 + 1, i % 25 + 1).isoformat(),
                                  created_at=stamp(created), updated_at=stamp(created)))
    for i, (slug, name) in enumerate(TOPICS):
        data["categories"].append(dict(id=fixed_id("categories", i, datetime(2026, 5, 1, tzinfo=timezone.utc)),
                                       name=name, slug=slug))
    categories = {r["slug"]: r["id"] for r in data["categories"]}
    authors = list(range(40)) * 4 + rng.choices(range(40), weights=[41-i for i in range(40)], k=240)
    rng.shuffle(authors)
    private = set(rng.sample(range(400), 50))
    content = read_content()
    for i, (topic, secondary, text, root, reply) in enumerate(content):
        created = datetime(2026, 7, 1, tzinfo=timezone.utc) + timedelta(minutes=rng.randrange(115*24*60))
        blog = dict(id=fixed_id("blogs", i, created), author_id=data["users"][authors[i]]["id"],
                    content=text, published="false" if i in private else "true", category_mode="MANUAL",
                    created_at=stamp(created), updated_at=stamp(created))
        data["blogs"].append(blog)
        labels = [topic] + ([] if secondary == "-" else [secondary])
        for label in labels:
            data["blog_categories"].append(dict(blog_id=blog["id"], category_id=categories[label],
                                                 category_source="MANUAL", confidence=""))
        if i < 340:
            parent = ""
            for j, text in enumerate((root, reply)):
                actor = blog["author_id"]
                if j == 0 and blog["published"] == "true":
                    actor = data["users"][(authors[i] + 1 + i % 38) % 40]["id"]
                posted = created + timedelta(minutes=30 + j*120)
                comment = dict(id=fixed_id("comments", len(data["comments"]), posted), blog_id=blog["id"],
                               author_id=actor, parent_id=parent, content=text,
                               created_at=stamp(posted), updated_at=stamp(posted))
                data["comments"].append(comment)
                parent = comment["id"]
    # Hai cuộc trao đổi có 12 cấp, giữ nguyên nội dung liên quan từng bài.
    for blog_index, thread in enumerate(EXTRA_THREADS):
        blog = data["blogs"][blog_index]
        parent = data["comments"][blog_index*2+1]["id"]
        base = datetime.fromisoformat(blog["created_at"].replace("Z", "+00:00"))
        for j, text in enumerate(thread):
            actor = blog["author_id"]
            if blog["published"] == "true" and j % 2 == 0:
                actor = data["users"][(authors[blog_index] + 3 + j) % 40]["id"]
            posted = base + timedelta(hours=j+5)
            comment = dict(id=fixed_id("comments", len(data["comments"]), posted), blog_id=blog["id"],
                           author_id=actor, parent_id=parent, content=text,
                           created_at=stamp(posted), updated_at=stamp(posted))
            data["comments"].append(comment)
            parent = comment["id"]

    def interactions(table, targets, count, target_column, time_column):
        pairs = set()
        while len(data[table]) < count:
            target = rng.choices(targets, weights=[1/(1+i%53)**1.25 for i in range(len(targets))])[0]
            blog = target if table != "comment_likes" else blogs[target["blog_id"]]
            if table == "blog_shares" and blog["published"] != "true":
                continue
            actor = rng.choice(data["users"])["id"] if blog["published"] == "true" else blog["author_id"]
            pair = (target["id"], actor)
            if pair in pairs:
                continue
            pairs.add(pair)
            earliest = datetime.fromisoformat(target["created_at"].replace("Z", "+00:00")) + timedelta(minutes=5)
            posted = earliest + timedelta(seconds=rng.randrange(int((END-earliest).total_seconds())))
            data[table].append({target_column: target["id"], "user_id": actor, time_column: stamp(posted)})

    blogs = {b["id"]: b for b in data["blogs"]}
    interactions("blog_likes", data["blogs"], 1000, "blog_id", "created_at")
    interactions("comment_likes", data["comments"], 200, "comment_id", "created_at")
    interactions("blog_shares", data["blogs"], 150, "blog_id", "shared_at")
    validate(data)
    return data


def validate(data):
    def require(ok, reason):
        if not ok:
            raise ValueError(reason)

    def instant(s):
        return datetime.fromisoformat(s.replace("Z", "+00:00"))

    maps = {name: {r["id"]: r for r in data[name]} for name in ("users", "categories", "blogs", "comments")}
    for name, mapping in maps.items():
        require(len(mapping) == len(data[name]), f"Trùng ID {name}")
        require(all(uuid.UUID(key).version == 7 for key in mapping), f"UUID {name}")
    require(len({u["username"] for u in data["users"]}) == 40, "Trùng username")
    require(all(re.fullmatch(r"[a-z0-9]{3,32}", u["username"]) for u in data["users"]), "Username sai")
    require(len({c["slug"] for c in data["categories"]}) == 16, "Trùng slug")
    require(all(re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", c["slug"]) for c in data["categories"]), "Slug sai")
    require(len({b["content"] for b in data["blogs"]}) == 400, "Bài viết trùng")
    require(len({c["content"] for c in data["comments"]}) == 700, "Bình luận trùng")
    for rows in data.values():
        for row in rows:
            require(all("\ufffd" not in v and unicodedata.normalize("NFC", v) == v for v in row.values()), "Lỗi UTF-8/NFC")
            for k,v in row.items():
                if k.endswith("_at"):
                    require(instant(v) <= END, "Thời gian vượt mốc dataset")
    for b in data["blogs"]:
        require(b["author_id"] in maps["users"] and 0 < len(b["content"].strip()) <= 5000, "Bài không hợp lệ")
        require(instant(maps["users"][b["author_id"]]["created_at"]) <= instant(b["created_at"]), "Bài trước user")
        require(b["category_mode"] == "MANUAL", "Mode chủ đề")
    require(sum(b["published"] == "true" for b in data["blogs"]) == 350, "Tỷ lệ public")
    linked = set()
    for row in data["blog_categories"]:
        key = (row["blog_id"], row["category_id"], row["category_source"])
        require(key not in linked and key[0] in maps["blogs"] and key[1] in maps["categories"], "Liên kết nhãn sai")
        require(row["category_source"] == "MANUAL" and row["confidence"] == "", "Nhãn giả AUTO")
        linked.add(key)
    depth = {}
    for c in data["comments"]:
        b = maps["blogs"][c["blog_id"]]
        require(0 < len(c["content"].strip()) <= 2000 and c["author_id"] in maps["users"], "Comment sai")
        require(b["published"] == "true" or c["author_id"] == b["author_id"], "Comment private sai quyền")
        require(instant(c["created_at"]) >= instant(b["created_at"]), "Comment trước bài")
        parent = c["parent_id"]
        if parent:
            require(parent in depth and maps["comments"][parent]["blog_id"] == c["blog_id"], "Parent sai bài hoặc vòng")
            require(instant(c["created_at"]) >= instant(maps["comments"][parent]["created_at"]), "Reply trước cha")
        depth[c["id"]] = depth[parent] + 1 if parent else 1
    require(max(depth.values()) >= 12, "Thiếu chuỗi sâu")
    for name in ("blog_likes", "comment_likes", "blog_shares"):
        seen = set()
        for r in data[name]:
            key = "comment_id" if name == "comment_likes" else "blog_id"
            obj = maps["comments" if key == "comment_id" else "blogs"][r[key]]
            b = maps["blogs"][obj["blog_id"]] if key == "comment_id" else obj
            require(r["user_id"] in maps["users"], "Actor không tồn tại")
            require(b["published"] == "true" or (name != "blog_shares" and r["user_id"] == b["author_id"]), "Tương tác private sai quyền")
            pair = (r[key], r["user_id"])
            require(pair not in seen, "Trùng tương tác")
            seen.add(pair)
            require(instant(r.get("created_at", r.get("shared_at"))) >= instant(obj["created_at"]), "Tương tác trước nội dung")


def write_dataset(directory, data):
    directory.mkdir(parents=True, exist_ok=True)
    for name, rows in data.items():
        with (directory / f"{name}.csv").open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(rows[0]), lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    result = build_dataset()
    write_dataset(ROOT, result)
    print({k: len(v) for k, v in result.items()})
    print("Số bài mỗi user:", sorted(Counter(r["author_id"] for r in result["blogs"]).values()))
