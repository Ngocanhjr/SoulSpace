# Quy tắc gán nhãn chủ đề bài đăng SoulSpace

**Dữ liệu:** `v2(40k).csv` — 40.000 bài tiếng Việt
**Nhãn:** 15 chủ đề, đa nhãn, nối bằng `|`
**Mục đích:** Gán nhãn thống nhất và hợp nhất nhãn từ nhiều nguồn.

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

## 4. Hợp nhất nhãn từ nhiều nguồn

### Cách đếm

Nhãn là tập hợp, **không so cả ô**. Tách theo `|` rồi đếm phiếu từng chủ đề:

```
vote(t) = số nguồn có t trong ô nhãn của mình   (0..4)
```

`am_thuc|tinh_cam` vs `am_thuc` → `am_thuc:2`, `tinh_cam:1`.

### Ngưỡng

| vote | Xử lý | Cột |
|---:|---|---|
| 4 hoặc 3 | Chốt | `labels_consensus` |
| **2** | **Hòa, cần trọng tài** | `labels_review` |
| 1 | Bỏ (chỉ đối chiếu) | `labels_weak` |
| 0 | Bỏ | — |

### Trạng thái dòng

| status | Điều kiện | Số dòng |
|---|---|---:|
| `CLEAN` | Không có chủ đề nào `vote = 2` | 34.431 |
| `DISPUTE` | Có `vote = 2`, vẫn còn nhãn chốt | 5.102 |
| `EMPTY` | Có `vote = 2`, không còn nhãn chốt nào | 467 |

### Hòa 2/2

Dữ liệu chỉ có dạng **2 chọi 2**, không có 4 nguồn gắn 4 nhãn khác nhau. Model mạnh làm **trọng tài**, không phải phiếu thứ 5:

- `DISPUTE` → giữ `labels_consensus`, hỏi model mạnh *có/không* cho từng nhãn trong `labels_review`.
- `EMPTY` → gắn lại từ đầu, `labels_review` là ứng viên gợi ý.

`labels_final = labels_consensus ∪ (nhãn trọng tài duyệt)`

### Ví dụ thật

**`SS_000095`** — "chúc mừng sinh viên nhé ."

| original | grok | gemini | gpt |
|---|---|---|---|
| cuoc_song | hoc_tap | hoc_tap | cuoc_song |

→ `cuoc_song:2, hoc_tap:2` → `EMPTY`

**`SS_000151`** — "automation test java selenium..."

| original | grok | gemini | gpt |
|---|---|---|---|
| cong_nghe | cong_viec\|cong_nghe | cong_viec\|cong_nghe | cong_nghe |

→ `cong_nghe:4, cong_viec:2` → chốt `cong_nghe`, review `cong_viec`, `DISPUTE`

## 5. Cột đầu ra

| Cột | Nội dung |
|---|---|
| `labels_votes` | `am_thuc:4\|cong_nghe:2` |
| `labels_consensus` | chủ đề `vote ≥ 3` |
| `labels_review` | chủ đề `vote = 2` |
| `labels_weak` | chủ đề `vote = 1` |
| `status` | `CLEAN` / `DISPUTE` / `EMPTY` |
| `needs_strong_model` | `TRUE` cho 5.569 dòng (`DISPUTE` + `EMPTY`) |

## 6. Nguồn

- `thong_ke_15_nhan.csv` — định nghĩa và ranh giới 15 nhãn.
- `tongHopNhan.csv` — 40.000 dòng × 4 nguồn (`labels_original`, `grok-4.7`, `gemini-hydrogen`, `gpt-6.1-sol`).
- Majority vote per-label cho multi-label (Aroyo & Welty, *Crowd Truth*, 2015). Không cần Dawid-Skene vì không nguồn nào lệch hẳn (Jaccard cặp 0,816–0,851).
