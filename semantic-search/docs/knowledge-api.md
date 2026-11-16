# Kiến thức kết nối frontend và backend — SoulSpace

## 1. Frontend và backend kết nối như thế nào?

Frontend (FE) hiển thị giao diện và nhận thao tác của người dùng. Backend (BE) xử lý yêu cầu, áp dụng quy tắc nghiệp vụ và đọc/ghi database.

FE gửi **HTTP request** đến backend. Backend xử lý rồi trả **HTTP response**, thường chứa dữ liệu dạng JSON.

Ví dụ luồng tìm kiếm của SoulSpace:

```text
Người dùng nhập câu tìm kiếm
  → React gửi GET đến API Spring Boot
  → Backend gọi Jina tạo vector cho câu tìm kiếm
  → Backend dùng JDBC truy vấn các vector bài viết trên Neon
  → Backend trả danh sách bài dạng JSON
  → React lưu kết quả vào state và hiển thị
```

Vector bài viết đã được tạo và lưu trước đó. Khi tìm kiếm, backend tạo vector cho câu người dùng vừa nhập.

## 2. API và endpoint là gì?

API là giao diện cùng quy ước để các chương trình trao đổi với nhau. FE và BE thống nhất phương thức HTTP, đường dẫn, đầu vào, đầu ra và cách xác thực.

Một endpoint cụ thể có phương thức và đường dẫn, ví dụ `GET /api/v1/search/semantic`.

```text
http://localhost:8080/api/v1/search/semantic?q=ôn%20thi
└─ Địa chỉ backend ─┘└─ Đường dẫn API ─────┘└─ Tham số ─┘
```

`localhost:8080` là backend chạy trên máy đang sử dụng trình duyệt. Khi triển khai, FE dùng địa chỉ backend của môi trường triển khai.

Đường dẫn API do người viết backend đặt trong code. Ví dụ `/api/v1/search/semantic` có thể hiểu theo quy ước sau:

| Phần | Ý nghĩa |
|---|---|
| `/api` | Nhóm API của ứng dụng |
| `/v1` | Phiên bản API thứ nhất |
| `/search` | Chức năng tìm kiếm |
| `/semantic` | Kiểu tìm theo ngữ nghĩa |

Bạn cũng có thể đặt `/api/search`. FE cần gọi đúng đường dẫn backend khai báo. Các ví dụ trong tài liệu này dùng `/api/v1/search/semantic`; khi áp dụng, đối chiếu annotation trong controller hiện tại.

## 3. GET, POST, PUT, PATCH, DELETE gọi chung là gì?

Chúng gọi chung là **HTTP methods**, tức phương thức HTTP. Mỗi phương thức thể hiện thao tác muốn thực hiện với tài nguyên.

| Phương thức | Mục đích thường dùng | Ví dụ |
|---|---|---|
| GET | Lấy dữ liệu | Tìm bài, xem chi tiết bài |
| POST | Tạo tài nguyên hoặc yêu cầu xử lý | Đăng bài mới |
| PUT | Thay thế toàn bộ tài nguyên | Gửi dữ liệu thay thế bài |
| PATCH | Cập nhật một phần tài nguyên | Sửa nội dung bài |
| DELETE | Xóa tài nguyên | Xóa bài theo ID |

Backend quyết định hành vi cụ thể và những phương thức được hỗ trợ cho mỗi đường dẫn.

## 4. Cú pháp gọi API trong frontend bằng Axios

Các đoạn dùng `await` cần đặt trong hàm `async`. URL bài viết bên dưới là ví dụ, cần có endpoint tương ứng trong backend.

```js
import axios from "axios";

const blogsUrl = "http://localhost:8080/api/v1/blogs";

async function examples(blogId) {
  // GET truyền tham số URL qua params.
  const response = await axios.get(blogsUrl, {
    params: { page: 1, limit: 10 }
  });
  console.log(response.data);

  // POST gửi dữ liệu tạo bài trong request body.
  await axios.post(blogsUrl, {
    content: "Bài viết mới",
    published: true
  });

  // PUT gửi dữ liệu thay thế đầy đủ theo hợp đồng API.
  await axios.put(`${blogsUrl}/${blogId}`, {
    content: "Nội dung thay thế",
    published: true
  });

  // PATCH gửi các trường cần sửa.
  await axios.patch(`${blogsUrl}/${blogId}`, {
    content: "Nội dung đã sửa"
  });

  // DELETE xác định bài qua ID trên đường dẫn.
  await axios.delete(`${blogsUrl}/${blogId}`);
}
```

Đây là minh họa riêng từng phương thức. Khi sử dụng, gắn từng lệnh vào đúng thao tác người dùng, chẳng hạn bấm nút đăng hoặc xóa bài.

Ví dụ hàm tìm kiếm trong React, với `setPosts` và `setError` là các hàm cập nhật state:

```jsx
async function searchPosts(query) {
  try {
    const response = await axios.get(
      "http://localhost:8080/api/v1/search/semantic",
      { params: { q: query } }
    );
    setPosts(response.data);
    setError("");
  } catch (error) {
    setError("Không thể tìm kiếm lúc này.");
    console.error(error);
  }
}
```

