# Quy tắc gán nhãn bộ dữ liệu UIT-ViHSD

**Bộ dữ liệu:** UIT-ViHSD — Vietnamese Hate Speech Detection  
**Nhãn:** `CLEAN = 0`, `OFFENSIVE = 1`, `HATE = 2`  
**Mục đích:** Hướng dẫn tham khảo khi gán nhãn bình luận tiếng Việt trên mạng xã hội.

> **Lưu ý về nguồn:** Tài liệu này diễn giải quy tắc từ bài báo gốc của UIT-ViHSD (mục *Annotation Guidelines*). Các ví dụ dưới đây là **ví dụ minh họa tự xây dựng**, không phải trích nguyên văn từ bộ dữ liệu hoặc bài báo. Với bình luận không đủ ngữ cảnh, cần đánh dấu để rà soát thủ công.

## 1. Định nghĩa ba nhãn

| Nhãn | Mã | Quy tắc nhận diện |
|---|---:|---|
| **CLEAN** | **0** | Bình luận không mang tính xúc phạm, quấy rối, miệt thị hay công kích. Có thể chứa nhận xét hoặc đánh giá tiêu cực hợp lý. |
| **OFFENSIVE** | **1** | Có ngôn từ thô tục, thiếu lịch sự hoặc mang tính xúc phạm nhưng **không hướng đến một cá nhân/nhóm người cụ thể**. |
| **HATE** | **2** | Nội dung mang tính xúc phạm, công kích, hạ nhục, quấy rối hoặc kỳ thị **một cá nhân hay nhóm người cụ thể**, trực tiếp hoặc hàm ý. |

**Điểm phân biệt trọng tâm:** Trong cách phân loại của ViHSD, **HATE không chỉ giới hạn ở phát ngôn kỳ thị một nhóm người được bảo vệ**; công kích một **cá nhân cụ thể** cũng có thể thuộc HATE.

## 2. Hướng dẫn theo từng nhãn

### 2.1. CLEAN (0)

Gán `CLEAN` khi nội dung là trao đổi, cảm thán, nêu quan điểm hoặc phê bình nhưng **không** xúc phạm hay công kích người khác.

Ví dụ:

- “Tôi không đồng ý với quan điểm này.” → `CLEAN`
- “Bộ phim này khá nhàm chán.” → `CLEAN`
- “Anh ấy đã đưa ra quyết định sai.” → `CLEAN`

**Chú ý:** Ý kiến tiêu cực không tự động được xem là `OFFENSIVE` hay `HATE`.

### 2.2. OFFENSIVE (1)

Gán `OFFENSIVE` khi có ngôn từ tục tĩu, thô lỗ, xúc phạm hoặc quấy rối nhưng **không xác định được đối tượng cụ thể đang bị công kích**.

Ví dụ:

- “Đm, bực thật.” → `OFFENSIVE`
- “Vãi thật, chán không chịu nổi.” → `OFFENSIVE`
- “Vcl, trận này chán thật.” → `OFFENSIVE`

**Chú ý:** Việc xuất hiện từ tục không tự động dẫn đến nhãn `HATE`. Cần xem từ ngữ có đang được dùng để công kích một người/nhóm xác định hay không.

### 2.3. HATE (2)

Gán `HATE` khi nội dung mang tính công kích, miệt thị, hạ nhục, quấy rối hoặc kỳ thị **người/nhóm người cụ thể**, kể cả khi không dùng từ tục.

Ví dụ:

- “Mày đúng là đồ vô dụng, chẳng làm được việc gì.” → `HATE`
- “Thằng đó ngu như bò.” → `HATE`
- “Đám người đó chỉ biết ăn bám, chẳng được tích sự gì.” → `HATE`

**Chú ý:** Có thể xác định đối tượng qua ngôn từ trực tiếp, đại từ, người được nhắc đến hoặc ngữ cảnh. Những câu mỉa mai, ám chỉ cần được xem xét dựa trên ngữ cảnh thực tế.

## 3. Bảng ví dụ đối chiếu

| Bình luận | Nhãn | Giải thích |
|---|---|---|
| “Bài hát này không hay.” | `CLEAN` | Đánh giá tiêu cực thông thường, không công kích người. |
| “Đm, tức quá!” | `OFFENSIVE` | Có từ tục nhưng không hướng đến ai. |
| “Mày đúng là đồ ngu.” | `HATE` | Xúc phạm trực tiếp một cá nhân. |
| “Tôi không thích cách anh ấy làm việc.” | `CLEAN` | Phê bình hành vi, không miệt thị. |
| “Đám đó toàn lũ vô dụng.” | `HATE` | Miệt thị một nhóm người cụ thể. |
| “Vcl, trận này chán thật.” | `OFFENSIVE` | Cảm thán bằng từ tục, không công kích người. |
| “Anh ấy đã đưa ra quyết định sai.” | `CLEAN` | Nhận xét về quyết định, không xúc phạm. |

## 4. Nguyên tắc xử lý trường hợp dễ nhầm lẫn

1. **Từ tục ≠ HATE:** Cảm thán bằng từ tục mà không công kích ai thường thuộc `OFFENSIVE`.
2. **Không có từ tục vẫn có thể là HATE:** Những câu hạ nhục, miệt thị, kỳ thị hoặc công kích một người/nhóm có thể thuộc `HATE`.
3. **Có nhắc đến người khác ≠ HATE:** Một nhận xét/phê bình về hành vi hoặc quan điểm mà không xúc phạm có thể là `CLEAN`.
4. **Ngữ cảnh quyết định ý nghĩa:** Xem xét đại từ, người được trả lời, từ lóng, viết tắt, mỉa mai, ẩn ý và ngữ cảnh xã hội.
5. **Không tự suy diễn mục tiêu:** Nếu không đủ thông tin để nhận định ai bị công kích hoặc câu mang tính xúc phạm hay không, đưa vào nhóm **cần rà soát**, không tự ép nhãn.

## 5. Quy trình gán nhãn đề xuất

1. Đọc toàn bộ bình luận, xét ngữ cảnh nếu có.
2. **Không có** nội dung xúc phạm, quấy rối, miệt thị, kỳ thị hoặc từ tục mang tính xúc phạm → `CLEAN (0)`.
3. **Có** nội dung như trên, nhưng **không có đối tượng bị công kích cụ thể** → `OFFENSIVE (1)`.
4. **Có** nội dung công kích/miệt thị/quấy rối/kỳ thị **một cá nhân hoặc nhóm người cụ thể** → `HATE (2)`.
5. Nếu thông tin mơ hồ, lưu `NEEDS_REVIEW` như **trạng thái rà soát nội bộ**, **không phải nhãn thứ tư của ViHSD**.

## 6. Nguồn tham khảo

- Luu, S. T., Nguyen, K. V., & Nguyen, N. L.-T. (2021). *A Large-scale Dataset for Hate Speech Detection on Vietnamese Social Media Texts*. [arXiv:2103.11528](https://arxiv.org/abs/2103.11528). Xem mục **Annotation Guidelines**, đặc biệt **Table 1**.
- Bộ dữ liệu [UIT-ViHSD trên Hugging Face](https://huggingface.co/datasets/uitnlp/vihsd).

---

**Ghi chú:** Đây là bản hướng dẫn thực hành được diễn giải lại từ nguồn nghiên cứu và các ví dụ minh họa, không phải bản sao nguyên văn của tài liệu gán nhãn gốc.
