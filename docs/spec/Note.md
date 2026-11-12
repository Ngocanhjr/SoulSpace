## Ghi chú

Báo cáo có nên giải thích sự khác nhau giữa trigger trong PostgreSQL và SQL Server:

| PostgreSQL                                                        | SQL Server                                             |
| ----------------------------------------------------------------- | ------------------------------------------------------ |
| `NEW`, `OLD` được sử dụng theo từng hàng trong row-level trigger. | Các bảng `inserted`, `deleted` có thể chứa nhiều hàng. |

- “Chỉ mình tôi” để người dùng hiểu rõ ý nghĩa của “riêng tư”.

- Kể cả tác giả cũng không chia sẻ được bài riêng tư của mình, vì điều kiện ở đây chỉ kiểm tra bài có công khai hay không.

## Luồng thao tác

### Luồng xử lý tổng quát

```text
Backend
  → Gọi procedure `sp_share_blog`
  → Procedure thêm một hàng vào `blog_shares`
  → Trigger `blog_shares_counter` được kích hoạt
  → Trigger gọi function `update_relation_counter`
  → Function tăng `blogs.shares_count`
```

### Transaction

1. Service mở transaction.
2. Service gọi procedure `sp_share_blog`.
3. Procedure thêm quan hệ giữa người dùng An và bài viết A vào
   `blog_shares`.
4. Trigger tự động tăng `blogs.shares_count`.
5. Service đọc lại kết quả.
6. Nếu mọi bước thành công, service thực hiện `COMMIT`; nếu có lỗi,
   service thực hiện `ROLLBACK`.

> **Phân tách trách nhiệm:** Procedure xử lý nghiệp vụ chính, trigger cập
> nhật các dữ liệu liên quan, còn service quyết định `COMMIT` hoặc
> `ROLLBACK`.


## Trigger
### Khi thêm lượt thích — `INSERT`

Trước đó chưa có hàng này. Sau khi thêm, hàng mới nằm trong NEW:

```text
NEW.user_id = An
NEW.blog_id = A
```

Trigger dùng `NEW.blog_id` để biết cần tăng bộ đếm của bài A. Vì không có
hàng cũ nên không sử dụng `OLD`.

### Khi bỏ thích — `DELETE`

Hàng thể hiện việc An thích bài A đang tồn tại và bị xóa. Dữ liệu của hàng
đó được cung cấp qua `OLD`:

```text
OLD.user_id = An
OLD.blog_id = A
```

Trigger dùng `OLD.blog_id` để biết cần giảm bộ đếm của bài A. Dù hàng đã bị
xóa khỏi bảng, trigger vẫn đọc được dữ liệu cũ thông qua `OLD`.

| Thao tác | Lấy dữ liệu từ |
| --- | --- |
| Thêm hàng | `NEW` — hàng mới |
| Xóa hàng | `OLD` — hàng bị xóa |
| Sửa hàng | `OLD` — dữ liệu trước khi sửa; `NEW` — dữ liệu sau khi sửa |

Vì vậy:

```sql
CASE
    WHEN TG_OP = 'INSERT' THEN NEW.blog_id
    ELSE OLD.blog_id
END
```

Nghĩa là: “Nếu đang thêm, lấy ID bài từ hàng mới; nếu đang xóa, lấy ID
bài từ hàng bị xóa.”

Trong đoạn này, `ELSE` tương ứng với thao tác xóa vì trigger chỉ được gắn
cho `INSERT OR DELETE`.