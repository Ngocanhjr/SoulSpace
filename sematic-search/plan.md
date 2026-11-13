# Kế hoạch xây dựng Semantic Search — SoulSpace

> **Mục tiêu:** Tìm bài viết tiếng Việt theo **ý nghĩa**, không cần trùng từ khóa. Dùng `jinaai/jina-embeddings-v3-hf` đã huấn luyện sẵn, **không train/fine-tune AI**.
>
> **Phạm vi:** Chỉ bổ sung chức năng tìm kiếm cho web SoulSpace; giữ nguyên PostgreSQL và hai mô hình ML (phân loại chủ đề, phát hiện spam) hiện có.

## 1. Hiện trạng cần tái sử dụng

Theo `03-postgresql.md`, dự án đã thiết kế:
- Backend **Java/Spring Boot**; CSDL dùng duy nhất **Neon PostgreSQL**, quản lý schema bằng **Flyway**. Không dựng hoặc vận hành PostgreSQL local/Docker cho chức năng này.
- Bảng `blogs`: `id UUID`, `content TEXT`, `published BOOLEAN`, `updated_at`.
- View `v_public_blog_feed` để đọc bài công khai; migration hiện mô tả từ **V1–V5**.

**Trước khi làm:** Kiểm tra source và Neon database đang dùng có khớp tài liệu hay không, đặc biệt PostgreSQL major version, package Java, migration cuối, dữ liệu mẫu và quyền tạo extension. Không sửa các migration đã áp dụng.

## 2. Công nghệ sử dụng

| Thành phần | Công cụ | Nhiệm vụ |
|---|---|---|
| Embedding | **`jinaai/jina-embeddings-v3-hf`** + `sentence-transformers` | Biến tiếng Việt thành vector **1024 chiều**; dùng `retrieval_passage` cho bài viết và `retrieval_query` cho câu tìm kiếm |
| Embedding API | **Python + FastAPI + Uvicorn** | Cung cấp `POST /embed` cho backend |
| Database | **Neon PostgreSQL + pgvector** | Lưu vector và tìm bài gần nghĩa bằng cosine distance; không dùng database local |
| Backend | **Spring Boot + JdbcTemplate/WebClient** | Gọi Embedding API, truy vấn SQL, trả kết quả |
| Migration | **Flyway** | Tạo bảng/index mới, không sửa bảng `blogs` |
| Kiểm thử | **Postman/curl, pytest, SQL EXPLAIN** | Kiểm API, chất lượng và hiệu năng |

**Quyết định MVP:** Chỉ tìm bài có `published=true` và `content` không rỗng; chưa tìm theo ảnh, bình luận hoặc bài private.

## 3. Luồng hoạt động

**Nạp dữ liệu:** `blogs.content` → Jina v3 với adapter `retrieval_passage` → vector chuẩn hóa L2 → bảng `blog_embeddings` trên Neon.

**Tìm kiếm:** Người dùng nhập câu → Spring gọi `/embed` với loại đầu vào `query` → Jina v3 dùng adapter `retrieval_query` và chuẩn hóa L2 → Neon tìm vector gần nhất → JOIN bài public → trả về giao diện SoulSpace.

**Đồng bộ:** Khi tạo/sửa/chuyển quyền bài, backend ghi một *job* trong transaction; worker tạo/cập nhật embedding **sau commit**. Khi xóa bài, FK cascade xóa vector/job. Không gọi mô hình bên trong trigger hay transaction dài.

## 4. Cấu trúc thư mục đề xuất

```text
REPO_SOULSPACE/
├── soulspace/                                  # Spring Boot hiện có
│   └── src/main/
│       ├── java/<base-package>/semantic/
│       │   ├── api/SemanticSearchController.java
│       │   ├── service/SemanticSearchService.java
│       │   ├── service/BlogEmbeddingEnqueueService.java
│       │   ├── client/EmbeddingClient.java
│       │   ├── repository/BlogVectorRepository.java
│       │   ├── worker/BlogEmbeddingWorker.java
│       │   └── backfill/SemanticBackfillRunner.java
│       └── resources/db/migration/
│           ├── V6__semantic_search_tables.sql
│           └── V7__semantic_search_hnsw.sql    # Chỉ tạo khi cần
├── embedding-service/
│   ├── app/main.py                            # GET /health, POST /embed
│   ├── app/model.py                           # Nạp Jina v3 và encode query/document
│   ├── app/schemas.py                         # Định dạng request/response
│   ├── tests/test_embed.py
│   ├── requirements.txt
│   └── .env.example                       # Cấu hình model; không chứa Neon secret
├── infra/postgres/tests/semantic_search.sql
├── docs/semantic-search/
│   ├── README.md
│   ├── test_queries.csv
│   └── test_results.csv
└── frontend/...                               # Tích hợp SearchPage hiện có
```

