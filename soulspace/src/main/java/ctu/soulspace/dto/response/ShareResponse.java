package ctu.soulspace.dto.response;

import java.time.Instant;
import java.util.UUID;

public record ShareResponse(
        UUID blogId,
        UUID userId,
        long sharesCount,
        long totalSharedBlogs,
        Instant sharedAt,
        String message) {}
