package ctu.soulspace.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "media_assets")
@Getter
@Setter
@NoArgsConstructor
public class MediaAsset {
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "purpose", nullable = false, length = 16)
    private String purpose;

    @Column(name = "state", nullable = false, length = 20)
    private String state;

    @Column(name = "provider_public_id", nullable = false, unique = true, columnDefinition = "TEXT")
    private String providerPublicId;

    @Column(name = "provider_asset_id", unique = true, columnDefinition = "TEXT")
    private String providerAssetId;

    @Column(name = "format", length = 8)
    private String format;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp(source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
