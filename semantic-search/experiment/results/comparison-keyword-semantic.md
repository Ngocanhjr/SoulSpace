# So sánh tìm kiếm keyword và semantic

Phạm vi: **350 bài, 15 truy vấn, 5.250 nhãn ground truth 0/1/2**. Đã xác minh hai lượt có cùng hash corpus, queries và ground truth; cả hai hoàn tất hợp lệ. Tính lại metrics từ kết quả gốc khớp các file đánh giá được cung cấp.

## Kết quả tổng thể

| Chỉ số | Keyword | Semantic | Chênh lệch semantic − keyword |
|---|---:|---:|---:|
| Precision@5 | 0.1733 | 0.4267 | +0.2533 |
| Recall@5 | 0.2501 | 0.6121 | +0.3621 |
| MRR@10 | 0.6389 | 0.9000 | +0.2611 |
| nDCG@10 | 0.4598 | 0.7680 | +0.3082 |

Các chỉ số nằm trên thang 0–1; cao hơn là tốt hơn. Chênh lệch là hiệu tuyệt đối trên cùng thang đo, không phải phần trăm tăng tương đối.

## Kết quả theo nhóm

| Nhóm | Phương pháp | Số query | Precision@5 | Recall@5 | MRR@10 | nDCG@10 |
|---|---|---:|---:|---:|---:|---:|
| A — Trùng từ khóa | Keyword | 3 | 0.2000 | 0.4444 | 1.0000 | 0.8235 |
| A — Trùng từ khóa | Semantic | 3 | 0.3333 | 0.7778 | 0.8333 | 0.7970 |
| B — Đồng nghĩa / diễn đạt lại | Keyword | 3 | 0.1333 | 0.1587 | 0.4167 | 0.0939 |
| B — Đồng nghĩa / diễn đạt lại | Semantic | 3 | 0.4000 | 0.4524 | 0.6667 | 0.4938 |
| C — Ngôn ngữ tự nhiên | Keyword | 3 | 0.2000 | 0.2194 | 0.6111 | 0.4864 |
| C — Ngôn ngữ tự nhiên | Semantic | 3 | 0.6000 | 0.6139 | 1.0000 | 0.8316 |
| D — Mô tả | Keyword | 3 | 0.1333 | 0.1500 | 0.5556 | 0.4236 |
| D — Mô tả | Semantic | 3 | 0.3333 | 0.5500 | 1.0000 | 0.8178 |
| E — Nhiều ý | Keyword | 3 | 0.2000 | 0.2778 | 0.6111 | 0.4717 |
| E — Nhiều ý | Semantic | 3 | 0.4667 | 0.6667 | 1.0000 | 0.9000 |
| ALL — Toàn bộ | Keyword | 15 | 0.1733 | 0.2501 | 0.6389 | 0.4598 |
| ALL — Toàn bộ | Semantic | 15 | 0.4267 | 0.6121 | 0.9000 | 0.7680 |

## Số query semantic tốt hơn, bằng hoặc kém keyword

| Chỉ số | Tốt hơn | Bằng | Kém hơn |
|---|---:|---:|---:|
| Precision@5 | 13 | 2 | 0 |
| Recall@5 | 13 | 2 | 0 |
| MRR@10 | 8 | 5 | 2 |
| nDCG@10 | 14 | 0 | 1 |

## Nhận xét

- Semantic đạt trung bình cao hơn keyword ở cả bốn metrics trên 15 query.
- Ở các nhóm B, C, D và E, semantic có trung bình cao hơn ở cả bốn metrics.
- Nhóm A (trùng từ khóa): semantic có Precision@5 và Recall@5 cao hơn; keyword có MRR@10 = 1.0000 so với 0.8333 và nDCG@10 = 0.8235 so với 0.7970. Semantic không tốt hơn ở mọi tình huống.
- Q01: hai phương pháp bằng P@5 và Recall@5; keyword tốt hơn về RR@10 (1.0000 so với 0.5000) và nDCG@10 (0.7262 so với 0.4582).
- Q05: semantic tốt hơn về P@5, Recall@5 và nDCG@10 nhưng RR@10 thấp hơn (0.5000 so với 1.0000).
- Q06 vẫn là câu khó: semantic tìm được một bài liên quan trong top 5 (P@5 = 0.2000, Recall@5 = 0.0714); keyword chưa tìm được bài liên quan trong top 10.

## Thời gian của semantic

