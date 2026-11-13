# Dữ liệu bình luận cào cũ và mới

Ba CSV chỉ gồm dữ liệu cào, không gồm bộ ViHSD. Được tạo bằng `collection/merge_comments.py`; các file nguồn không bị sửa.

| File | Số dòng | Nội dung |
| --- | ---: | --- |
| `comments_crawl.raw.csv` | 3.943 | Dữ liệu trước tiền xử lý: 3.314 dòng cũ được ghép lại từ 3.173 dòng giữ và 141 dòng đã loại, cùng 629 dòng thô của lô mới. Nhãn trống. |
| `comments_crawl.clean.csv` | 3.788 | 3.173 dòng sạch cũ và 615 dòng sạch mới; nhãn trống. |
| `comments_crawl.labeled.csv` | 3.788 | Cùng dữ liệu, thứ tự và trường như file clean; chỉ khác `label_id`, gồm nhãn vote sơ bộ của lô cũ và nhãn GPT của lô mới. |

File raw **không phải bản sao chính xác theo thứ tự của file thô cũ**: không tìm thấy file thô gốc trong workspace. Bản này tái dựng các dòng từ file clean cũ và file removed cũ, theo thứ tự clean trước, removed sau, rồi ghép file thô lô mới. Thứ tự ban đầu của lô cũ không xác định. File removed được bỏ cột `filter_reason` khi gộp để khớp schema; bản nguồn có lý do vẫn ở `data/processed/comments_merged.removed.csv`.

Chạy kiểm tra `preprocess_comments.py` trên file raw tái dựng cho ra đúng 3.788 dòng khớp từng trường và thứ tự với file clean; 155 dòng bị loại. File labeled khớp file clean ngoài cột nhãn. Phân bố nhãn: CLEAN 3.243, OFFENSIVE 143, HATE 402.

Nguồn: `data/processed/comments_merged.clean_NgocAnh.csv` và `data/processed/comments_merged.removed.csv` cho lô cũ; `data/vote/final/comments_merged.final.provisional.csv` cho nhãn cũ; các file `comments_merged.csv`, `comments_merged.clean.csv`, `comments_merged.clean.labeled.csv` trong thư mục cha cho lô mới. Xem README của hai bộ nguồn để hiểu cách gán nhãn và những giới hạn. Các nhãn là nhãn AI sơ bộ, chưa được người gán nhãn độc lập xác nhận.
