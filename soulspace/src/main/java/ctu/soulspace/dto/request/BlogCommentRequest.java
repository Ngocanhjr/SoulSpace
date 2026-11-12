package ctu.edu.blogAPI.dto.request;

import lombok.Data;

@Data
public class BlogCommentRequest {
    private String userId;
    private String content;
}
