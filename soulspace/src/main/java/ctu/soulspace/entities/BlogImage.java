package ctu.soulspace.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "blog_images")
@Getter
@Setter
@NoArgsConstructor
public class BlogImage {
    @Id
    @Column(name = "asset_id", nullable = false, updatable = false)
    private UUID assetId;

    @Column(name = "blog_id", nullable = false)
    private UUID blogId;

    @Column(name = "position", nullable = false)
    private short position;
}
