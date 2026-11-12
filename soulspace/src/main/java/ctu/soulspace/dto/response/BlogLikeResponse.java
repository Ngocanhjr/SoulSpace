package ctu.edu.blogAPI.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BlogLikeResponse {
    private String blogId;
    private String userId;
    private boolean liked;
    private Long likesCount;
    private String message;
}
