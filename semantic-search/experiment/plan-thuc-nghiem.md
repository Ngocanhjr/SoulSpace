# Kế hoạch thực nghiệm keyword và semantic search

Lệnh chạy cụ thể nằm trong [README.md](README.md). Kế hoạch này mô tả dữ liệu, cách so sánh và công cụ dùng trong thực nghiệm.

## 1. Mục tiêu và phạm vi

So sánh chất lượng xếp hạng của baseline keyword với semantic dùng Jina v3 + cosine trên **cùng tập 175 bài và cùng 10 query**. Giữ Precision@5, Recall@5, MRR@10 và nDCG@10. Đây là thực nghiệm quy mô nhỏ trong hai chủ đề Học tập/Công nghệ, không đại diện toàn bộ 1.000 bài hoặc mọi nhu cầu tìm kiếm.

Không dùng pooling trong phương án đã chốt. Mọi query cần được chấm với toàn bộ 175 bài, gồm cả bài không xuất hiện trong kết quả hai hệ thống.

## 2. Dữ liệu thực nghiệm

- Nguồn: `semantic-search/data-semantic-1000.csv` gồm 1.000 dòng.
- Quy tắc chọn: bài có ít nhất một nhãn `hoc_tap` hoặc `cong_nghe`; hợp hai tập rồi loại trùng ID. Có 92 bài Học tập, 90 bài Công nghệ, 7 bài thuộc cả hai: **175 bài**.
- Giữ nguyên toàn bộ nội dung và ID PostgreSQL theo công thức import `md5('soulspace.csv.data-semantic-1000.' || source_id)::uuid`.
- `data/corpus.csv` gồm `blog_id,content,content_sha256,source_id,topics`. Nhãn chủ đề chỉ phục vụ chọn phạm vi; không được lấy làm nhãn liên quan 0/1/2.
- DB có thể giữ nguyên 1.000 bài và các vector đã có. Hai cách tìm chỉ sử dụng 175 ID đã chọn; không cần xóa bài hay chạy lại embedding.
- Bộ dữ liệu, ground truth và kết quả của thực nghiệm trước được xóa khỏi thư mục experiment; file nguồn 1.000 bài vẫn giữ làm đầu vào.

## 3. Truy vấn

Có 10 câu Q01–Q10, mỗi nhóm 2 câu:

| Nhóm | Loại | Query ID |
|---|---|---|
| A | Trùng từ khóa | Q01–Q02 |
| B | Đồng nghĩa/diễn đạt lại | Q03–Q04 |
| C | Ngôn ngữ tự nhiên | Q05–Q06 |
| D | Mô tả | Q07–Q08 |
| E | Nhiều ý | Q09–Q10 |

Query được soạn dựa trên nội dung thực của tập con; `intent` quy định cách hiểu để người/AI chấm thống nhất. Không sửa query sau khi xem kết quả tìm kiếm để có lợi cho một phương pháp. Nếu đổi ý định thì phải gắn nhãn lại query đó.

## 4. Ground truth

`data/ground-truth-template.csv` có **175 × 10 = 1.750 cặp**, đúng 6 cột `group,query_id,query,blog_id,content,label`. Toàn bộ label ban đầu để trống.

Trạng thái hiện tại: đã chốt đủ 1.750 cặp tại `D:\3-SoulSpace\semantic-search\experiment\data\ground-truth-final.csv`, gồm 1.617 nhãn 0, 40 nhãn 1, 93 nhãn 2. File tổng hợp có ba cột agent và `final_label`; nhãn đa số được giữ, 11 cặp ba nhãn khác nhau được review theo intent và người dùng chấp nhận. Runner chỉ đọc cột label trong bản final 6 cột; không dùng nhãn từng agent riêng lẻ. Khi sửa bản tổng hợp, chạy lại bước 00 với `--label-column final_label` trước lượt thực nghiệm mới.

Thang nhãn: 0 không liên quan; 1 liên quan một phần; 2 đáp ứng rõ ràng ý định. Với nhóm nhiều ý, chỉ khớp một ý chưa đủ để cho 2. Không suy diễn ngữ cảnh không có trong bài ngắn. Cùng bài ở query khác phải chấm riêng.

Người dùng gửi dữ liệu cho ChatGPT/Gemini/Claude hoặc người chấm. Có thể chia 7 lô, mỗi lô 25 bài × 10 query. Kết quả có thể trả gọn `query_id,blog_id,label`; khi chốt công cụ ghép lại đầy đủ query và content. Kiểm tra các cặp bất đồng và chốt một label duy nhất; không coi nhãn trống là 0. Ghi đúng quy trình người dùng thực sự áp dụng trong báo cáo, không mặc định bộ mới đã có đồng thuận đa số agent.

Bước 00 chỉ kiểm tra đủ cặp, schema, giá trị và hash, không tự đánh giá nhãn. Sau khi đủ 1.750 nhãn, tạo `ground-truth-final.csv` và cập nhật `dataset-info.json`. Chưa đủ nhãn thì không chạy lượt đánh giá chính thức.

## 5. Phương pháp tìm kiếm

