package ctu.soulspace.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "blogs")
@Getter
@Setter
@NoArgsConstructor
public class Blog {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content = "";

    @Column(name = "published", nullable = false)
    private boolean published = true;

    @Setter(AccessLevel.NONE)
    @Column(name = "likes_count", nullable = false, insertable = false, updatable = false)
    private Long likesCount;

    @Setter(AccessLevel.NONE)
    @Column(name = "comments_count", nullable = false, insertable = false, updatable = false)
    private Long commentsCount;

    @Setter(AccessLevel.NONE)
    @Column(name = "shares_count", nullable = false, insertable = false, updatable = false)
    private Long sharesCount;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp(source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "category_mode", nullable = false, length = 6)
    private String categoryMode = "AUTO";
}
