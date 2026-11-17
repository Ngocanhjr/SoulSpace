# Quy tắc gán nhãn chủ đề bài đăng SoulSpace

**Dữ liệu:** `v2(40k).csv` — 40.000 bài tiếng Việt, gắn nhãn bởi 4 nguồn
**Nhãn:** 15 chủ đề, đa nhãn, nối bằng `|`
**Mục đích:** Gán nhãn thống nhất và hợp nhất nhãn từ nhiều nguồn.

**Các file trong pipeline**

| Bước | File | Trạng thái |
|---|---|---|
| Thô | `0_raw/chua_gan_nhan.csv` | 120.000 bài chưa gán nhãn |
| Từng nguồn | `1_model_output/<nguồn>/` | Nhãn riêng của chatweb, chatgpt, gemini, grok, claude |
| Gốc | `2_tong_hop/tongHopNhan_goc.csv` | 6 cột nhãn từ 4 nguồn, chưa hợp nhất |
| Rule | `2_tong_hop/tongHopNhan_rule.csv` | Đã hợp nhất bằng rule ở mục 4 |
| AI | `2_tong_hop/tongHopNhan_rule_ai.csv` | Sau khi LLM xử lý các dòng `AI xác định` (mục 6) |
| Final | `3_final/tongHopNhan_final.csv` | 39.995 dòng có nhãn, bỏ 5 dòng rác |
| Báo cáo | `4_bao_cao/` | Thống kê nhãn qua từng giai đoạn |


## 1. 15 chủ đề

| STT | Nhãn | Phạm vi | Ví dụ |
|---:|---|---|---|
| 1 | `hoc_tap` | Học tập, thi cử, khóa học, học bổng, du học, khóa luận, thực tập tín chỉ. | "ra trường rồi nhưng gặp thầy cô hành chính vẫn rén ngang" → `hoc_tap` |
| 2 | `cong_viec` | Nghề nghiệp, tuyển dụng, kỹ năng làm việc, đồng nghiệp, lương thưởng, quyền lợi lao động. | "thi đỗ viên chức của viện hay nộp lệ phí để thực tập lấy chứng chỉ hành nghề" → `cong_viec` |
| 3 | `tai_chinh` | Tiền, thu nhập, chi tiêu, tiết kiệm, ngân hàng, vay nợ, đầu tư, thuế, bảo hiểm. | "bạn định vay 10 học phí vinuni à" → `tai_chinh` (+ `hoc_tap`) |
| 4 | `cong_nghe` | Thiết bị, linh kiện, phần mềm, AI, Internet, cấu hình, sửa lỗi, bảo mật, tài khoản số. | "automation test java selenium, viết unit test pattern" → `cong_nghe` (+ `cong_viec`) |
| 5 | `the_thao` | Thể thao, tập luyện, gym, vận động viên, giải đấu, esports. | "tập vừa sức thôi đừng tập quá sức… vừa tập gym vừa đi tập võ" → `the_thao` |
| 6 | `du_lich` | Điểm đến, nghỉ dưỡng, phượt, lịch trình, trải nghiệm chuyến đi, lưu trú du lịch. | "đi phượt từ q9 sang q7" → `du_lich` |
| 7 | `am_thuc` | Món ăn, đồ uống, nguyên liệu, công thức, hương vị, quán ăn, nhà hàng. | "sáng nhịn trưa nhịn tối ăn lẩu hay nướng gì thì tùy" → `am_thuc` |
| 8 | `mua_sam` | Chọn mua, giá, nơi bán, ưu đãi, đặt hàng, giao nhận, đổi trả, bảo hành. | "mình pass mé với ạ" (bán lại đồ) → `mua_sam` |
| 9 | `suc_khoe` | Sức khỏe thể chất/tinh thần, bệnh, thuốc, khám chữa bệnh, dinh dưỡng, giấc ngủ, hỗ trợ tâm lý. | "trải nghiệm thất vọng trong vai trò người nhà bệnh nhân… đưa người nhà đi khám" → `suc_khoe` |
| 10 | `lam_dep` | Da, tóc, trang điểm, mỹ phẩm, thời trang, phối đồ, thẩm mỹ. | "thuốc nhuộm tóc" → `lam_dep` |
| 11 | `giai_tri` | Phim, nhạc, truyện, chương trình, game, concert, nghệ sĩ, fandom, hài. | câu hài, thơ vui về trường lớp → `giai_tri` |
| 12 | `gia_dinh` | Cha mẹ, con cái, anh chị em, họ hàng, hôn nhân, nuôi dạy con. | "thành quả đáng nể của con gái mẹ, chúc mừng gia đình" → `gia_dinh` |
| 13 | `tinh_cam` | Yêu đương, crush, hẹn hò, chia tay, người yêu cũ, tình bạn. | "nói vầy chứ đã có người yêu đâu mà chia" → `tinh_cam` |
| 14 | `xa_hoi` | Cộng đồng, pháp luật, chính trị, an sinh, môi trường, thiên tai, thiện nguyện, văn hóa. | "phòng vụ cháy ccmn đêm khương hạ", chia buồn nạn nhân → `xa_hoi` |
| 15 | `cuoc_song` | Sinh hoạt, việc nhà, lời chúc, phòng trọ, nhà ở, xe cộ, thú cưng, thú vui cá nhân. | "không đăng kí kí túc xá muộn… thuê trọ… sợ gầm cầu" → `cuoc_song` |

