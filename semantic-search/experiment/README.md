# Thực nghiệm tìm kiếm — 175 bài, 10 query

Bộ mới lấy **175 bài thuộc ít nhất một trong hai chủ đề `hoc_tap` hoặc `cong_nghe`** từ `semantic-search/data-semantic-1000.csv`. Có 92 bài mang nhãn Học tập, 90 bài mang nhãn Công nghệ; 7 bài có cả hai nên chỉ tính một lần. Bộ truy vấn có 10 câu, mỗi nhóm A–E có 2 câu. Ground truth đã chốt đủ **1.750 cặp**: **1.617 nhãn 0, 40 nhãn 1, 93 nhãn 2**.

**File runner dùng để đánh giá:** `D:\3-SoulSpace\semantic-search\experiment\data\ground-truth-final.csv`. File này chỉ có một cột `label` cuối cùng; các cột agent chỉ nằm ở file tổng hợp để đối chiếu.

Mọi lệnh dưới chạy bằng **PowerShell từ `D:\3-SoulSpace`**. DB vẫn có thể chứa 1.000 bài; cả keyword và semantic chỉ xếp hạng trong 175 ID đã chốt. Không xóa bài khỏi DB để tạo tập con.

## 1. Các file và thứ tự chạy

| File | Công dụng |
|---|---|
| `data/corpus.csv` | ID bài, toàn bộ nội dung, SHA-256, ID nguồn và nhãn chủ đề. |
| `data/queries.csv` | Query ID, nhóm, câu truy vấn và ý định cần chấm. |
| `data/ground-truth-template.csv` | Mẫu 1.750 cặp, `label` để trống; không coi trống là 0. |
| `data/ground-truth-tong-hop.csv` | Bảng đối chiếu ba agent và `final_label` đã review đủ 1.750 cặp. |
| `data/ground-truth-final.csv` | Nhãn chốt chính thức, 6 cột; đầu vào mặc định của runner 02/03. |
| `data/dataset-info.json` | Phạm vi, số lượng, cấu hình embedding và hash dữ liệu. |
| `runner/00-finalize-ground-truth.py` | Kiểm tra nhãn đã chấm, tạo `ground-truth-final.csv` và cập nhật metadata. |
| `runner/01-check-corpus.py` | Đối chiếu 175 bài và vector trên DB; chỉ đọc, có thể chạy khi chưa có nhãn. |
| `runner/02-run-experiment.py` | Chạy keyword, semantic hoặc both; yêu cầu nhãn đã chốt. |
| `runner/03-evaluate.py` | Tính metrics cho một thư mục kết quả, bao gồm both. |
| `runner/experiment_data.py` | Thư viện dùng chung; không chạy trực tiếp. |

**Bộ hiện tại đã hoàn tất bước 00.** Chạy keyword: 02 → 03. Chạy semantic/both: 01 → 02 → 03. Bước 00 chỉ cần chạy lại khi thay đổi nhãn đã review; công cụ không tự gọi AI để chấm.

## 2. Gắn nhãn và chốt ground truth

Hiện đã chốt theo nhãn đồng thuận/đa số của ba agent; 11 cặp không có đa số được review theo `intent` và người dùng chấp nhận. Các hướng dẫn gắn nhãn dưới đây dành cho lần cập nhật dữ liệu sau; có thể chuyển ngay sang mục 3, 4 hoặc 5 để chạy bộ hiện tại.

Gửi `corpus.csv`, `queries.csv` và `ground-truth-template.csv` cho người/AI đánh giá. Chấm **đủ 175 bài cho mỗi query**, không giới hạn Top K. Có thể chia lô khoảng 25 bài × 10 query = 250 nhãn/lô; tổng 7 lô. Mỗi bài vẫn phải được chấm cho cả 10 query.

- **0:** không đáp ứng ý định tìm kiếm.
- **1:** liên quan một phần, chưa đáp ứng đầy đủ.
- **2:** đáp ứng rõ ràng ý định trong `intent`.

Đánh giá theo nội dung thực tế, không suy diễn phần ngữ cảnh bị thiếu. Nhãn chủ đề chỉ dùng chọn corpus, không thay nhãn liên quan. Với nhóm E, chỉ đáp ứng một ý thì chưa đủ để cho 2. Không tự điền 0 cho cặp chưa chấm; các cặp bất đồng cần được chốt thành một nhãn duy nhất trước khi chạy.

