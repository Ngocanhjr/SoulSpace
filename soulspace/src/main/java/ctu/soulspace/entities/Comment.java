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
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "comments")
@Getter
@Setter
@NoArgsConstructor
public class Comment {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "blog_id", nullable = false, updatable = false)
    private UUID blogId;

    @Column(name = "author_id")
    private UUID authorId;

    @Column(name = "parent_id", updatable = false)
    private UUID parentId;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Setter(AccessLevel.NONE)
    @Column(name = "likes_count", nullable = false, insertable = false, updatable = false)
    private Long likesCount;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Setter(AccessLevel.NONE)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Setter(AccessLevel.NONE)
    @Column(name = "version", nullable = false, insertable = false, updatable = false)
    private Long version;
}
