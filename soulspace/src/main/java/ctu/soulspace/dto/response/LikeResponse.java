package ctu.soulspace.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LikeResponse(
        UUID blogId, UUID commentId, UUID userId, boolean liked, long likesCount, String message) {}
