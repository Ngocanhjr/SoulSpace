package ctu.edu.blogAPI.dto.request;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
@Data // khai all hàm get,set,ToString
@Builder // khởi tạo object theo kiểu xâu chuỗi, dễ đọc – không cần viết constructor dài
         // hay gọi nhiều setter.
@NoArgsConstructor // tạo constructor không tham số (mặc định).
@AllArgsConstructor // tạo constructor với tất cả field theo thứ tự khai báo.
@FieldDefaults(level = AccessLevel.PRIVATE) // ko cần khai khai báo các phạm vi của biến

public class UserUpdateRequestPatch {
    @NotBlank(message = "Tên tài khoản không được để trống")
    @Size(min = 3, message = "username must be at least 3 character!!!")
    String username;

    String fullname;

    LocalDate dob;

    String userAvatarUrl;

}
