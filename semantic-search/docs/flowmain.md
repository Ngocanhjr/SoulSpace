# Luồng chính Semantic Search của SoulSpace

Jina v3 tạo embedding từ bài đăng và câu tìm kiếm. PostgreSQL trên Neon dùng `pgvector` để xếp hạng các bài công khai theo khoảng cách cosine.

```mermaid
flowchart TD
    subgraph PREP["1. Import bài đăng và tạo embedding"]
        CSV["File bài đăng"] --> IMPORT["psql · import dữ liệu"]
        IMPORT --> BLOGS
        BLOGS -->|"Đọc bài công khai có nội dung"| BACKFILL["Script backfill · xử lý theo lô"]
        BACKFILL -->|"Nội dung bài"| PASSAGE["Jina v3 · retrieval_passage"]
        PASSAGE -->|"Backfill ghi ID bài và vector 1024 chiều"| EMBEDDINGS
    end

    subgraph NEON["2. PostgreSQL trên Neon"]
        BLOGS[("blogs · dữ liệu bài gốc")]
        EMBEDDINGS[("blog_embeddings · vector bài viết")]
        FEED["v_public_blog_feed · bài công khai"]
        SQL["SQL · JOIN theo ID bài, xếp hạng cosine bằng pgvector"]
        BLOGS --> FEED --> SQL
        EMBEDDINGS --> SQL
    end

    subgraph SEARCH["3. Tìm kiếm trên web"]
        UI["SearchPage"] -->|"Câu tìm kiếm"| API["Spring Boot · API tìm kiếm"]
        API -->|"Gửi câu tìm kiếm"| QUERY["Jina v3 · retrieval_query"]
        QUERY -->|"Trả vector 1024 chiều"| API
        API -->|"Truyền vector truy vấn"| SQL
        SQL -->|"Trả danh sách bài gần nghĩa"| API
        API -->|"Trả kết quả"| UI
    end
```

## Phần làm riêng và phần tích hợp

- **Làm và kiểm thử riêng.** Dùng Python để tạo vector bằng Jina v3. Dùng `psql` để import bài, lưu vector và thử SQL trên Neon. Các bước này chạy được khi Spring Boot đang tắt.
- **Tích hợp vào backend.** Spring Boot nhận câu tìm kiếm từ `SearchPage`, gọi dịch vụ embedding và chạy SQL trên Neon. Khi backend hỗ trợ tạo hoặc sửa bài, worker sẽ cập nhật embedding sau khi thay đổi được lưu thành công.

Neon hiện đã có `pgvector`, bảng `blogs` và view `v_public_blog_feed` từ phần chuẩn bị V1–V5. Migration V6 dự kiến tạo `blog_embeddings` để lưu vector. Khi có file bài đăng, import vào `blogs` rồi chạy backfill để tạo vector ban đầu. Xem thêm [kế hoạch triển khai](plan.md).