## 2. Ranh giới dễ nhầm

| Chủ đề | Không gán khi | Ví dụ |
|---|---|---|
| `hoc_tap` | Chỉ nhắc "trường", "sinh viên". | "chúc mừng sinh viên nhé" → `cuoc_song`, không phải `hoc_tap` |
| `cong_viec` | Có giá/lương nhưng không bàn về nghề nghiệp. | "tìm phòng trọ giá 3 triệu" → `cuoc_song` + `mua_sam`, không có `cong_viec` |
| `tai_chinh` | Chỉ có giá hoặc mức lương. | "lẩu 200k/người" → `am_thuc`, không thêm `tai_chinh` |
| `cong_nghe` | Chỉ nhắc nền tảng đăng bài. | "bán iPhone 15 còn bảo hành" → `mua_sam`, không có `cong_nghe` |
| `the_thao` | Chơi game thông thường. | "cày Liên Quân cả đêm" → `giai_tri`, không phải `the_thao` |
| `du_lich` | Địa danh chỉ là bối cảnh. | "ở Đà Nẵng trọ 3 triệu/tháng" → `cuoc_song`, không phải `du_lich` |
| `am_thuc` | Giá món ăn không tạo nhãn tiền. | "bị ngộ độc sau khi ăn quán đó" → `suc_khoe`, thêm `am_thuc` nếu có phần ăn uống rõ |
| `mua_sam` | Review thuần trải nghiệm sử dụng. | "dùng máy này 2 năm vẫn mượt, pin trâu" → `cong_nghe`, không thêm `mua_sam` |
| `suc_khoe` | Suy bệnh từ tiếng lóng, buồn vui thường ngày. | "hôm nay mệt quá" → `cuoc_song`; "đau dạ dày 3 ngày, không ngủ được" → `suc_khoe` |
| `lam_dep` | Chỉ sale mỹ phẩm. | "sale serum 50%" → `mua_sam`; "serum này trị mụn 3 ngày hết" → thêm `lam_dep` |
| `giai_tri` | Chỉ nhắc người nổi tiếng. | "Sơn Tùng bị phạt thuế" → `xa_hoi` + `tai_chinh`, không phải `giai_tri` |
| `gia_dinh` | Chỉ nhắc người thân làm nhân vật. | "mẹ bảo phải lấy chồng" → `gia_dinh`; "crush không rep tin nhắn" → `tinh_cam` |
| `tinh_cam` | Vợ chồng/họ hàng, đồng nghiệp công việc. | "vợ chồng cãi nhau chuyện tiền" → `gia_dinh` + `tai_chinh`, không có `tinh_cam` |
| `xa_hoi` | Tin tức mặc định. | "đi khám BHYT được hoàn tiền" → `suc_khoe` + `xa_hoi` (bàn cả hai) |
| `cuoc_song` | **Bài khó hiểu** — không dùng làm nhãn mặc định. | bài không rõ chủ đề → để trống, không gán `cuoc_song` |

## 3. Quy tắc gán

1. **Đa nhãn**, không bắt buộc nhãn chính, không giới hạn số nhãn.<br>Ví dụ: "đi du lịch Đà Nẵng ăn hải sản" → `du_lich|am_thuc`
2. Nối bằng `|` **theo thứ tự STT** ở mục 1.<br>Ví dụ: `am_thuc|gia_dinh` ✅ — `gia_dinh|am_thuc` ❌
3. Chủ đề phải được **bàn rõ** trong bài — không gán vì có từ khóa, bối cảnh hay giọng điệu.<br>Ví dụ: "trường tôi mới xây" (nhắc trường, không bàn học) → không gán `hoc_tap`
4. Thiếu ngữ cảnh hoặc ngoài 15 chủ đề → **để `labels` trống**, không ép `cuoc_song`.<br>Ví dụ: "ok", "haha", "ừ đúng rồi" → `labels` trống
5. Chỉ dùng đúng 15 nhãn, không tự thêm.<br>Ví dụ: ❌ `kinh_te`, ❌ `the_thao|game` — game đã nằm trong `giai_tri`

## 4. Hợp nhất nhãn từ 4 nguồn

4 nguồn: `labels_original`, `grok-4.7`, `gemini-hydrogen`, `gpt-6.1-sol`.

