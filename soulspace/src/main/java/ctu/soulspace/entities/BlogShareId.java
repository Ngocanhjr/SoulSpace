package ctu.soulspace.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EqualsAndHashCode
public class BlogShareId implements Serializable {
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "blog_id", nullable = false, updatable = false)
    private UUID blogId;
}
