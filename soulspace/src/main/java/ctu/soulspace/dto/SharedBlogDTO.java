package ctu.edu.blogAPI.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class SharedBlogDTO {
    private String blogId;

    private String originalUserId;

    private String username;

    private String userAvatarUrl;

    private String content;

    private List<String> imageContentUrls;

    private boolean published;

    private Instant originalCreatedAt;

    private Long likesCount;

    private Long commentsCount;

    private Long sharesCount;

    private Instant sharedAt;
}
