package ctu.edu.blogAPI.dto.request;

import lombok.Builder;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Builder
@Data
public class BlogUpdateRequest {
    private String blogId;

    private String content;

    private MultipartFile[] newImages;

    private List<String> removeImagesUrl = new ArrayList<>();

    private Boolean published; // dùng wrapper để check null
}
