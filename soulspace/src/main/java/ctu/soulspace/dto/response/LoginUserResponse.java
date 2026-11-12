package ctu.edu.blogAPI.dto.response;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class LoginUserResponse {
    String id;
    String username;
}
