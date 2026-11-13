package ctu.soulspace.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
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
public class ExternalIdentityId implements Serializable {
    @Column(name = "provider", nullable = false, length = 20, updatable = false)
    private String provider;

    @Column(name = "subject", nullable = false, length = 255, updatable = false)
    private String subject;
}
