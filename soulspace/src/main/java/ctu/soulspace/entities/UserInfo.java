package ctu.edu.blogAPI.entities;

import lombok.*;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class UserInfo {
    private String username;
    private String userAvatarUrl;
}
