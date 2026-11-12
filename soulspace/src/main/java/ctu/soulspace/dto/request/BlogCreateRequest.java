package ctu.edu.blogAPI.dto.request;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Data
@Builder
public class BlogCreateRequest {
    private String userId;
    @Size(max = 5000, message = "Exceed 5000 characters!!!")
    private String content;
    
    private boolean published = true;

    @ArraySchema(
            schema = @Schema(type = "array", format = "binary", description = "Danh sách file hình ảnh/blog", implementation = MultipartFile.class)
    )
    private List<MultipartFile> files;

}
