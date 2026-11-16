# Đối chiếu nhãn Claude, Gemini và Chatweb

Phạm vi: 15 truy vấn × 350 bài trong corpus.csv hiện tại = **5.250 cặp truy vấn–bài đăng**. Ghép theo query_id + blog_id; không ghép theo thứ tự dòng.

## 1. Kiểm tra đầu vào

| Nguồn | Số dòng nhãn gốc | Cặp thuộc corpus 350 bài | Ngoài phạm vi |
|---|---:|---:|---:|
| claude | 5,265 | 5,250 | 15 |
| gemini | 5,250 | 5,250 | 0 |
| chatweb | 5,250 | 5,250 | 0 |

Claude còn 15 nhãn (đều là 0, một cho mỗi query) của bài 01a1df83-427c-77a0-b1cc-68ad4e30c81f, nội dung “hihi”, đã bị bỏ khỏi corpus hiện tại. Chỉ loại các dòng đó khỏi thống kê chung; không sửa ba file nguồn.

Cả ba nguồn đều đủ 5.250 cặp cần đối chiếu, không trùng khóa, không thiếu query/bài trong tập chuẩn và không có nhãn ngoài 0/1/2. Không thể xác minh chỉ từ CSV rằng các mô hình đã dùng đúng phiên bản nội dung/prompt hoặc chấm độc lập; báo cáo này đo sự đồng thuận của các nhãn được cung cấp.

## 2. Kết quả tổng thể

- Cả ba trùng nhãn: **5,193/5.250 = 98.91%**.
- Có khác biệt: **57 cặp**; cả 57 cặp đều có hai bên cùng nhãn, một bên khác.
- Cả ba chấm ba mức khác nhau (0/1/2): **0 cặp**.
- Trong các cặp cả ba trùng nhãn: cùng 0 = 5,140; cùng 1 = 36; cùng 2 = 17.
- Bất đồng giữa không liên quan và có liên quan (0 so với 1/2): **54 cặp**.
- Chỉ bất đồng mức độ liên quan (1 so với 2): **3 cặp**.

**Lưu ý về nhãn 0:** có 110 cặp được ít nhất một mô hình cho nhãn 1 hoặc 2. Trong tập này, cả ba trùng nhãn ở 53/110 cặp (48.18%). Tỷ lệ đồng thuận toàn bộ cao chủ yếu do nhiều cặp cùng nhãn 0; không nên xem đó là bằng chứng ground truth đã chính xác.

## 3. Thống kê theo từng query

| Query | Cả ba trùng | Hai bên trùng, một bên khác | Cả ba khác | Tỷ lệ cả ba trùng | Có ít nhất một nhãn 1/2 |
|---|---:|---:|---:|---:|---:|
| Q01 | 348 | 2 | 0 | 99.43% | 5 |
| Q02 | 349 | 1 | 0 | 99.71% | 2 |
| Q03 | 350 | 0 | 0 | 100.00% | 2 |
| Q04 | 349 | 1 | 0 | 99.71% | 4 |
| Q05 | 337 | 13 | 0 | 96.29% | 19 |
| Q06 | 338 | 12 | 0 | 96.57% | 19 |
| Q07 | 349 | 1 | 0 | 99.71% | 4 |
| Q08 | 345 | 5 | 0 | 98.57% | 12 |
| Q09 | 350 | 0 | 0 | 100.00% | 5 |
| Q10 | 345 | 5 | 0 | 98.57% | 7 |
| Q11 | 345 | 5 | 0 | 98.57% | 8 |
| Q12 | 349 | 1 | 0 | 99.71% | 3 |
| Q13 | 344 | 6 | 0 | 98.29% | 9 |
| Q14 | 347 | 3 | 0 | 99.14% | 7 |
| Q15 | 348 | 2 | 0 | 99.43% | 4 |
| **Tổng** | **5,193** | **57** | **0** | **98.91%** | **110** |

