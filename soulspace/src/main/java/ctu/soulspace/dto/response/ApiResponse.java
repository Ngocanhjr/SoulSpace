package ctu.edu.blogAPI.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

     int code = 1000;         // Mã code để người dùng tra cứu thông tin (có thể là mã lỗi hoặc mã thành công)
     String message;   // Thông điệp trả về cho người dùng
     T result;         // Kết quả trả về có thể là bất kỳ kiểu dữ liệu nào (generic type)

    
}
