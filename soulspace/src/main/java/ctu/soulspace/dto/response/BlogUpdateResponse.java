package ctu.edu.blogAPI.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
public class BlogUpdateResponse {
    private String blogId;

    private String content;

    private List<String> successUrls;

    private List<String> failedDeleteFiles;

    private List<String> failedUploadFiles;

    private Boolean published;

    private Instant updatedAt;
}
