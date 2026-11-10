# Tổng hợp vote và phân xử sơ bộ

Ngày: 10/11/2026. Áp dụng ánh xạ ViHSD trong `../../../docs/README.md`: 0 CLEAN, 1 OFFENSIVE, 2 HATE. Đây là nhãn tổng hợp AI, chưa được người gán nhãn độc lập xác nhận; không coi là gold label.

Ba nguồn là `codex/comments_merged.clean.labeled.csv`, `claude/comments_merged_labeled.csv` và `chatweb/comments_merged.vihsd_annotated_draft.csv`. Chatweb là kết quả mô hình ViHSD cộng luật, không phải một người gán nhãn. Đã kiểm tra toàn bộ các trường gốc ngoài `label_id` ở cả ba nguồn khớp đầu vào theo đúng thứ tự.

- 2.702 dòng: ba nguồn cùng nhãn, giữ nhãn đó.
- 430 dòng: hai nguồn cùng nhãn, lấy đa số; chưa được người thực hiện lượt này đọc phân xử riêng.
- 41 dòng: ba nguồn chia ba nhãn; người thực hiện lượt này đọc bình luận và ngữ cảnh có sẵn, ghi vote và lý do ngắn trong audit. Vote này quyết định nhãn cuối cho các dòng chia ba.

Không tạo vote giả cho các dòng chưa đọc: `vote_current_reviewer` để trống ở 3.132 dòng còn lại. `needs_review=YES` đánh dấu tất cả 471 dòng có bất đồng ban đầu, kể cả các dòng đã phân xử, để ưu tiên người kiểm tra; `NO` chỉ nghĩa là ba nguồn đồng thuận, không bảo đảm nhãn đúng. Phân xử một số câu ngắn, biệt danh, ý mỉa và ngữ cảnh thiếu vẫn có thể khác nhau giữa người gán nhãn.

Kết quả: CLEAN 2.831, OFFENSIVE 109, HATE 233; tổng 3.173.

- `comments_merged.final.provisional.csv`: đầy đủ cột và thứ tự đầu vào, chỉ điền `label_id`.
- `comments_merged.vote_audit.csv`: ba vote, vote của người thực hiện lượt này nếu có, phương pháp chốt và lý do cho các dòng chia ba.
- `comments_merged.disagreements.csv`: 471 dòng có bất đồng để rà soát.
- `build_vote.py`: kiểm tra tính khớp và tái tạo ba CSV.

SHA-256 hiện tại của `codex/comments_merged.clean.csv` và bản `data/processed/comments_merged.clean.csv`: `cbdc13cefea41288d1091611f41817e9d26e088a2580aaa773f2aa1475a22f5b`. Mã này khác mã ghi trong `codex/comments_merged.clean.annotation.md`; chưa xác định được nguyên nhân khác biệt byte. Việc đối chiếu nội dung và các trường gốc của ba bộ nhãn hiện có đã thành công, nhưng không thể xác nhận mã băm lịch sử đó.
