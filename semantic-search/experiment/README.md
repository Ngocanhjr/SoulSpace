# Thực nghiệm tìm kiếm SoulSpace — bộ nhãn đã chốt

Bộ hiện tại gồm **350 bài, 15 truy vấn, 5.250 cặp truy vấn–bài đăng**, nhãn 0/1/2. Ground truth đã được người dùng chốt theo đa số Claude, Gemini và Chatweb: 5.193 cặp đồng thuận cả ba, 57 cặp lấy nhãn của hai agent giống nhau.

Quy trình hiện tại: **kiểm tra bộ dữ liệu và ground truth → kiểm tra DB nếu chạy ngữ nghĩa → chạy tìm kiếm → tính bốn metrics**. Không tạo bảng nhãn trống hoặc chấm lại sau khi xem kết quả.

## Thứ tự chạy trong runner

| Thứ tự | File | Công dụng | Khi nào chạy |
|---|---|---|---|
| 01 | `runner/01-check-corpus.py --check` | Đối chiếu corpus và embedding với DB, chỉ đọc. | Trước khi chạy semantic hoặc both; bỏ qua nếu chỉ chạy keyword. |
| 02 | `runner/02-run-experiment.py` | Chạy tìm kiếm và lưu kết quả riêng theo phương pháp. | Chọn `--method keyword`, `semantic` hoặc `both`. |
| 03 | `runner/03-evaluate.py <thu-muc-luot-chay>` | Tính bốn metrics từ kết quả và nhãn chốt dùng chung trong data/. | Sau bước 02, chạy cho từng thư mục lượt hoàn tất. |
| — | `runner/experiment_data.py` | Hàm đọc/ghi CSV, kiểm tra nhãn và hash cho các script trên. | Thư viện được import tự động; không chạy trực tiếp. |

**Keyword:** 02 → 03. **Semantic hoặc both:** 01 → 02 → 03. Mỗi file trong `runner/` có ghi mục đích, đầu vào, đầu ra và cách chạy ngay phần đầu.

## 1. Dữ liệu sử dụng

| File | Vai trò |
|---|---|
| `data/corpus.csv` | 350 bài, giữ nguyên ID, toàn bộ nội dung và SHA-256. |
| `data/queries.csv` | Q01–Q15, mỗi nhóm A–E có 3 câu; gồm query và intent. |
| `data/ground-truth-final.csv` | Bộ nhãn chính thức cho đủ 5.250 cặp. File chỉ có 6 cột `group,query_id,query,blog_id,content,label`; `label` là nhãn cuối cùng, nhận 0/1/2. |
| `data/ground-truth-final-info.json` | Số lượng, ý nghĩa nhãn, schema và hash của bộ dữ liệu đã chốt. |
| `data/dataset-info.json` | Manifest hiện tại: số lượng, nhóm truy vấn, fingerprint corpus, hash queries và ground truth. |
| `label-comparison/` | Báo cáo đối chiếu và chi tiết 57 cặp bất đồng trước khi chốt. |

Bài chỉ ghi “hihi”, ID `01a1df83-427c-77a0-b1cc-68ad4e30c81f`, đã được loại khỏi corpus. Snapshot xuất từ DB ban đầu có 351 bài; số lượng lịch sử được lưu riêng trong `original_export_counts`. Không cần xóa bài đó khỏi DB để chạy bộ kiểm thử này.

Nhóm truy vấn: **A = trùng từ khóa, B = đồng nghĩa/diễn đạt lại, C = ngôn ngữ tự nhiên, D = mô tả, E = nhiều ý**. Nhóm D không còn là truy vấn âm tính; cả 15 câu đều được đưa vào metrics và các lượt đo thời gian.

Script từ chối dữ liệu trống, trùng ID, nhãn thiếu/ngoài 0–2, sai nội dung/hash, cặp ngoài corpus hoặc metadata không khớp. Phải có đủ nhãn toàn bộ corpus, kể cả bài không xuất hiện trong kết quả.

## 2. Chạy keyword và đánh giá kết quả

Mọi lệnh chạy từ thư mục gốc repository:

**Bước 02 — Chạy tìm kiếm keyword:**

```powershell
cd D:\3-SoulSpace
python -X utf8 semantic-search/experiment/runner/02-run-experiment.py --method keyword
```

**Bước 03 — Chạy evaluate để tính bốn metrics:** lệnh dưới dùng cho lượt keyword hiện có `20261116T070710512518Z`.

