package ctu.soulspace.dto.response;

import java.time.LocalDate;
import java.util.UUID;

public record UserResponse(UUID id, String username, String fullname,
                           LocalDate dob, String userAvatarUrl) {
}
