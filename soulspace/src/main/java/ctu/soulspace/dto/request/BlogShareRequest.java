package ctu.edu.blogAPI.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BlogShareRequest {
    @NotBlank(message = "userId must not be blank")
    private String userId;
}
