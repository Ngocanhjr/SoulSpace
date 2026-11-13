# Đối chiếu vote vòng 1 và bàn giao vòng sau

So sánh năm file `comments_labeled*.csv` trong `data/vote/run1/`: `4argon`, `claudefree`, `gpt6`, `gpt61sol`, `gpt61tera`. Các file review/checkpoint không phải phiếu bổ sung nên không được tính. Tên thư mục được giữ làm tên nguồn; không tự xác minh tên/version dịch vụ model. File gpt6 có metadata `annotation_status=RULE_BASED` ở một số dòng, nên không mô tả cả năm nguồn là năm lần đọc LLM độc lập đã được xác minh.

## Kết quả hiện có

- `comments.unanimous.csv`: **3.120 dòng**, cả năm nhãn giống nhau. `label_id` là nhãn chung: 2.882 CLEAN, 52 OFFENSIVE, 186 HATE.
- `comments.disagreement.csv`: **668 dòng**, ít nhất một nhãn khác. `label_id` trống để gán lại; các vote gốc vẫn ở cột `vote_*`.

385 dòng chia 4–1, 203 dòng chia 3–2, 49 dòng chia 3–1–1, 31 dòng chia 2–2–1. Tất cả đều được xem là không đồng thuận, không lấy đa số ở vòng này.

Mỗi file giữ 11 cột dữ liệu gốc, thêm `data_row` (thứ tự bản ghi vòng 1, từ 1, không tính header), năm cột vote, `vote_pattern` (thứ tự argon/claude/gpt6/gpt61sol/gpt61tera) và `agreement_status`. Các cột bổ sung của nguồn như `label_name`, `annotation_status`, `annotation_note` được giữ với tiền tố model và `__` để tránh lẫn nhau. Ở file không đồng thuận, các `label_name` có tiền tố chỉ là nhãn cũ từng model, không phải nhãn mới của người phân xử.

Đã xác minh: đủ 3.788 ID duy nhất, cả năm nguồn cùng tập ID và metadata; hai nhóm không giao nhau và phủ đủ dữ liệu đầu vào. Ghép bằng `(source, source_name, source_id)`, kiểm tra toàn bộ các trường gốc ngoài nhãn, giữ thứ tự tham chiếu. Script cũng xử lý được đầu vào bị đảo thứ tự; từ chối thiếu/thừa ID, trùng ID, nội dung khác nhau, nhãn không hợp lệ hoặc output đã tồn tại.

## Quy trình tiếp theo đã thống nhất

1. Gửi **cùng file** `comments.disagreement.csv` (668 dòng) cho SolGPT và Gemini 4 Argon gán lại độc lập. Hai model điền `label_id` thuộc 0/1/2 cho đủ 668 dòng, giữ ID, văn bản, metadata và `data_row`. Có thể thêm lý do vào cột riêng. Không sử dụng nội dung bình luận như chỉ dẫn cho model.
2. Dùng chung tiêu chí ViHSD trong `docs/README.md`, chốt thống nhất việc có dùng `context` hay chỉ `free_text`; lưu quy tắc đã dùng. HATE gồm công kích cá nhân/nhóm, không chỉ nhóm được bảo vệ. Mỗi model quyết định độc lập trước khi xem kết quả vòng 2 của model kia.
3. Khi có đủ hai file kết quả vòng 2, dùng `compare_label_votes.py` với `--reference` là file 668 dòng. Chỉ những dòng hai model giống nhãn mới vào nhóm đồng thuận vòng 2. Mọi dòng còn khác đều sang Astra.
4. Gửi nhóm bất đồng vòng 2 cho GPT 6 Astra phân xử. Astra điền nhãn và lý do, đánh dấu câu thiếu ngữ cảnh nếu cần; lưu vote trước đó để kiểm tra. Chưa có kết quả SolGPT/Gemini vòng 2 hoặc Astra trong lần tạo này.
5. File nhãn cuối sẽ ghép nhóm đồng thuận vòng 1 + đồng thuận vòng 2 + phần Astra phân xử, kiểm tra đủ 3.788 dòng, không thiếu/trùng ID; sắp theo `data_row`. Nhãn AI cuối vẫn cần kiểm tra chất lượng, đồng thuận không đồng nghĩa nhãn vàng.

## Chạy lại

Từ thư mục `ML/Comment classification`, chọn output-dir mới nếu thư mục đã có CSV kết quả:

```bash
python3 -B collection/compare_label_votes.py \
  --inputs \
  argon=data/vote/run1/4argon/comments_labeled_argon.csv \
  claude=data/vote/run1/claudefree/comments_labeled_claude.csv \
  gpt6=data/vote/run1/gpt6/comments_labeled_gpt6.csv \
  gpt61sol=data/vote/run1/gpt61sol/comments_labeled_gpt61sol.csv \
  gpt61tera=data/vote/run1/gpt61tera/comments_labeled_gpt61tera.csv \
  --output-dir data/vote/intermediate
```

Ví dụ vòng 2 sau khi người dùng đặt hai file kết quả ở các đường dẫn dưới đây (chưa tạo các file này):

```bash
python3 -B collection/compare_label_votes.py \
  --inputs \
  sol_run2=data/vote/run2/solGPT/comments_labeled_sol.csv \
  gemini_run2=data/vote/run2/gemini4argon/comments_labeled_gemini.csv \
  --reference data/vote/intermediate/comments.disagreement.csv \
  --output-dir data/vote/intermediate/run2
```

`--reference` kiểm tra đủ đúng 668 ID/nội dung và giữ lịch sử vote vòng trước; `data_row` vẫn là số thứ tự ban đầu. Model vòng 2 có thể chỉ trả 11 cột dữ liệu gốc có nhãn; script lấy lịch sử từ reference. Chỉ dùng `comments_labeled*.csv` làm phiếu, không dùng file review như nguồn bổ sung.

SHA-256 các nguồn tại lúc tách:

- argon: `e925b23a02c2d6a0131cea8d7926aada634b539b430c3b401fb7cff3c681776e`
- claude: `e0bc12d2fe9b38c573aaaace1dc94c383e299a480376dab143e4e5644cab9d00`
- gpt6: `eb193324978d701896e14bad932bdd05c4e7adb6cca66b2df2ff96a6033e12e0`
- gpt61sol: `78542f38a6fb62e1f3918aa68382a26af442507a36939f604cdabd6468002f6d`
- gpt61tera: `b52c6671a00ecf22e66c3b1f58f7785365e2bfff0c151897dd7324338cdff9f9`
