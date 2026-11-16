# Chạy SoulSpace

Mỗi terminal bắt đầu tại thư mục gốc repo.

## Frontend

```powershell
cd frontend
npm i
npm run dev
```

Mở [localhost:5173](http://localhost:5173/). `npm ci` chỉ cần khi mới lấy source hoặc lockfile thay đổi.

## Backend — Neon

```powershell
cd soulspace
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=db-neon,web-local"
```

## Backend — Local DB

Chuẩn bị DB local, bảng vector/job và backfill theo [hướng dẫn embedding local](run-local-embedding.md). Script Python dùng `--env-file .env.docker`; backend dùng profile dưới đây.

```powershell
docker compose --env-file .env.docker up -d postgres
cd soulspace
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=db-local,web-local"
```

Kiểm tra [backend health](http://localhost:8080/actuator/health).

## File .env cần điền

### `.env.neon` — gốc repo

Dùng file Neon hiện có của nhóm. Kết nối DB và `JINA_API_KEY` đã có, không cần tạo key mới. Giữ:

```properties
spring.flyway.enabled=false
embedding.worker.enabled=true
embedding.worker.delay-ms=5000
```

### `.env.docker` — gốc repo

Tạo từ `.env.docker.example`. Điền ba password khác nhau:

```properties
POSTGRES_PASSWORD=<password-postgres>
DB_PASSWORD=<password-runtime>
FLYWAY_PASSWORD=<password-migrator>
```

Nếu dùng tìm kiếm/embedding, thêm:

```properties
JINA_API_KEY=<key-Jina-cua-nhom-hoac-key-rieng>
embedding.worker.enabled=true
embedding.worker.delay-ms=5000
```

### Cloudinary — tùy chọn

Nếu cần upload ảnh, điền trong `.env.neon` hoặc `.env.docker` đang dùng:

```properties
CLOUD_NAME=<cloud-name>
CLOUD_API_KEY=<api-key>
CLOUD_API_SECRET=<api-secret>
```

### `frontend/.env` — không bắt buộc

Nếu có file này, để:

```properties
VITE_API_BASE_URL=
```
