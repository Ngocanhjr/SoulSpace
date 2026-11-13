package ctu.soulspace.entities;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;

@Entity
@Table(name = "blog_shares")
@Getter
@Setter
@NoArgsConstructor
public class BlogShare {
    @EmbeddedId
    private BlogShareId id;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "shared_at", nullable = false, updatable = false)
    private Instant sharedAt;
}