`<base-package>` và tên migration **phải kiểm tra theo source thật** trước khi tạo file.

## 5. Thứ tự thực hiện — làm từ đâu trước?

| Bước | Việc cần làm | Kết quả cần đạt |
|---|---|---|
| **1. Kiểm tra Neon** | Xác nhận branch/database Neon đích, PostgreSQL major version, migration Flyway cuối, bảng `blogs`, view `v_public_blog_feed`, SearchPage, Java package và quyền `CREATE EXTENSION vector` | Biết chính xác môi trường cloud cần tích hợp; không phụ thuộc PostgreSQL local |
| **2. Chạy thử embedding** | Tạo `embedding-service`; cài FastAPI và sentence-transformers; load Jina v3; encode bài bằng `retrieval_passage`, query bằng `retrieval_query`; kiểm tra chuẩn hóa L2 | API trả vector 1024 chiều và phân biệt đúng query/document |
| **3. Tạo bảng vector trên Neon** | Bật `pgvector` và chạy migration mới tạo `blog_embeddings`, `blog_embedding_jobs`; thêm FK tới `blogs.id` | Neon lưu được embedding và Flyway ghi nhận migration thành công |
| **4. Nạp dữ liệu ban đầu** | Viết backfill cho khoảng 20–50 bài public có text, chạy theo lô nhỏ | Có vector dữ liệu thật để tìm |
| **5. Viết SQL tìm kiếm** | Trên Neon, dùng cosine `<=>`, lọc public qua `v_public_blog_feed`, lấy top 10; ban đầu exact search | SQL trả bài gần nghĩa |
| **6. Tạo API Spring** | Viết `EmbeddingClient`, repository, service, `GET /api/v1/search/semantic?q=...` | Postman nhận kết quả |
| **7. Đồng bộ bài viết** | Job/worker cho tạo, sửa, private/public, xóa; bỏ kết quả embedding cũ khi nội dung đổi | Không lộ private, không trả nội dung cũ |
| **8. Gắn giao diện** | Tích hợp SearchPage, loading/empty/error states | Người dùng tìm kiếm được trên web |
| **9. Kiểm thử & báo cáo** | So sánh với `ILIKE`, đo Precision@5 và latency end-to-end tới Neon; dùng `EXPLAIN (ANALYZE, BUFFERS)`; thử HNSW khi dữ liệu và số đo cho thấy cần | Có minh chứng và số liệu trên môi trường cloud thật |

**Thứ tự ưu tiên:** Hoàn thành **bước 1–5** (tìm thành công bằng SQL) trước khi làm API, worker đầy đủ và frontend.

## 6. Thiết kế database tối thiểu

Migration mới (ví dụ V6; đổi số nếu project đã có migration cao hơn) được Flyway chạy trực tiếp trên Neon. Tài khoản Flyway cần quyền tạo extension/schema; runtime user chỉ cần quyền DML tối thiểu:

```sql
-- Cần enable extension vector bằng role có quyền phù hợp trước khi migrate.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE blog_embeddings (
    blog_id UUID PRIMARY KEY REFERENCES blogs(id) ON DELETE CASCADE,
    embedding VECTOR(1024) NOT NULL,
    model_name TEXT NOT NULL DEFAULT 'jinaai/jina-embeddings-v3-hf',
    embedding_dimension INTEGER NOT NULL DEFAULT 1024 CHECK (embedding_dimension = 1024),
    content_sha256 CHAR(64) NOT NULL,
    embedded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Không trộn vector từ model, revision, số chiều hoặc cách encode khác nhau. Nếu thay một trong các yếu tố đó, tạo generation mới và backfill lại toàn bộ bài. Lưu chính xác model revision/config trong cấu hình triển khai để kết quả tái lập được.

Bổ sung bảng **`blog_embedding_jobs`** cho hàng đợi: `blog_id` (UUID/FK), `generation`, `status` (`PENDING/RUNNING/DEAD`), `attempts`, `available_at`, `lease_until`, `last_error`. Khi worker xong phải kiểm tra lại nội dung/quyền và `generation` trước khi lưu; tránh ghi đè vector mới bằng kết quả cũ.

SQL tìm kiếm mẫu (vector query do backend bind, không nối chuỗi SQL):

```sql
SELECT p.blog_id, p.content,
       1 - (e.embedding <=> :query_vector::vector) AS similarity