**Keyword baseline:** chuẩn hóa NFC/chữ thường/khoảng trắng; tách query theo khoảng trắng; mỗi token chỉ tính một lần khi xuất hiện như chuỗi con trong bài; bỏ điểm 0; sắp điểm giảm dần và hòa theo UUID; lấy Top 10. Không coi baseline này là BM25 hay PostgreSQL Full-Text Search.

**Semantic:** dùng vector bài `jina-embeddings-v3`, `retrieval.passage`, 1.024 chiều đã nạp. Query dùng cùng model với task `retrieval.query`. Runner gọi Jina rồi truy vấn pgvector trực tiếp, giới hạn 175 ID trong CTE MATERIALIZED trước khi tính cosine, sắp điểm giảm dần và hòa theo UUID, lấy Top 10. Không lấy Top 50 toàn DB rồi mới lọc tập con.

Thuật toán semantic dùng cùng model/task/distance với backend, nhưng phép đo này đi trực tiếp DB; không đo đăng nhập, HTTP hay giao diện. Không cần cookie/Playwright/backend đang chạy. Có thể kiểm thử luồng web riêng sau thực nghiệm chất lượng.

## 6. Công cụ

| Công cụ | Vai trò |
|---|---|
| Python 3 và thư viện chuẩn | Đọc/ghi CSV, kiểm tra hash, chạy keyword, tính metrics và xuất báo cáo. |
| `psycopg` trong `data/demo-seed/.venv` | Kết nối PostgreSQL chỉ đọc cho kiểm tra và truy vấn semantic. |
| Docker Compose + PostgreSQL 17/pgvector | DB local, view bài công khai, bảng vector 1.024 chiều, phép tính cosine. |
| Jina API | Tạo vector query khi chạy semantic; cần key, Internet và hạn mức. |
| ChatGPT/Gemini/Claude hoặc người chấm | Gắn nhãn thủ công bên ngoài runner; không tự gọi các dịch vụ này để chấm. |

Các file runner đều ghi mục đích và thứ tự ở đầu file: 00 chốt nhãn → 01 kiểm tra DB → 02 tìm kiếm → 03 evaluate. Thư viện `experiment_data.py` được import tự động.

## 7. Quy trình và kiểm soát chất lượng

1. Chốt corpus, query và ý định, tạo template nhãn trống.
2. Chấm đủ 1.750 cặp, đối chiếu bất đồng, chạy bước 00 để kiểm tra và chốt.
3. Bật Docker và chạy bước 01 để xác nhận 175 bài công khai có đúng nội dung/hash và vector hợp lệ; bài ngoài corpus không ảnh hưởng phạm vi.
4. Giữ nội dung, quyền công khai và vector ổn định; dừng backfill/ghi sửa vào các bài đang đo.
5. Chạy keyword riêng, semantic riêng hoặc both. Keyword chạy một lượt; semantic mặc định 3 lượt với seed thứ tự cố định, tổng **30 lần gọi Jina**. Chất lượng lấy lượt đầu, các lượt sau chỉ đo thời gian.
6. So sánh dấu vết nội dung/vector và hash đầu vào trước/sau. Lượt lỗi hoặc bị ngắt có `complete=false`, không dùng để tính metrics.
7. Chạy evaluate cho folder kết quả. Với both chỉ cần một lần evaluate, báo cáo chứa hai phương pháp.

## 8. Metrics và kết quả

P@5/Recall@5/MRR@10 coi label 1 hoặc 2 là liên quan. Recall dùng tổng bài liên quan trong đủ **175 bài**. nDCG dùng gain 0/1/3 và IDCG từ 10 nhãn cao nhất trong 175 bài. Không có bài liên quan: Recall/nDCG là N/A, P@5/RR là 0. Mỗi query có trọng số bằng nhau; báo điểm từng query, từng nhóm A–E và ALL.

Không bảo đảm mọi query có ít nhất 5 bài liên quan; thống kê số bài liên quan và nêu giới hạn P@5 thay vì đổi công thức. Với chỉ 2 query/nhóm, kết quả nhóm nhạy với từng câu; không khẳng định khác biệt nhỏ là bằng chứng chắc chắn cho mọi truy vấn.

Mỗi lượt có một folder trực tiếp dưới `results/`, tên `keyword-search_<timestamp>`, `semantic-search_<timestamp>` hoặc `both-search_<timestamp>`. Không tạo hai thư mục cha keyword/semantic. Dữ liệu dùng chung trong data, không chép lại vào kết quả.

`results.csv` kèm query, nội dung, method, rank, score và label; `timings.csv` ghi thời gian semantic; `run-info.json` ghi trạng thái và hash. Evaluate tạo thư mục con `evaluation-<timestamp>` với bảng tổng hợp, từng query, p50/p95 và báo cáo Markdown.

Thời gian semantic gồm Jina + DB, không phải thời gian SQL riêng. Không suy ra tốc độ của giao diện hoặc so trực tiếp số này với CPU của keyword. Không chọn lượt tốt nhất, không điều chỉnh thuật toán rồi công bố lại trên cùng tập như một phép đánh giá độc lập.
