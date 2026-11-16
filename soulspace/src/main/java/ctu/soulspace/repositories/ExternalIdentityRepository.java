package ctu.soulspace.repositories;

import ctu.soulspace.entities.ExternalIdentity;
import ctu.soulspace.entities.ExternalIdentityId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalIdentityRepository
        extends JpaRepository<ExternalIdentity, ExternalIdentityId> {
    Optional<ExternalIdentity> findByUserId(UUID userId);
}
