package ctu.soulspace.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BlogUpdateResponse(
        UUID blogId,
        String content,
        List<String> successUrls,
        List<String> failedDeleteFiles,
        List<String> failedUploadFiles,
        boolean published,
        Instant updatedAt) {}
