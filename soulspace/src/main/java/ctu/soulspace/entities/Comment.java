package ctu.edu.blogAPI.entities;

import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@Setter
public class Comment {
    private String id;

    private String userId;

    private UserInfo author;

    private String content;

    @Builder.Default
    private Long likesCount = 0L;

    @Builder.Default
    private Set<String> likedUserIds = new HashSet<>();

    @Builder.Default
    private List<Comment> replies = new ArrayList<>();

    private Instant createdAt;
}
