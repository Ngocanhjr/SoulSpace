"""Bài mô phỏng để thử phân trang hồ sơ demo01."""
from collections import Counter
from datetime import datetime, timedelta, timezone
from itertools import product
import random

from generate import fixed_id, stamp


TARGET = {"true": 1000, "false": 300}

# Mỗi nhóm giữ tình huống và lời kết trong cùng chủ đề.
NOTES = (
    (
        ("Tối qua mình thử tự giải bài SQL trước khi xem đáp án.", "Hôm nay cả nhóm đổi đề cho nhau làm thử.",
         "Mình chuyển phần ôn khó nhất sang đầu buổi.", "Chiều nay ngồi thư viện, mình thử đọc lại ghi chú của tuần trước."),
        ("Lúc tự giải mới lộ ra đoạn mình chỉ nhớ cú pháp mà chưa hiểu.", "Phần ghi ngắn giúp mình biết nên hỏi gì trong buổi tới.",
         "Để hôm sau làm lại thì thấy phần nào đã thực sự nhớ.", "Đánh dấu chỗ hay nhầm có ích hơn việc chép lại toàn bộ.",
         "Chỉ cần tự giải thích được một ý thì buổi học cũng đáng.", "Khi có ví dụ cụ thể mình dễ nhận ra chỗ đọc lướt."),
    ),
    (
        ("Sáng nay mình đi làm sớm để có thời gian sắp việc.", "Nhóm mình thử báo tiến độ bằng một tin nhắn ngắn.",
         "Mình đổi cách ghi các việc còn vướng sau cuộc họp.", "Cuối buổi, mình dành vài phút chuẩn bị việc cho ngày mai."),
        ("Ghi rõ ai làm bước tiếp theo thì đỡ phải hỏi qua lại.", "Có việc nhìn nhỏ nhưng cần chờ phản hồi từ người khác.",
         "Cứ tưởng làm nhanh, đến lúc xem lại mới thấy thiếu thông tin.", "Nói sớm chỗ đang kẹt giúp mọi người dễ sắp lịch hơn.",
         "Mình muốn giữ phần ghi chú đủ ngắn để ngày mai còn đọc.", "Nhờ có mốc rõ ràng, mình dễ biết việc nào cần xử lý trước."),
    ),
    (
        ("Cuối tuần mình thử nấu với phần rau còn trong tủ.", "Hôm nay tập chia nguyên liệu trước khi bật bếp.",
         "Chiều nay mình nấu một phần nhỏ để thử vị.", "Mình rủ người nhà cùng chuẩn bị bữa tối thay vì gọi đồ."),
        ("Nêm từng ít một vẫn dễ cứu hơn lỡ tay.", "Có sẵn nguyên liệu đã sơ chế thì đến giờ nấu nhẹ việc hẳn.",
         "Mình ghi lại lượng vừa ăn để lần sau khỏi nấu thừa.", "Chỗ tốn thời gian nhất hóa ra là dọn sau bữa.",
         "Phần còn lại có thể để cho bữa trưa mai.", "Khi nấu ít, mình dễ để ý món thay đổi ra sao trên bếp."),
    ),
    (
        ("Mình đi bộ một vòng quanh khu nhà lúc trời vừa dịu.", "Cuối tuần thử đi một đoạn bằng xe buýt rồi đi bộ.",
         "Sáng nay mình đổi sang đường nhỏ thay vì qua ngã tư lớn.", "Mình thử ghé công viên lúc còn sớm."),
        ("Đi chậm mới để ý được mấy hàng cây dọc đường.", "Đoạn có bóng mát dễ chịu hơn mình tưởng.",
         "Mình ghi lại chỗ có ghế để lần sau rủ người nhà đi cùng.", "Lúc về mới nhận ra mình đã bớt nhìn điện thoại.",
         "Lần sau mình sẽ mang nước theo.", "Mình muốn thử lại tuyến này vào một giờ khác."),
    ),
    (
        ("Mình gom các file tải về vào một thư mục để rà lại.", "Hôm nay mình thử ghi cách cài một công cụ vừa dùng.",
         "Cuối buổi mình dọn mấy nhánh thử nghiệm của bài tập.", "Mình dành thời gian đọc lại một đoạn code mình từng bỏ qua."),
        ("Tên rõ ràng giúp lần sau tìm lại nhanh hơn.", "Tự làm lại từ đầu mới thấy bước nào đang thiếu.",
         "Nhìn ví dụ nhỏ dễ hiểu hơn mở ngay cả dự án.", "Có bản ghi ngắn thì hôm sau tiếp tục đỡ mất công.",
         "Mình đang tập ghi điều kiện đã thử thay vì chỉ ghi kết quả.", "Vẫn còn vài chỗ mình muốn hiểu kỹ hơn."),
    ),
    (
        ("Tối nay mình tập vẽ trên một trang giấy nhỏ.", "Mình dành ít phút thử một cách gấp giấy mới.",
         "Cuối tuần mình sửa lại chậu cây trên bàn.", "Chiều nay mình lấy cuốn sổ cũ ra làm tiếp."),
        ("Làm một lượt chậm giúp mình nhìn ra chỗ hay bỏ qua.", "Mình thích đoạn có thể sửa đi sửa lại cho vừa mắt.",
         "Dùng những gì đang có cũng đủ để thử.", "Để đồ sẵn trên bàn thì dễ nhớ làm thêm một chút.",
         "Mình giữ bản đầu để vài tuần nữa xem đã tiến bộ chỗ nào.", "Lần này mình dừng đúng lúc thấy còn muốn làm tiếp."),
    ),
    (
        ("Tuần này mình ngồi xem lại các khoản chi nhỏ.", "Mình thử để sẵn danh sách trước khi đi mua đồ.",
         "Hôm nay mình sửa một món đang dùng được thay vì mua cái mới.", "Mình rà lại những dịch vụ đang trả tiền hàng tháng."),
        ("Có vài khoản nhỏ cộng lại mới thấy rõ.", "Biết mình cần gì trước khi mua thì dễ bỏ qua mấy món phát sinh.",
         "Mình để qua hôm sau rồi mới quyết định.", "Ghi cả số tiền lẫn lý do chi thì dễ nhớ hơn.",
         "Mình muốn tách phần phải chi và phần có thể lùi.", "Cách này hợp với mình khi có một buổi rảnh để nhìn lại."),
    ),
    (
        ("Tối qua mình nghe lại một album cũ.", "Mình tìm một phim ngắn để xem sau bữa tối.",
         "Cuối tuần hai đứa đổi sách cho nhau đọc.", "Hôm nay mình mở lại danh sách những thứ định xem từ lâu."),
        ("Đọc lại sau một thời gian thì chú ý những đoạn khác trước.", "Mình thích nghe ý kiến người khác sau khi tự xem xong.",
         "Có vài chi tiết nhỏ lúc đầu mình bỏ qua.", "Mình để dành phần còn lại cho một tối rảnh.",
         "Lúc đầu định xem cho qua, cuối cùng lại ngồi nghĩ thêm.", "Mình giữ lại vài dòng cảm nhận để lần sau nhớ."),
    ),
)

