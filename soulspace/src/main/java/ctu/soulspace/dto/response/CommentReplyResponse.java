package ctu.edu.blogAPI.dto.response;

import ctu.edu.blogAPI.dto.CommentDTO;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CommentReplyResponse {
    private String blogId;
    private String parentCommentId;
    private Long commentsCount;
    private CommentDTO reply;
    private String message;
}