- 45/45 yêu cầu thành công, 0 lỗi; 15 query × 3 lượt.
- p50 = 1450.940 ms (khoảng 1.45 giây).
- p95 = 3130.878 ms (khoảng 3.13 giây).
- Thời gian đo là API lấy tối đa 50 ứng viên, gồm mạng, Jina và DB; kết quả chất lượng dùng lượt đầu, lọc đúng 350 bài rồi lấy top 10.
- Keyword không có số đo thời gian trong bộ kết quả này; không lập kết luận phương pháp nào nhanh hơn.

## Quy ước và giới hạn

- Precision/Recall/MRR: nhãn 1 hoặc 2 đều được tính là liên quan. nDCG dùng gain 2^label − 1 (0/1/3).
- Recall dùng toàn corpus; nDCG dùng danh sách lý tưởng lấy từ toàn corpus. Tất cả 15 query đều hợp lệ cho cả bốn metrics.
- Trung bình theo nhóm và toàn bộ là trung bình không trọng số từng query; mỗi nhóm có 3 câu.
- Đây là kết quả trên bộ 15 query do AI soạn và nhãn đã chốt theo đa số agent. Chưa kiểm định ý nghĩa thống kê; không suy rộng thành kết luận cho mọi dữ liệu.

## Nguồn kết quả

- keyword: [metrics-summary.csv](keyword-search/20261116T070710512518Z/evaluation-20261116T072039824838Z/metrics-summary.csv), [metrics-per-query.csv](keyword-search/20261116T070710512518Z/evaluation-20261116T072039824838Z/metrics-per-query.csv).
- semantic: [metrics-summary.csv](semantic-search/20261116T073314558286Z/evaluation-20261116T080617598540Z/metrics-summary.csv), [metrics-per-query.csv](semantic-search/20261116T073314558286Z/evaluation-20261116T080617598540Z/metrics-per-query.csv).

## Đối chiếu từng query (Keyword / Semantic)

| Query | Nhóm | Precision@5 | Recall@5 | RR@10 | nDCG@10 |
|---|---|---:|---:|---:|---:|
| Q01 | A | 0.2000 / 0.2000 | 0.3333 / 0.3333 | 1.0000 / 0.5000 | 0.7262 / 0.4582 |
| Q02 | A | 0.2000 / 0.4000 | 0.5000 / 1.0000 | 1.0000 / 1.0000 | 0.9180 / 1.0000 |
| Q03 | A | 0.2000 / 0.4000 | 0.5000 / 1.0000 | 1.0000 / 1.0000 | 0.8262 / 0.9328 |
| Q04 | B | 0.2000 / 0.6000 | 0.3333 / 1.0000 | 0.2500 / 1.0000 | 0.1043 / 0.9515 |
| Q05 | B | 0.2000 / 0.4000 | 0.1429 / 0.2857 | 1.0000 / 0.5000 | 0.1774 / 0.4121 |
| Q06 | B | 0.0000 / 0.2000 | 0.0000 / 0.0714 | 0.0000 / 0.5000 | 0.0000 / 0.1179 |
| Q07 | C | 0.2000 / 0.4000 | 0.3333 / 0.6667 | 0.3333 / 1.0000 | 0.3631 / 0.8790 |
| Q08 | C | 0.2000 / 0.6000 | 0.1250 / 0.3750 | 0.5000 / 1.0000 | 0.4225 / 0.6939 |
| Q09 | C | 0.2000 / 0.8000 | 0.2000 / 0.8000 | 1.0000 / 1.0000 | 0.6736 / 0.9218 |
| Q10 | D | 0.2000 / 0.2000 | 0.2500 / 0.2500 | 1.0000 / 1.0000 | 0.6577 / 0.7237 |
| Q11 | D | 0.2000 / 0.4000 | 0.2000 / 0.4000 | 0.5000 / 1.0000 | 0.3188 / 0.7657 |
| Q12 | D | 0.0000 / 0.4000 | 0.0000 / 1.0000 | 0.1667 / 1.0000 | 0.2943 / 0.9639 |
| Q13 | E | 0.2000 / 0.4000 | 0.2500 / 0.5000 | 0.5000 / 1.0000 | 0.3250 / 0.9014 |
| Q14 | E | 0.2000 / 0.4000 | 0.2500 / 0.5000 | 1.0000 / 1.0000 | 0.7268 / 0.8154 |
| Q15 | E | 0.2000 / 0.6000 | 0.3333 / 1.0000 | 0.3333 / 1.0000 | 0.3631 / 0.9832 |
