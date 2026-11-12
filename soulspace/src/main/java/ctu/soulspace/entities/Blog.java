package ctu.edu.blogAPI.entities;

import lombok.*;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

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
@Document(collection = "blogs")
public class Blog {
    @Id
    private ObjectId id;

    private ObjectId userId;

    private UserInfo author;

    private String content;

    private List<String> imageContentUrls;

    @Builder.Default
    private Long likesCount = 0L;

    @Builder.Default
    private Long commentsCount = 0L;

    @Builder.Default
    private Long sharesCount = 0L;

    @Builder.Default
    private Set<String> likedUserIds = new HashSet<>();

    @Builder.Default
    private List<Comment> comments = new ArrayList<>();

    @Builder.Default
    private boolean published = true;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public Blog(Blog blog) {
    }
}