```powershell
cd D:\3-SoulSpace
python -X utf8 semantic-search/experiment/runner/03-evaluate.py "semantic-search/experiment/results/keyword-search/20261116T070710512518Z"
```

Nếu chạy lại bước 02, thay mã lượt ở cuối đường dẫn bằng tên thư mục mới mà script in ra. Tham số là **thư mục lượt chạy**, không phải đường dẫn tới `results.csv`.

Không cần backend, DB, key hoặc thư viện Python bên ngoài cho hai bước keyword này. Kết quả được lưu trong `semantic-search/experiment/results/keyword-search/`.

Báo cáo được lưu trong `results/keyword-search/<ma-luot-chay>/evaluation-<timestamp>/`:

- Mở `summary.md` để xem báo cáo dễ đọc.
- Mở `metrics-summary.csv` để xem điểm tổng hợp.
- Mở `metrics-per-query.csv` để xem điểm từng query.

Baseline giữ nguyên quy tắc: NFC + chữ thường + gộp khoảng trắng; tách truy vấn theo khoảng trắng, bỏ token trùng; đếm số token xuất hiện như chuỗi con trong nội dung, mỗi token một lần. Bỏ điểm 0, sắp điểm giảm dần, hòa điểm theo ID tăng dần, lấy tối đa 10 bài. Đây là baseline đơn giản, không phải BM25/PostgreSQL Full-Text Search.

## 3. Chạy riêng semantic search và đánh giá kết quả

### Bước 01 — Kiểm tra DB trước khi đo

Giữ nguyên nội dung, trạng thái công khai và embedding của 350 bài trong suốt thực nghiệm. Chạy kiểm tra chỉ đọc:

```powershell
cd D:\3-SoulSpace
.\data\demo-seed\.venv\Scripts\python.exe -X utf8 semantic-search/experiment/runner/01-check-corpus.py --check
```

Bước này cần `psycopg`, đọc cấu hình từ `.env.neon`, không gọi Jina và không sửa DB. Kiểm tra đủ 350 bài công khai có nội dung và embedding khớp hash; DB được phép chứa bài khác ngoài corpus. Không bỏ qua lỗi embedding thiếu/cũ: xử lý đồng bộ xong rồi kiểm tra lại trước lượt đo. Không xuất lại hoặc ghi đè corpus/nhãn đã chốt.

### Khởi động backend và lấy phiên đăng nhập

Ở terminal riêng:

```powershell
cd D:\3-SoulSpace\soulspace
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=db-neon,web-local"
```

Backend phải cấu hình Jina và dùng đúng DB đã kiểm tra. Đăng nhập trên giao diện hiện có; mở F12 → Application → Cookies → localhost, sao chép Value của `SOULSPACESESSION`.

### Bước 02 — Chạy tìm kiếm semantic

Sau khi backend khởi động và đã đăng nhập, mở terminal tại thư mục gốc repo:

```powershell
cd D:\3-SoulSpace
python -X utf8 semantic-search/experiment/runner/02-run-experiment.py --method semantic --repeats 3
```

Dán **giá trị cookie** khi script hỏi; nhập ẩn và không lưu vào file. Nếu terminal không hỗ trợ nhập ẩn, script dừng trước khi đọc giá trị; dùng biến môi trường `SOULSPACE_SESSION` trong trường hợp đó. Đổi backend bằng `--base-url`; mặc định `http://127.0.0.1:8080`. Phiên đăng nhập phải thuộc backend này.

Với 15 câu và 3 lượt, có **45 yêu cầu tìm kiếm ngữ nghĩa**, mỗi yêu cầu dùng Jina qua backend. Các GET xác thực/feed không gọi Jina. Kết quả lưu trong `semantic-search/experiment/results/semantic-search/<ma-luot-chay>/`; script in đường dẫn sau khi hoàn tất.

### Bước 03 — Chạy evaluate cho kết quả semantic

Thay `<ma-luot-chay>` bằng tên thư mục mà bước 02 vừa in:

```powershell
python -X utf8 semantic-search/experiment/runner/03-evaluate.py "semantic-search/experiment/results/semantic-search/<ma-luot-chay>"
```

Mở thư mục `results/semantic-search/<ma-luot-chay>/evaluation-<timestamp>/`:

- `summary.md`: báo cáo dễ đọc.
- `metrics-summary.csv`: điểm tổng hợp.
- `metrics-per-query.csv`: điểm từng query.

### Bảo đảm kết quả thuộc đúng 350 bài

