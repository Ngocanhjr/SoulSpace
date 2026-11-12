package ctu.edu.blogAPI.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Data
@Builder
public class BlogDTO {
    private String id;

    private String userId;

    private String username;

    private String userAvatarUrl;

    private String content;

    private List<String> imageContentUrls;

    private Long likesCount;

    private Long commentsCount;

    private Long sharesCount;

    private Set<String> likedUserIds;

    private boolean published = true;

    private Instant createdAt;

    private Instant updatedAt;
}
