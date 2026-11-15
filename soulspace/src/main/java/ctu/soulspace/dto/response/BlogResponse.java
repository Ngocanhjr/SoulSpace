package ctu.soulspace.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BlogResponse(
        UUID id,
        UUID userId,
        String username,
        String userAvatarUrl,
        String content,
        List<String> imageContentUrls,
        long likesCount,
        long commentsCount,
        long sharesCount,
        List<UUID> likedUserIds,
        boolean published,
        Instant createdAt,
        Instant updatedAt) {}