Q03 và Q09 đồng thuận hoàn toàn. Q05 có nhiều cặp bất đồng nhất (13), tiếp theo Q06 (12).

## 4. Phân bố nhãn 0/1/2 theo query và mô hình

Mỗi ô dưới đây ghi số bài theo thứ tự **nhãn 0 / nhãn 1 / nhãn 2**; tổng mỗi ô là 350 (dòng toàn bộ là 5.250).

| Query | Claude: 0 / 1 / 2 | Gemini: 0 / 1 / 2 | Chatweb: 0 / 1 / 2 |
|---|---|---|---|
| Q01 | 347 / 2 / 1 | 347 / 2 / 1 | 345 / 4 / 1 |
| Q02 | 348 / 1 / 1 | 348 / 1 / 1 | 349 / 0 / 1 |
| Q03 | 348 / 1 / 1 | 348 / 1 / 1 | 348 / 1 / 1 |
| Q04 | 347 / 2 / 1 | 347 / 2 / 1 | 346 / 3 / 1 |
| Q05 | 343 / 6 / 1 | 343 / 6 / 1 | 332 / 17 / 1 |
| Q06 | 337 / 9 / 4 | 336 / 12 / 2 | 334 / 13 / 3 |
| Q07 | 346 / 3 / 1 | 347 / 2 / 1 | 347 / 2 / 1 |
| Q08 | 340 / 9 / 1 | 342 / 7 / 1 | 341 / 8 / 1 |
| Q09 | 345 / 4 / 1 | 345 / 4 / 1 | 345 / 4 / 1 |
| Q10 | 344 / 5 / 1 | 346 / 3 / 1 | 347 / 2 / 1 |
| Q11 | 343 / 6 / 1 | 345 / 4 / 1 | 346 / 3 / 1 |
| Q12 | 348 / 1 / 1 | 348 / 1 / 1 | 347 / 2 / 1 |
| Q13 | 343 / 5 / 2 | 346 / 2 / 2 | 345 / 3 / 2 |
| Q14 | 345 / 4 / 1 | 346 / 3 / 1 | 344 / 5 / 1 |
| Q15 | 346 / 3 / 1 | 347 / 2 / 1 | 348 / 1 / 1 |
| ALL | 5170 / 61 / 19 | 5181 / 52 / 17 | 5164 / 68 / 18 |

Chatweb gán nhãn 1/2 cho 86 cặp, Claude 80 cặp và Gemini 69 cặp. Đây là sự khác nhau trong kết quả gắn nhãn, không khẳng định mô hình nào đúng hơn.

## 5. Đối chiếu từng cặp mô hình

| Cặp mô hình | Trùng nhãn | Khác nhãn | Tỷ lệ trùng |
|---|---:|---:|---:|
| claude – gemini | 5,233 | 17 | 99.68% |
| claude – chatweb | 5,193 | 57 | 98.91% |
| gemini – chatweb | 5,210 | 40 | 99.24% |

## 6. Các kiểu bất đồng

Thứ tự ba nhãn trong bảng là **Claude / Gemini / Chatweb**.

| Claude | Gemini | Chatweb | Số cặp | Hai bên cùng nhãn | Bên chấm khác |
|---:|---:|---:|---:|---|---|
| 0 | 0 | 1 | 28 | claude+gemini | chatweb |
| 0 | 1 | 1 | 2 | gemini+chatweb | claude |
| 1 | 0 | 0 | 13 | gemini+chatweb | claude |
| 1 | 1 | 0 | 11 | claude+gemini | chatweb |
| 1 | 1 | 2 | 1 | claude+gemini | chatweb |
| 2 | 1 | 1 | 2 | gemini+chatweb | claude |

## 7. File kết quả và cách đọc