File đã review của bộ hiện tại là `D:\3-SoulSpace\semantic-search\experiment\data\ground-truth-tong-hop.csv`, cột nhãn chốt là `final_label`. File được chấp nhận nếu có tối thiểu `query_id,blog_id` và cột nhãn đã chọn; nếu có thêm query/content thì nội dung phải khớp dữ liệu gốc. File template gốc vẫn giữ trống. Khi cần chốt lại sau khi sửa nhãn:

```powershell
cd D:\3-SoulSpace
python -X utf8 semantic-search/experiment/runner/00-finalize-ground-truth.py --input "D:\3-SoulSpace\semantic-search\experiment\data\ground-truth-tong-hop.csv" --label-column final_label
```

Bước này từ chối nhãn trống, ngoài 0/1/2, ID lạ, cặp thiếu/trùng hoặc nội dung không khớp. Khi thành công, tạo `data/ground-truth-final.csv` đúng 6 cột `group,query_id,query,blog_id,content,label` và cập nhật hash. Chưa đủ nhãn thì chưa chạy 02/03.

## 3. Chạy riêng keyword search

Không cần Docker, backend, cookie hoặc Jina ở bước này; chỉ dùng Python và bộ nhãn đã chốt:

```powershell
cd D:\3-SoulSpace
python -X utf8 semantic-search/experiment/runner/02-run-experiment.py --method keyword --repeats 3
```

Kết quả: `results/keyword-search_<timestamp>/`.

Baseline giữ thuật toán cũ: chuẩn hóa NFC, chữ thường, gộp khoảng trắng; tách query theo khoảng trắng, bỏ token trùng; đếm mỗi token xuất hiện như chuỗi con một lần. Bỏ điểm 0, sắp giảm theo điểm, hòa điểm theo UUID tăng dần, lấy tối đa 10 bài. Đây là baseline đơn giản, không phải BM25/PostgreSQL Full-Text Search.

## 4. Chạy riêng semantic search

Dùng venv có `psycopg` hiện có. Nếu chưa cài dependencies:

```powershell
.\data\demo-seed\.venv\Scripts\python.exe -m pip install -r data/demo-seed/requirements.txt
```

Cấu hình `.env.docker` trỏ cùng DB đã nạp bài/vector:

```properties
DB_URL=jdbc:postgresql://127.0.0.1:5432/soulspace
DB_USER=soulspace_runtime
DB_PASSWORD=<mat-khau-runtime-hien-tai>
JINA_API_KEY=<key-Jina>
```

Biến môi trường trong terminal có ưu tiên hơn file, nên dùng terminal chưa nạp cấu hình DB khác. Bật Docker rồi kiểm tra corpus:

```powershell
cd D:\3-SoulSpace
docker compose --env-file .env.docker up -d postgres
.\data\demo-seed\.venv\Scripts\python.exe -X utf8 semantic-search/experiment/runner/01-check-corpus.py --env-file .env.docker
```

Sau khi chốt nhãn và kiểm tra DB thành công, chạy:

```powershell
.\data\demo-seed\.venv\Scripts\python.exe -X utf8 semantic-search/experiment/runner/02-run-experiment.py --method semantic --env-file .env.docker --repeats 3
```

Kết quả: `results/semantic-search_<timestamp>/`. Có **30 lượt gọi Jina** cho 10 query × 3 lần. Chất lượng xếp hạng lấy lần đầu; các lần sau dùng đo thời gian. Không chọn lượt có điểm tốt nhất.

Runner gọi Jina `jina-embeddings-v3`, task `retrieval.query`, 1.024 chiều; dùng vector bài đã có và cosine của pgvector như backend. SQL tạo tập 175 ID bằng `MATERIALIZED` trước khi xếp hạng và lấy Top 10, nên không bị cắt mất bài do Top 50 toàn DB. Đây là phép đo thuật toán trên tập con, không phải kiểm thử HTTP/UI của ứng dụng. Không cần chạy backend, đăng nhập hoặc lấy cookie.

Các lệnh 01/02 chỉ đọc DB, không tạo lại vector bài và không sửa dữ liệu. Jina vẫn cần Internet/key/hạn mức. Dừng việc thêm/sửa/xóa bài trong corpus và backfill trong lúc đo; runner kiểm tra nội dung và dấu vết vector trước/sau lượt chạy.

