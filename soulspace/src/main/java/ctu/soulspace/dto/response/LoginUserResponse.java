package ctu.soulspace.dto.response;

import java.util.UUID;

public record LoginUserResponse(UUID id, String username, String avatarUrl) {
}
