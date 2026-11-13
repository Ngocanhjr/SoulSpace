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
public class CommentLikeId implements Serializable {
    @Column(name = "comment_id", nullable = false, updatable = false)
    private UUID commentId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
}
