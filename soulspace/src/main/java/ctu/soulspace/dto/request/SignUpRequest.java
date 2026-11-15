package ctu.soulspace.dto.request;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class SignUpRequest extends LoginRequest {

    @NotNull
    private String fullname;

    private LocalDate dob;
    private LocalDate birthday;
}
