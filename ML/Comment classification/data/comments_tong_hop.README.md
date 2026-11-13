# Dữ liệu bình luận tổng hợp sau giảm CLEAN

Ngày 13/11/2026. `comments_tong_hop.csv` là bản đã giảm mẫu CLEAN từ file 37.188 dòng từng được tạo bằng `collection/merge_comments.py`. Các tệp nguồn vẫn còn nguyên.

| Nguồn | Số dòng còn lại |
| --- | ---: |
| `vote/final/comments_merged.final.provisional.csv` | 3.173 |
| `tuanduy_crawl_data_gpt_6_1_tera/comments_merged.clean.labeled.csv` | 615 |
| ViHSD train | 4.563 |
| ViHSD dev | 526 |
| ViHSD test | 1.244 |
| **Tổng** | **10.121** |

Phân bố nhãn: `0=CLEAN`: **3.800**; `1=OFFENSIVE`: **2.405**; `2=HATE`: **3.916**. Tất cả các dòng OFFENSIVE/HATE và tất cả 3.788 dòng từ hai bộ bình luận thu thập được giữ nguyên. Trong 27.624 dòng CLEAN ViHSD ban đầu, giữ ngẫu nhiên 557 dòng và loại 27.067 dòng; nhãn và các cột của dòng giữ lại không thay đổi.

Lấy mẫu theo từng split, gần tỷ lệ CLEAN ban đầu: train 401/19.886, dev 44/2.190 và test 112/5.548. Dùng `random.Random(20261113)` của Python, gọi `sample` lần lượt cho train, dev, test trên danh sách vị trí theo thứ tự gốc; sau đó giữ thứ tự ban đầu trong file. Mã `source_id` dạng `vihsd_<split>:<n>` chỉ thứ tự bản ghi bắt đầu từ 1 trong file ViHSD gốc, là mã tạo khi gộp chứ không phải ID gốc.

**File này đã lấy mẫu cả ViHSD dev và test**, do yêu cầu giảm tổng số CLEAN xuống 3.800. Không dùng các phần dev/test trong file này để báo cáo kết quả so sánh chuẩn ViHSD: dùng các file gốc trong `vihsd/vihsd/` để đánh giá. Khi tạo tập huấn luyện phải loại `vihsd_dev` và `vihsd_test`, và kiểm tra trùng nội dung với tập đánh giá để hạn chế rò rỉ.

Script merge chỉ nối dữ liệu, không xóa trùng hoặc phân xử nhãn. Có nội dung trùng giữa các split và có trường hợp cùng nội dung khác nhãn. Nhãn từ bộ vote là nhãn tổng hợp sơ bộ; bộ bình luận mới do GPT gán và chưa được xác nhận độc lập. Xem README trong từng thư mục nguồn để biết giới hạn và các trường hợp cần rà soát.

SHA-256 hiện tại của `comments_tong_hop.csv`: `5ec49bbf7a4225428d8a56f0335fbfe7290b8a30a292607a1b7be97772b3f956`.
