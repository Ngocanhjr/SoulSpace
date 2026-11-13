package ctu.soulspace.entities;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "external_identities", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "provider"}))
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ExternalIdentity {
    @EmbeddedId
    private ExternalIdentityId id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
}
