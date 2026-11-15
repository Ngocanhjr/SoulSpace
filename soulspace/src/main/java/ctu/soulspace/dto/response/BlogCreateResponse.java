package ctu.soulspace.dto.response;

import java.util.List;
import java.util.UUID;

public record BlogCreateResponse(UUID blogId, List<String> successUrls, List<String> failedFiles) {}
