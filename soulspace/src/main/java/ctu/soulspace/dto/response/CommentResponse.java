package ctu.soulspace.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CommentResponse(
        UUID id,
        UUID userId,
        String username,
        String userAvatarUrl,
        String content,
        long likesCount,
        List<UUID> likedUserIds,
        List<CommentResponse> replies,
        Instant createdAt,
        Instant updatedAt,
        boolean isDeleted,
        long version) {}
