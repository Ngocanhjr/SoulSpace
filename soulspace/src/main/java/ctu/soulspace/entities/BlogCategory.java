package ctu.soulspace.entities;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "blog_categories")
@Getter
@Setter
@NoArgsConstructor
public class BlogCategory {
    @EmbeddedId
    private BlogCategoryId id;

    @Column(name = "confidence")
    private Double confidence;
}
