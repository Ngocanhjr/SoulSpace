# Bình luận Facebook do Tuấn Duy chọn — gán nhãn GPT

Ngày 11/11/2026. Dữ liệu được thu thập bởi `collection/crawl_facebook_public.py` từ sáu URL trong `collection/download.md`; bản gốc vẫn ở `data/collected/`. Các URL được gộp theo thứ tự: `1DkS118a6f`, `1CxTcrygvi`, `19JNUVhBbp`, `1AQanba47m`, `1cN9UPr78P`, `19mgSLw9Wr`.

- `comments_merged.csv`: 629 dòng gộp từ sáu tệp gốc, chưa gán nhãn.
- `comments_merged.clean.csv`: 615 dòng được giữ theo `collection/preprocess_comments.py`, nhãn trống.
- `comments_merged.removed.csv`: 14 dòng bị loại, gồm 6 dòng trùng nội dung và 8 dòng chỉ có biểu tượng; có cột `filter_reason`.
- `comments_merged.clean.labeled.csv`: 615 dòng sau lọc, giữ nguyên thứ tự và các trường; `label_id` được điền.
- `comments_merged.annotation_audit.csv`: số thứ tự dòng dữ liệu (bắt đầu từ 1, không tính header), ID, nhãn và chỉ dấu các câu ưu tiên kiểm tra lại. ID và số thứ tự dùng để đối chiếu với tệp labeled. `needs_review=NO` không bảo đảm nhãn chính xác.

Áp dụng quy ước ViHSD trong `docs/README.md`: `0=CLEAN`, `1=OFFENSIVE` (tục/xúc phạm không xác định được đối tượng công kích), `2=HATE` (công kích cá nhân hoặc nhóm; bao gồm công kích bằng ẩn ý). Chỉ trích hành vi hoặc thành tích không tự động là HATE; cần xét cả câu, mục tiêu, từ lóng và ngữ cảnh. Từ tục cảm thán được xếp vào OFFENSIVE. Một số biệt danh, lời đùa, mỉa mai, từ viết tắt hoặc ngữ cảnh thiếu có thể được hiểu khác nhau; các dòng ưu tiên kiểm tra được đánh dấu trong audit. Không suy ra tính xác thực của những cáo buộc trong bình luận.

Phân bố: CLEAN 412, OFFENSIVE 34, HATE 169. Nhãn do GPT đọc và gán trong một lượt, chưa được người gán nhãn độc lập xác nhận hay đo độ chính xác; không phải gold label. Một số dòng chỉ gồm tên người hoặc URL vẫn được quy tắc tiền xử lý hiện tại giữ; chúng được đánh dấu trong audit, có thể loại thêm tùy mục tiêu huấn luyện. Trường `context` của crawler Facebook là tiêu đề trang, không phải bình luận cha; toàn bộ dòng thu được thiếu ID cha, nên không đủ ngữ cảnh để xác định mọi mục tiêu hoặc lời đáp.

Thu thập không đảm bảo đầy đủ. Riêng bài `1CxTcrygvi` dừng do lỗi truy cập, timeout hoặc thay đổi giao diện; lần chạy lại không lấy thêm được dòng nào. Năm bài còn lại dừng sau năm vòng không thấy ID mới. Dòng bị loại do trùng nội dung có thể khác ngữ cảnh, nên kiểm tra tệp removed nếu điều đó quan trọng.

SHA-256 của `comments_merged.clean.csv`: `66c4a13e6aca26bcd57d566ed690c60c1f95a3e799036e330e75e22e89e6b30e`.