## 5. Chạy cả keyword và semantic trong một lượt

Hoàn thành bước chốt nhãn và kiểm tra DB ở trên, rồi chạy:

```powershell
cd D:\3-SoulSpace
.\data\demo-seed\.venv\Scripts\python.exe -X utf8 semantic-search/experiment/runner/02-run-experiment.py --method both --env-file .env.docker --repeats 3
```

Chỉ tạo **một thư mục** `results/both-search_<timestamp>/`. `results.csv` chứa cả hai phương pháp và có cột `method` để phân biệt. Keyword chạy một lần; semantic chạy 3 lần với 30 lần gọi Jina. Nếu một phần thất bại thì cả lượt có `complete=false`, không dùng để kết luận so sánh.

## 6. Chạy evaluate

Script 02 sẽ in đường dẫn kết quả. Truyền đúng thư mục đó, thay `<timestamp>` bằng tên thực tế. Ví dụ chọn **một** lệnh tương ứng:

```powershell
python -X utf8 semantic-search/experiment/runner/03-evaluate.py "semantic-search/experiment/results/keyword-search_<timestamp>"
python -X utf8 semantic-search/experiment/runner/03-evaluate.py "semantic-search/experiment/results/semantic-search_<timestamp>"
python -X utf8 semantic-search/experiment/runner/03-evaluate.py "semantic-search/experiment/results/both-search_<timestamp>"
```

Với `both`, chỉ evaluate **một lần**, báo cáo có cả keyword và semantic. Bước này không cần DB, backend hay Jina. Chỉ nhận lượt `complete=true`, dữ liệu/nhãn/kết quả còn khớp hash.

Trong `evaluation-<timestamp>/` của lượt chạy:

- `summary.md`: bảng điểm dễ đọc và giới hạn thực nghiệm.
- `metrics-summary.csv`: điểm theo từng phương pháp, nhóm A–E và ALL.
- `metrics-per-query.csv`: điểm từng query.
- `latency-summary.csv`: p50/p95 và số lượt semantic thành công/lỗi.
- `evaluation-info.json`: hash dùng đối chiếu.

## 7. Cách đọc metrics và kết quả

| Chỉ số | Quy ước |
|---|---|
| Precision@5 | Số bài nhãn 1/2 trong Top 5 chia 5; vị trí không trả về tính là không liên quan. |
| Recall@5 | Số bài nhãn 1/2 trong Top 5 chia tổng bài nhãn 1/2 của query trong **175 bài**. |
| MRR@10 | Trung bình `1/r`, r là vị trí bài nhãn 1/2 đầu tiên trong Top 10; không có thì 0. |
| nDCG@10 | Gain `2^label-1` tương ứng 0/1/3, giảm theo `log2(rank+1)`; IDCG lấy 10 nhãn tốt nhất trong 175 bài. |

Query không có bài liên quan: Recall/nDCG là N/A, P@5/RR là 0. ALL là trung bình mỗi query với trọng số bằng nhau; báo riêng số query hợp lệ. Nếu query có dưới 5 bài liên quan thì P@5 không thể đạt 1; cần nhìn thêm số bài liên quan và nDCG. Không đổi mẫu số để nâng điểm.

Cấu trúc kết quả:

```text
results/
  keyword-search_<timestamp>/
  semantic-search_<timestamp>/
  both-search_<timestamp>/
```

Chỉ tạo folder của phương pháp được chạy; không tạo hai tầng `keyword-search/<run>` và `semantic-search/<run>`. Mỗi lượt có `results.csv`, `timings.csv`, `run-info.json`; không chép lại corpus/queries/ground truth vào đây. `results.csv` gồm câu hỏi, nội dung bài, thứ hạng, điểm, nhãn và method. `--output` nếu dùng là thư mục gốc thay `results/`.

Semantic time gồm gọi Jina cho query và truy vấn DB; không phải thời gian riêng của SQL, không dùng để khẳng định tốc độ HTTP/UI. Corpus chỉ có hai chủ đề và 10 query do AI soạn, nên kết luận giới hạn trong bộ này. File test và bộ thực nghiệm cũ đã được dọn theo yêu cầu; không dùng nhãn/kết quả cũ cho bộ mới.
