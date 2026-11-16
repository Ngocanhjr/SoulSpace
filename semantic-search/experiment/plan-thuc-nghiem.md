# Kế hoạch thực nghiệm tìm kiếm SoulSpace

Hướng dẫn và lệnh chạy chính thức: [README.md](README.md).

## Thiết lập đã chốt

- Corpus: 350 bài công khai, giữ nguyên ID/nội dung/hash; bài “hihi” đã loại khỏi tập đánh giá.
- Truy vấn: 15 câu Q01–Q15, chia đều A trùng từ khóa, B diễn đạt lại, C ngôn ngữ tự nhiên, D mô tả, E nhiều ý.
- Ground truth: 5.250 cặp, nhãn 0/1/2 từ `data/ground-truth-final.csv`, do người dùng chốt theo đa số Claude/Gemini/Chatweb.
- Đồng thuận cả ba: 5.193 cặp; lấy đa số hai bên: 57 cặp. File chốt chỉ có group, query_id, query, blog_id, content, label.
- Kiểm tra đủ ground truth trước khi chạy; không chấm chỉ các bài được hệ thống trả về.
- Từ khóa: baseline đếm token theo chuỗi con trên corpus cố định.
- Ngữ nghĩa: API hiện có, lấy tối đa 50 ứng viên, lọc đúng corpus rồi lấy top 10 khi có thể chứng minh danh sách đủ; dừng nếu bị cắt thiếu.
- Kiểm tra DB trước lượt ngữ nghĩa để xác nhận 350 bài có embedding khớp nội dung; giữ dữ liệu ổn định suốt lượt đo.

## Thực hiện

- [ ] Chạy toàn bộ unit tests.
- [ ] Chạy riêng keyword để xác nhận pipeline local.
- [ ] Kiểm tra DB chỉ đọc bằng `01-check-corpus.py --check`.
- [ ] Khởi động backend, đăng nhập, chạy `02-run-experiment.py --method both --repeats 3`.
- [ ] Lưu riêng kết quả vào `results/keyword-search/<ma-luot>` và `results/semantic-search/<ma-luot>`; chế độ both dùng chung mã lượt.
- [ ] Lưu 45 thời gian API và top 10 lượt đầu; mọi query A–E đều được đo 3 lần.
- [ ] Chỉ dùng lượt có `complete: true`; lưu và xử lý lỗi riêng.
- [ ] Chạy `03-evaluate.py` với corpus/queries/ground truth dùng chung trong data/, đối chiếu hash của lượt chạy.
- [ ] Tổng hợp P@5, Recall@5, MRR@10, nDCG@10 theo từng query, từng nhóm và ALL.

## Báo cáo

P/Recall/MRR dùng label >= 1; nDCG dùng gain 0/1/3. Recall tính mẫu số từ toàn corpus. IDCG tính từ 10 nhãn cao nhất toàn corpus. Mỗi query có trọng số bằng nhau; báo số câu hợp lệ nếu Recall/nDCG có N/A.

| Phương pháp | Nhóm | Precision@5 | Recall@5 | MRR@10 | nDCG@10 |
|---|---|---|---|---|---|
| Từ khóa | A/B/C/D/E/ALL | Điền số đo thực | — | — | — |
| Ngữ nghĩa | A/B/C/D/E/ALL | Điền số đo thực | — | — | — |

Ghi cấu hình embedding thực tế, ngày chạy, máy, backend/DB/mạng và hash dữ liệu. Thời gian p50/p95 là API lấy 50 ứng viên, gồm mạng/Jina/DB; không gọi đây là tốc độ truy vấn DB riêng 350 bài hoặc so trực tiếp với baseline CSV.

Phân tích các query ngữ nghĩa tốt hơn/kém hơn/ngang baseline dựa trên kết quả thực. Nêu giới hạn bộ truy vấn nhỏ do AI soạn, nhãn đa số agent, baseline đơn giản và sự phụ thuộc môi trường. Không chọn lượt tốt nhất hoặc sửa nhãn theo thứ hạng.

Bố cục báo cáo: Thiết lập thực nghiệm → Bộ truy vấn và chỉ số → Kết quả → Nhận xét và giới hạn.