### Bước 1 — Đếm phiếu từng chủ đề

Tách ô nhãn theo `|`, đếm xem **mỗi chủ đề** được bao nhiêu nguồn chọn (0–4). Không so nguyên cả ô.

> `am_thuc|tinh_cam` và `am_thuc` → `am_thuc` 2 phiếu, `tinh_cam` 1 phiếu.

### Bước 2 — Xếp chủ đề theo số phiếu

| Số phiếu | Kết quả | Ghi vào |
|---:|---|---|
| 3 – 4 | **Chốt** | `labels` |
| 2 | **Treo** — hòa, chưa quyết | `ghi_chu` |
| 0 – 1 | Bỏ | — |

### Bước 3 — Ghi ai quyết định

| `quyet_dinh` | Khi nào | Số dòng |
|---|---|---:|
| `rule` | Không có chủ đề nào treo → dòng đã xong | 34.431 |
| `AI xác định` | Còn ít nhất 1 chủ đề treo → cần LLM quyết | 5.569 |

`AI xác định` **không có nghĩa là `labels` rỗng**:
- Có chủ đề chốt + có chủ đề treo → `labels` có giá trị (5.102 dòng).
- Không chủ đề nào chốt → `labels` rỗng (467 dòng).

### Ví dụ

| ID | original | grok | gemini | gpt | Đếm phiếu | `labels` | `quyet_dinh` | `ghi_chu` |
|---|---|---|---|---|---|---|---|---|
| SS_000016 | cuoc_song | cuoc_song | cuoc_song | cuoc_song | cuoc_song:4 | `cuoc_song` | `rule` | |
| SS_000114 | cong_viec | hoc_tap | hoc_tap\|cong_viec | cong_viec | cong_viec:3, hoc_tap:2 | `cong_viec` | `AI xác định` | `hoc_tap` |
| SS_000095 | cuoc_song | hoc_tap | hoc_tap | cuoc_song | cuoc_song:2, hoc_tap:2 | *(rỗng)* | `AI xác định` | `cuoc_song\|hoc_tap` |

- **SS_000016** — 4/4 đồng ý → xong.
- **SS_000114** — `cong_viec` đủ 3 phiếu nên chốt; `hoc_tap` hòa 2/2 nên treo.
- **SS_000095** — hòa 2 chọi 2, không chủ đề nào chốt được.

## 5. Các cột trong `tongHopNhan_rule.csv`

| Cột | Ý nghĩa | Ai điền |
|---|---|---|
| `id` | Mã bài | Gốc, không sửa |
| `content` | Nội dung bài | Gốc, không sửa |
| `labels_original` | Nhãn của bộ dữ liệu gốc | Gốc, không sửa |
| `grok-4.7` | Nhãn Grok gắn | Model, không sửa |
| `gemini-hydrogen` | Nhãn Gemini gắn | Model, không sửa |
| `gpt-6.1-sol` | Nhãn GPT gắn | Model, không sửa |
| `labels` | Nhãn chốt (≥3 phiếu) — **nhãn dùng để train** | Rule (mục 4) |
| `quyet_dinh` | `rule` hoặc `AI xác định` | Rule (mục 4) |
| `ghi_chu` | Chủ đề đang treo (2 phiếu), chỉ có ở dòng `AI xác định` | Rule (mục 4) |

## 6. Việc của LLM với dòng `AI xác định`

Chỉ xử lý 5.569 dòng có `quyet_dinh = AI xác định`. LLM là **trọng tài**, không phải phiếu thứ 5.

1. Đọc `content`, áp quy tắc mục 1–3.
2. Với từng chủ đề trong `ghi_chu`: quyết **có** hoặc **không**.
3. Chủ đề **có** → thêm vào `labels` (giữ thứ tự STT). Giữ nguyên các nhãn đã chốt sẵn trong `labels`.
4. Nếu sau cùng `labels` vẫn rỗng và bài không thuộc chủ đề nào → để rỗng, **không ép** `cuoc_song`.

> SS_000114: LLM chỉ trả lời "bài có bàn rõ `hoc_tap` không?". `cong_viec` đã chốt, không hỏi lại.

Kết quả ghi ra **`tongHopNhan_rule_ai.csv`** — giữ nguyên 9 cột, cập nhật `labels`, đổi `quyet_dinh` thành `rule + AI` cho các dòng đã xử lý. File `tongHopNhan_rule.csv` **không sửa**.

## 7. Nguồn

- `thong_ke_15_nhan.csv` — định nghĩa và ranh giới 15 chủ đề.
- Majority vote theo từng nhãn cho dữ liệu đa nhãn (Aroyo & Welty, *Crowd Truth*, 2015). Các nguồn đồng thuận khá cao (Jaccard từng cặp 0,816–0,851) nên không cần mô hình trọng số annotator như Dawid-Skene.