`await` chờ kết quả request. `response.data` chứa dữ liệu backend trả về. `setPosts()` cập nhật state để React vẽ lại giao diện.

Nếu API yêu cầu đăng nhập, request cần thông tin xác thực theo cơ chế backend đang dùng. Khi FE và BE khác origin, backend cũng cần cấu hình CORS cho origin của FE. Origin gồm giao thức, hostname và cổng, nên `localhost:5173` và `localhost:8080` là hai origin khác nhau.

## 5. `.map()` dùng làm gì?

`.map()` là phương thức xử lý mảng JavaScript. Nó tạo mảng mới bằng cách biến đổi từng phần tử. Trong React, thường dùng nó để biến danh sách bài nhận được thành phần tử giao diện:

```jsx
{posts.map((post) => (
  <article key={post.id}>
    <p>{post.content}</p>
    <span>@{post.username}</span>
  </article>
))}
```

Ở đây, Axios lấy dữ liệu từ backend, còn `.map()` tạo giao diện từ dữ liệu đã có trong state.

## 6. `@GetMapping` là gì?

`@GetMapping` là annotation Spring dùng để gắn một phương thức Java với HTTP GET tại một đường dẫn.

```java
@RestController
public class SemanticSearchController {
    // Các dependency được truyền vào qua constructor.

    @GetMapping("/api/v1/search/semantic")
    public List<SearchResult> search(@RequestParam("q") String query) {
        return searchService.search(query);
    }
}
```

Đây là minh họa cấu trúc controller khi có service. Với request `GET /api/v1/search/semantic?q=ôn%20thi`, Spring chọn hàm `search()` và lấy tham số `q` đã giải mã vào biến `query`. `@RestController` cho phép Spring chuyển giá trị trả về thành response JSON với cấu hình hiện tại.

| Phương thức HTTP | Annotation Spring |
|---|---|
| GET | `@GetMapping` |
| POST | `@PostMapping` |
| PUT | `@PutMapping` |
| PATCH | `@PatchMapping` |
| DELETE | `@DeleteMapping` |

Tham số trên URL thường dùng `@RequestParam`, ID trong đường dẫn dùng `@PathVariable`, dữ liệu JSON gửi trong body dùng `@RequestBody`.

## 7. Vì sao backend có Controller, Service, Repository?

Đây là **kiến trúc phân lớp** (layered architecture), chia code theo trách nhiệm để dễ đọc, kiểm thử và sửa đổi.

| Thành phần | Trách nhiệm | Ví dụ tìm kiếm |
|---|---|---|
| Controller | Nhận HTTP request, kiểm tra đầu vào và trả response | Nhận `q`, trả danh sách JSON |
| Service | Điều phối xử lý và quy tắc nghiệp vụ | Gọi tạo vector rồi tìm bài |
| Repository | Đọc/ghi database | Chạy SQL trên Neon |
| Client | Gọi dịch vụ bên ngoài | Gọi API Jina |

```text
FE → Controller → Service → Jina Client → Jina
                         → Repository → Neon
```

Service có thể điều phối như sau:

```java
public List<SearchResult> search(String query) {
    String vector = jinaClient.embedQuery(query);
    return repository.search(vector);
}
```

Các lớp có thể dùng lại cùng service từ nhiều controller, hoặc thử nghiệp vụ bằng dependency giả lập mà không cần khởi động HTTP server.

Số lớp tùy độ phức tạp. Phần semantic hiện tại của SoulSpace có ba file, controller gọi trực tiếp client và repository:

| File hiện có | Công việc |
|---|---|
| `controllers/SearchController.java` | Nhận `q`, kiểm tra độ dài và điều phối tìm kiếm |
| `services/SearchEmbedding.java` | Đọc API key từ cấu hình Spring, gọi Jina tạo vector |
| `repositories/SearchRepository.java` | Dùng JDBC truy vấn bài công khai có vector khớp nội dung |

Khi phần điều phối phát triển thêm, có thể chuyển nó sang service riêng.

## 8. Cách lần theo một chức năng từ FE đến BE

1. Tìm sự kiện trên FE, ví dụ `handleSubmit` khi gửi form tìm kiếm.
2. Xem lệnh Axios gọi phương thức nào, URL nào và gửi dữ liệu gì.
3. Tìm annotation tương ứng trong controller của BE.
4. Đọc các hàm controller gọi tiếp, gồm service, client và repository.
5. Xem cấu trúc JSON trả về và cách FE dùng `response.data`.
6. Theo dõi state và `.map()` để biết kết quả được hiển thị ở đâu.

Trong tab Network của công cụ phát triển trình duyệt, mỗi request cho thấy URL, method, status, dữ liệu gửi và response. Các mã thường gặp gồm `200` thành công, `400` đầu vào không hợp lệ, `401` chưa xác thực, `403` không được phép, `404` không tìm thấy đường dẫn/tài nguyên và `503` dịch vụ tạm không khả dụng.
