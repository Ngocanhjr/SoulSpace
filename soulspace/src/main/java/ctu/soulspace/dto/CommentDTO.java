package ctu.edu.blogAPI.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Data
@Builder
public class CommentDTO {
    private String id;

    private String userId;

    private String username;

    private String userAvatarUrl;

    private String content;

    private Long likesCount;

    private Set<String> likedUserIds;

    private List<CommentDTO> replies;

    private Instant createdAt;
}