- **thong-ke-theo-query.csv**: 15 dòng theo query và một dòng ALL; gồm số đồng thuận/bất đồng, phân bố nhãn, số lần mỗi mô hình là bên khác và đồng thuận từng cặp mô hình.
- **nhan-bat-dong.csv**: toàn bộ 57 cặp cần xem lại, kèm query, intent, nội dung bài, ba nhãn, hai bên giống nhau và bên khác.
- **doi-chieu-toan-bo.csv**: đủ 5.250 cặp trên 350 bài, bao gồm các cặp đồng thuận. Có thể lọc query_id, agreement_type hoặc label_pattern.

Quy ước: all_agree = cả ba giống; two_agree = đúng hai bên giống; all_different = cả ba khác. relevance_boundary_disagreement = 1 khi có bên chấm 0 và có bên chấm 1/2. Nhãn 1/2 được coi là có liên quan chỉ để phân tích ranh giới; việc so sánh nhãn chính vẫn dùng đủ thang 0/1/2.

**Chưa chốt ground truth:** báo cáo chỉ thống kê, không tự ghi final_label hoặc lấy đa số làm nhãn chuẩn. Cần đọc lại 57 cặp bất đồng, đặc biệt 54 cặp đổi ranh giới có/không liên quan, và rà mẫu các cặp đồng thuận trước khi chốt.

## 8. Nội dung truy vấn

| Query | Nhóm | Truy vấn |
|---|---|---|
| Q01 | A | tự đặt câu hỏi sau mỗi buổi học |
| Q02 | A | migration trên một database thử |
| Q03 | A | nấu canh chua |
| Q04 | B | Sắp xếp vật dụng để buổi sáng khỏi cuống cuồng tìm trước khi ra ngoài |
| Q05 | B | Món có giá thấp chưa chắc tiết kiệm nếu hiếm khi dùng tới |
| Q06 | B | Cho phép mình vụng về khi bắt đầu một thú vui mới |
| Q07 | C | Tối nào mình cũng bị cuốn vào xem video trên giường rồi ngủ muộn, có ai từng thay đổi thói quen này chưa? |
| Q08 | C | Mình cứ thấy có lỗi mỗi khi dừng làm việc để nghỉ, làm sao cho bản thân được nghỉ một chút? |
| Q09 | C | Mình nhận quá nhiều việc nhưng sợ bị nghĩ là lười nếu nói không kham nổi, nên trao đổi với quản lý thế nào? |
| Q10 | D | Những bài viết về cảm giác lạc lõng khi mới lên thành phố đi học |
| Q11 | D | Những chia sẻ về việc khó kết bạn khi đã đi làm |
| Q12 | D | Những bài viết về việc tập bỏ thói quen liên lạc với người cũ sau chia tay |
| Q13 | E | Đi chơi ngắn ngày với ngân sách hạn chế nhưng vẫn đủ tiền trả trọ |
| Q14 | E | Tự nấu bữa trưa có rau và đạm nhưng đơn giản để duy trì lâu dài |
| Q15 | E | Làm việc ở nhà và trao đổi với gia đình để có giờ yên tĩnh khi họp |

## 9. Hash SHA-256 đầu vào

Hash ghi nhận đúng phiên bản file được dùng trong lần đối chiếu này.

| File | SHA-256 |
|---|---|
| queries.csv | f2fcf4c3244a5cd86728557862cba3cb5fd537d1423e762bda021ce7b3f63742 |
| corpus.csv | 9786c32a9c69bb2034ce67dd548bbe4217a73fe1c71845f3915efc7dcbd28c48 |
| ground-truth-claude.csv | 74755325cb4af10d5e52b1415dbcc402447b0d801176ebe4bbcc4fa6f36f5358 |
| ground-truth-gemini.csv | 2e0a63c156afd960d3b00f43f6a5f561701e56718640afdac56b2991ebab9cb3 |
| ground-truth-chatweb.csv | 125fc38eb748a2e8015e98e4037bfa7f64767d78d8fda5ed16b5e38b481c09b1 |