API hiện hỗ trợ tối đa 50 kết quả, chưa có bộ lọc corpus. Script yêu cầu `/api/v1/search?q=...&limit=50`, kiểm tra ID/nội dung/điểm/thứ tự, giữ những bài thuộc corpus rồi mới lấy top 10.

Điều này cho đúng top 10 của tập con khi danh sách đã chứa ít nhất 10 bài trong corpus, hoặc khi API trả ít hơn 50 bài và danh sách đã hết. Nếu API trả đủ 50 ứng viên nhưng có dưới 10 bài trong corpus, script **dừng**, vì bài thuộc corpus ở vị trí sau 50 có thể còn thiếu. Không lọc sau khi chỉ lấy top 10 toàn DB.

Đây là kết quả xếp hạng trong corpus được suy ra từ thứ tự API; chưa phải phép đo thời gian truy vấn DB giới hạn riêng 350 bài. Thời gian đo là thời gian API lấy 50 ứng viên, gồm mạng, Jina và DB; không so trực tiếp với thời gian baseline đọc CSV.

Feed được kiểm tra trước và sau lượt chạy: 350 bài phải còn nguyên nội dung, và feed công khai không được thay đổi trong lúc đo. Dữ liệu/vector phải được giữ ổn định; kiểm tra feed không thay thế bước kiểm tra embedding trên DB.

## 4. Chạy cả keyword và semantic

Hoàn thành phần kiểm tra DB, khởi động backend và đăng nhập ở mục 3, sau đó chạy từ thư mục gốc repo:

```powershell
cd D:\3-SoulSpace
python -X utf8 semantic-search/experiment/runner/02-run-experiment.py --method both --repeats 3
```

Chế độ `both` chạy keyword rồi semantic, lưu vào hai thư mục riêng có cùng mã thời gian để đối chiếu. Nếu bước semantic lỗi, kết quả keyword đã hoàn tất vẫn được giữ. Số yêu cầu tìm kiếm ngữ nghĩa vẫn là 45 với 3 lượt.

Sau khi hoàn tất, tính metrics riêng cho từng phương pháp:

```powershell
python -X utf8 semantic-search/experiment/runner/03-evaluate.py "semantic-search/experiment/results/keyword-search/<ma-luot-chay>"
python -X utf8 semantic-search/experiment/runner/03-evaluate.py "semantic-search/experiment/results/semantic-search/<ma-luot-chay>"
```

## 5. Nơi lưu kết quả và file của mỗi lượt chạy

```text
semantic-search/experiment/
  data/       # Corpus, queries và ground truth đã chốt
  runner/     # 01-check-corpus.py, 02-run-experiment.py, 03-evaluate.py và thư viện chung
  label-comparison/  # Kết quả đối chiếu nhãn cũ, không phải đầu vào chạy thực nghiệm
  test_*.py   # Unit tests, không phải các bước thực nghiệm
  results/
    keyword-search/
      <ma-luot-chay>/
    semantic-search/
      <ma-luot-chay>/
```

Mỗi phương pháp chỉ lưu results.csv, timings.csv và run-info.json riêng; dữ liệu đầu vào dùng chung trong data/. Chế độ `both` dùng cùng mã lượt chạy ở hai nhánh, không tạo thư mục `both` hay gộp hai phương pháp vào một file.

`--output` nếu được truyền là **thư mục gốc kết quả thay cho results**, script vẫn tự tạo `keyword-search/<ma-luot>` và `semantic-search/<ma-luot>` bên trong. Mỗi lượt có thư mục mới, không ghi đè kết quả cũ.

Các kết quả keyword có sẵn đã được chuyển từ `runs/` sang `results/keyword-search/`, giữ nguyên nội dung.


| File | Nội dung |
|---|---|
| `results.csv` | `group,query_id,query,method,rank,blog_id,content,score,label`; kèm câu hỏi, toàn bộ nội dung bài và nhãn chốt để đọc trực tiếp. Tối đa top 10 mỗi câu/phương pháp, chất lượng lấy từ lượt ngữ nghĩa thứ nhất. |
| `timings.csv` | Mỗi câu/lượt có thời gian và trạng thái API; ghi lỗi, không đổi lỗi thành kết quả rỗng. |
| `run-info.json` | Phương pháp, số lượt, seed, cấu hình API, hash đầu vào/kết quả và `complete`. |

Lượt lỗi/bị ngắt có `complete: false`, không được tính metrics. Khắc phục nguyên nhân rồi chạy lượt mới; giữ lại dấu vết lỗi. Không chọn lần có chất lượng tốt nhất trong các lần chạy.

