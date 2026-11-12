package ctu.edu.blogAPI.dto.response;

import ctu.edu.blogAPI.dto.CommentDTO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BlogCommentResponse {
    private String blogId;
    private Long commentsCount;
    private CommentDTO comment;
    private String message;
}