FROM blog_embeddings e
JOIN v_public_blog_feed p ON p.blog_id = e.blog_id
ORDER BY e.embedding <=> :query_vector::vector
LIMIT 10;
```

Ở bước đầu dùng **exact search**. Sau khi có dữ liệu và kết quả đo trên Neon, cân nhắc migration V7 với chỉ mục **HNSW** (`vector_cosine_ops`) để tăng tốc. Không tạo HNSW chỉ vì có sẵn; phải so sánh latency, recall và chi phí tài nguyên trước/sau.

## 7. Kết nối và vận hành Neon

- Dùng `application-db-neon.properties` và biến môi trường `DB_URL`, `DB_USER`, `DB_PASSWORD`, `FLYWAY_URL`, `FLYWAY_USER`, `FLYWAY_PASSWORD`; không ghi connection string hoặc mật khẩu vào Git.
- Tách Flyway user và runtime user theo cấu hình backend hiện có. Flyway user quản lý extension/migration; runtime user chỉ đọc/ghi các bảng nghiệp vụ cần thiết.
- Bật SSL theo connection string do Neon cấp. Ưu tiên endpoint pooled cho lưu lượng ứng dụng; kiểm tra giới hạn kết nối thực tế của project trước khi chốt Hikari pool.
- Dùng một Neon branch riêng cho phát triển/kiểm thử nếu project cho phép; chỉ chạy migration lên branch chính sau khi migration và rollback strategy đã được kiểm chứng.
- Vì Neon có thể suspend compute, bài test latency phải ghi riêng cold start và warm request; không kết luận hiệu năng từ một request đơn lẻ.
- Backup/khôi phục và branch database thuộc Neon; dự án không duy trì volume PostgreSQL hoặc quy trình restore local.

## 8. Kiểm thử tối thiểu

- **Đúng nghĩa:** “Áp lực ôn thi” có thể tìm được bài “Lo lắng vì sắp kiểm tra” dù không trùng từ.
- **Quyền truy cập:** Bài private không xuất hiện; chuyển public/private cập nhật đúng.
- **CRUD:** Tạo/sửa/xóa bài không để vector sai hoặc kết quả cũ.
- **Lỗi hệ thống:** Embedding API tạm ngừng, worker retry có giới hạn và khôi phục được.
- **Nhất quán model:** Query dùng `retrieval_query`, bài dùng `retrieval_passage`; mọi vector có đúng 1024 chiều và được chuẩn hóa L2.
- **Neon:** Migration chạy được trên branch thử nghiệm; runtime user không có quyền DDL; kết nối SSL hoạt động; đo riêng cold/warm latency.
- **So sánh:** Cùng bộ 20–30 câu truy vấn, đối chiếu `ILIKE` và Semantic Search; đo **Precision@5** và thời gian phản hồi end-to-end; không tự khẳng định hiệu quả trước khi đo.

## 9. Sản phẩm bàn giao

- Module Python `/embed` chạy được với Jina v3, phân biệt query/document và **không có code train**.
- Migration Neon PostgreSQL, `pgvector` và SQL cosine search.
- API Spring + đồng bộ embedding + UI tìm kiếm.
- Bộ test, bảng kết quả, ảnh minh chứng và `README.md` hướng dẫn chạy ứng dụng với Neon là database duy nhất.

**Bắt đầu ngay:** xác nhận Neon branch/database và quyền `CREATE EXTENSION vector` → kiểm tra dữ liệu `blogs` và Flyway → tạo `embedding-service` → thử một cặp query/bài tiếng Việt ra vector 1024 chiều bằng đúng adapter. Chưa cần đụng tới 2 mô hình ML khác và không dựng PostgreSQL local.