Lượt chạy phải có run-info.json với hash đầu vào khớp data/; không trộn kết quả với bộ dữ liệu khác. Hai phương pháp có thang điểm riêng, không so trực tiếp cột score.

Trong `results.csv`: `rank` là thứ hạng tìm kiếm, `score` là điểm của phương pháp, `label` là nhãn 0/1/2 tra từ ground truth đã chốt trong data/. Các cột query/content được lấy đầy đủ từ dữ liệu đã kiểm tra, giữ nguyên nội dung và dấu tiếng Việt.

## 6. Tính metrics bằng dữ liệu dùng chung trong data/

Thay `<ma-luot-chay>` bằng tên thư mục thực tế script vừa in. Chỉ chạy lệnh cho phương pháp đã hoàn tất; với `both`, đánh giá riêng cả hai thư mục:

```powershell
python -X utf8 semantic-search/experiment/runner/03-evaluate.py "semantic-search/experiment/results/keyword-search/<ma-luot-chay>"
python -X utf8 semantic-search/experiment/runner/03-evaluate.py "semantic-search/experiment/results/semantic-search/<ma-luot-chay>"
```

Mặc định evaluator đọc corpus, queries, metadata và nhãn **từ experiment/data/**. Không sao chép các file này vào từng thư mục kết quả. Hash được kiểm tra trước/sau khi tìm kiếm và khi tính metrics; nếu thay đổi bộ dữ liệu, cần chốt bộ mới và chạy lại. Có thể chỉ định thư mục dữ liệu khác bằng `--dataset-dir`, nhưng phải khớp hash của lượt chạy.

Kết quả được xuất vào `evaluation-<timestamp>/` mới:

- `metrics-per-query.csv`: P@5, Recall@5, RR@10, nDCG@10, số bài liên quan toàn corpus và số bài trả về cho từng query/phương pháp.
- `metrics-summary.csv`: trung bình theo A/B/C/D/E và ALL, mỗi query cùng trọng số; MRR là trung bình RR.
- `latency-summary.csv`: số yêu cầu, thành công/lỗi, p50/p95 API theo từng nhóm và toàn bộ.
- `summary.md`: bảng để đọc và đưa vào báo cáo.
- `evaluation-info.json`: hash đầu vào đánh giá.

### Quy ước chỉ số

Nhãn: **0 = không liên quan; 1 = liên quan một phần; 2 = liên quan rõ ràng**.

Với P@5, Recall@5 và MRR@10, **label >= 1** được tính là liên quan; mỗi bài chỉ tính một lần, không cộng nhãn 2 thành hai bài.

| Chỉ số | Công thức |
|---|---|
| Precision@5 | Số bài nhãn 1/2 trong top 5 / 5; vị trí không trả về tính là không liên quan. |
| Recall@5 | Số bài nhãn 1/2 trong top 5 / tổng bài nhãn 1/2 của query trong toàn bộ 350 bài. |
| MRR@10 | Trung bình `1/r`, với r là vị trí bài nhãn 1/2 đầu tiên trong top 10; không có thì RR = 0. |
| nDCG@10 | `DCG/IDCG`; gain `2^label - 1` lần lượt là 0/1/3; chiết khấu vị trí i bằng `log2(i+1)`. IDCG lấy 10 nhãn cao nhất toàn corpus. |

Không có bài liên quan trong ground truth: Recall và nDCG là N/A (CSV để trống), P@5 và RR = 0. Trung bình Recall/nDCG chỉ dùng các câu hợp lệ và ghi số lượng ở `recall_valid_queries`, `ndcg_valid_queries`.

Báo cáo phải nêu: bộ truy vấn nhỏ, do AI soạn; nhãn theo đa số ba agent theo quyết định người dùng, chưa phải xác minh thủ công mọi cặp; baseline đơn giản; thời gian API phụ thuộc mạng/Jina/DB. Không dùng bộ kiểm thử để chỉnh tham số rồi công bố lại trên chính bộ đó.

## 7. Kiểm tra code không gọi Jina

```powershell
python -X utf8 -m unittest discover -s semantic-search/experiment -p "test_*.py" -v
```

Tests kiểm tra metrics bằng ví dụ tính tay, đủ nhãn toàn corpus, hash dữ liệu dùng chung, nhóm D/E, 45 yêu cầu qua HTTP giả lập, lọc top 50 thành top 10, phản hồi lỗi và dữ liệu DB thiếu/cũ. Không cần truy cập DB hoặc phiên đăng nhập thật.
