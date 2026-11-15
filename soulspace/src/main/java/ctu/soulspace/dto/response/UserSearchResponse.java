package ctu.soulspace.dto.response;

import java.util.UUID;

public record UserSearchResponse(
        UUID id, String username, String fullname, String userAvatarUrl) {}
