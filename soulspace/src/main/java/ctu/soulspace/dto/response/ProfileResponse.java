package ctu.soulspace.dto.response;

import java.time.LocalDate;

public record ProfileResponse(
        String username, String fullname, LocalDate dob, String userAvatarUrl) {}
