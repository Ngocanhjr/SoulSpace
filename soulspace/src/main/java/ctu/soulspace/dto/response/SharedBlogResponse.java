package ctu.soulspace.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SharedBlogResponse(
        UUID blogId,
        UUID originalUserId,
        String username,
        String userAvatarUrl,
        String content,
        List<String> imageContentUrls,
        boolean published,
        Instant originalCreatedAt,
        long likesCount,
        long commentsCount,
        long sharesCount,
        Instant sharedAt) {}
