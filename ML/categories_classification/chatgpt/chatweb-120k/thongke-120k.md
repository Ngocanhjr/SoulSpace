# Thống kê dữ liệu chatweb-120k

Nguồn: [chatweb-tonghop.csv](D:/3-SoulSpace/ML/categories_classification/chatgpt/chatweb-120k/chatweb-tonghop.csv). Ngày thống kê: 16/11/2026.

## 1. Tổng quan

| Hạng mục | Số lượng | Tỷ lệ trên tổng dữ liệu |
|---|---:|---:|
| Dòng dữ liệu, không tính header | **120.000** | 100,00% |
| Dòng trong file, gồm header | 120.001 | — |
| Số nhãn chủ đề khác nhau | 15 | — |
| Dòng đã gắn nhãn | 116.703 | 97,25% |
| Dòng chưa gắn nhãn | **3.297** | 2,75% |
| Dòng đơn nhãn: đúng 1 nhãn | **75.737** | 63,11% |
| Dòng đa nhãn: từ 2 nhãn trở lên | **40.966** | 34,14% |
| Tổng lượt gắn nhãn | 164.027 | — |
| Số nhãn tối đa trên một dòng | **6** | — |

Đếm theo bản ghi CSV bằng trình đọc CSV, không tách nội dung theo dấu phẩy. Nhãn trong cột `labels` được tách bằng `|`, bỏ khoảng trắng hai đầu. Dòng không có nhãn sau khi tách được tính là chưa gắn nhãn. Đơn nhãn/đa nhãn là số dòng dữ liệu, không phải số loại chủ đề.

## 2. Số dòng dữ liệu theo từng nhãn

| Mã nhãn | Chủ đề | Số dòng có nhãn | Tỷ lệ trên 120.000 dòng |
|---|---|---:|---:|
| `cuoc_song` | Cuộc sống | 16.098 | 13,41% |
| `xa_hoi` | Xã hội | 15.789 | 13,16% |
| `mua_sam` | Mua sắm | 14.594 | 12,16% |
| `giai_tri` | Giải trí | 14.257 | 11,88% |
| `gia_dinh` | Gia đình | 12.788 | 10,66% |
| `tinh_cam` | Tình cảm | 11.092 | 9,24% |
| `cong_viec` | Công việc | 10.611 | 8,84% |
| `hoc_tap` | Học tập | 9.578 | 7,98% |
| `suc_khoe` | Sức khỏe | 9.485 | 7,90% |
| `cong_nghe` | Công nghệ | 9.457 | 7,88% |
| `lam_dep` | Làm đẹp | 9.156 | 7,63% |
| `the_thao` | Thể thao | 8.423 | 7,02% |
| `tai_chinh` | Tài chính | 7.927 | 6,61% |
| `am_thuc` | Ẩm thực | 7.822 | 6,52% |
| `du_lich` | Du lịch | 6.950 | 5,79% |

Một dòng đa nhãn được tính một lần trong từng nhãn mà nó mang. Vì vậy tổng số dòng theo từng nhãn là 164.027, lớn hơn tổng số dòng dữ liệu; đây không phải số dòng bị trùng.

## 3. Phân bố số nhãn trên mỗi dòng

| Số nhãn trên một dòng | Loại | Số dòng | Tỷ lệ trên tổng dữ liệu |
|---:|---|---:|---:|
| 0 | Chưa gắn nhãn | 3.297 | 2,75% |
| 1 | Đơn nhãn | 75.737 | 63,11% |
| 2 | Đa nhãn | 35.225 | 29,35% |
| 3 | Đa nhãn | 5.206 | 4,34% |
| 4 | Đa nhãn | 464 | 0,39% |
| 5 | Đa nhãn | 60 | 0,05% |
| 6 | Đa nhãn | 11 | 0,01% |
| **Tổng** | | **120.000** | **100,00%** |

Có **40.966 dòng đa nhãn**, gồm các mức từ 2 đến 6 nhãn. Không có dòng nào mang hơn 6 nhãn.

## 4. Các dòng có số nhãn tối đa: 6 nhãn

Có **11 dòng** mang 6 nhãn. Danh sách đầy đủ:

| ID | Các nhãn |
|---|---|
| `SS_018984` | `am_thuc`, `cong_viec`, `lam_dep`, `suc_khoe`, `the_thao`, `xa_hoi` |
| `SS_027093` | `cong_viec`, `cuoc_song`, `gia_dinh`, `suc_khoe`, `tai_chinh`, `xa_hoi` |
| `SS_115103` | `cuoc_song`, `du_lich`, `giai_tri`, `hoc_tap`, `suc_khoe`, `the_thao` |
| `SS_260563` | `am_thuc`, `cuoc_song`, `du_lich`, `gia_dinh`, `lam_dep`, `tai_chinh` |
| `SS_323291` | `cong_viec`, `cuoc_song`, `du_lich`, `mua_sam`, `tai_chinh`, `tinh_cam` |
| `SS_377507` | `cong_viec`, `cuoc_song`, `gia_dinh`, `lam_dep`, `suc_khoe`, `the_thao` |
| `SS_593537` | `cuoc_song`, `du_lich`, `lam_dep`, `mua_sam`, `tai_chinh`, `tinh_cam` |
| `SS_625348` | `am_thuc`, `cong_viec`, `hoc_tap`, `tai_chinh`, `the_thao`, `tinh_cam` |
| `SS_704826` | `cong_viec`, `cuoc_song`, `du_lich`, `gia_dinh`, `lam_dep`, `tai_chinh` |
| `SS_985410` | `cong_viec`, `cuoc_song`, `gia_dinh`, `suc_khoe`, `tai_chinh`, `xa_hoi` |
| `SS_987681` | `am_thuc`, `cuoc_song`, `gia_dinh`, `lam_dep`, `suc_khoe`, `tinh_cam` |

## 5. Đối soát

- 75.737 dòng đơn nhãn + 40.966 dòng đa nhãn + 3.297 dòng chưa gắn nhãn = **120.000 dòng**.
- Số dòng sai số cột CSV: **0**.
- Số lượt ID bị lặp ngoài lần xuất hiện đầu: **0**.
- Số dòng có ID trống: **0**.
- Số dòng lặp cùng một nhãn trong cột `labels`: **0**.
- Thống kê chỉ phản ánh nhãn hiện có, không xác nhận nhãn đúng với nội dung.

SHA-256 của file nguồn: `e41a9c498af5951f97ad5166a7ad2ce86a2c0af1a53868b58d6400f6639e70c0`.