PUBLIC_ENDINGS = (
    "Chia sẻ lên đây cho ai cũng đang thử cách tương tự.",
    "Chắc phải thử thêm vài lần mới biết có hợp lâu dài không.",
    "Mọi người có mẹo nào cho tình huống này không?",
    "Có ai từng làm cách khác rồi thấy dễ hơn không?",
    "Lần sau thử lại, mình sẽ để ý xem điều gì thay đổi.",
    "Một chuyện nhỏ trong ngày mà mình muốn ghi lại.",
)
PRIVATE_ENDINGS = (
    "Ghi riêng để cuối tuần đọc lại.",
    "Để đây, hôm khác có thời gian sẽ viết thêm.",
    "Nhắc mình đừng đặt mục tiêu quá nhiều cho lần tới.",
)


def build_posts(data):
    user_id = next(row["id"] for row in data["users"] if row["username"] == "demo01")
    counts = Counter(row["published"] for row in data["blogs"] if row["author_id"] == user_id)
    rng = random.Random(13112026)
    used = {row["content"] for row in data["blogs"]}
    posts = []
    for published, total in TARGET.items():
        endings = PUBLIC_ENDINGS if published == "true" else PRIVATE_ENDINGS
        texts = [" ".join(parts) for starts, observations in NOTES
                 for parts in product(starts, observations, endings)
                 if " ".join(parts) not in used]
        for content in rng.sample(texts, total - counts[published]):
            index = len(posts)
            created = datetime(2026, 7, 1, tzinfo=timezone.utc) + timedelta(hours=index * 2)
            posts.append(dict(id=fixed_id("demo01-extra-blogs", index, created), author_id=user_id,
                              content=content, published=published, category_mode="MANUAL",
                              created_at=stamp(created), updated_at=stamp(created)))
    return posts
