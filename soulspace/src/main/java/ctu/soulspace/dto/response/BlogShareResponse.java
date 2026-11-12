package ctu.edu.blogAPI.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class BlogShareResponse {
    private String blogId;

    private String userId;

    private Long sharesCount;

    private Long totalSharedBlogs;

    private Instant sharedAt;

    private String message;
}
