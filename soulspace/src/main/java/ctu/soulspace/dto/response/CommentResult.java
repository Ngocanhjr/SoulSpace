package ctu.soulspace.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommentResult(
        UUID blogId,
        UUID parentCommentId,
        long commentsCount,
        CommentResponse comment,
        CommentResponse reply,
        String message) {}
