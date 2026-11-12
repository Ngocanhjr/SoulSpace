package ctu.edu.blogAPI.dto.request;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
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
public class UserCreationRequest {
    @NotBlank(message = "Account name cannot be blank")
    @Size(min = 3, message = "username must be at least 3 characters!!!")
    // Chữ + số (phổ biến, vẫn không khoảng trắng/ký tự đặc biệt):
    @Pattern(regexp = "^[a-z0-9]+$", message = "Username must contain only a-z, 0-9, no spaces")
    String username;

    @NotBlank(message = "Password cannot be blank")
    @Size(min = 8, message = "password must be at least 8 character!!!")
    @Pattern(regexp = "^(?=.*[!@#$%^&*(),.?\":{}|<>]).+$", message = "Password must contain at least 1 special character")
    String password;

    @NotNull(message = "Full name cannot be left blank")
    String fullname;

    @Past(message = "Date of birth must be less than current date")
    LocalDate dob;

    String userAvatarUrl;

}
