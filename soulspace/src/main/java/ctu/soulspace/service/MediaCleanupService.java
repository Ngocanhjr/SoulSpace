package ctu.soulspace.service;

import ctu.soulspace.entities.MediaAsset;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@EnableScheduling
public class MediaCleanupService {
    private static final int SCAN_INTERVAL_MS = 60_000;
    private static final int BATCH_SIZE = 20;
    private static final int MAX_ATTEMPTS = 5;
    private static final int LEASE_SECONDS = 120;
    private static final int UNCONFIRMED_MAX_AGE_SECONDS = 600;
    private static final int CONFIRMED_MAX_AGE_SECONDS = 86_400;
    private static final Logger log = LoggerFactory.getLogger(MediaCleanupService.class);
    private static final int[] BACKOFF_SECONDS = {60, 300, 1800, 7200};
    @PersistenceContext private EntityManager em;
    private final TransactionTemplate transactions;
    private final MediaService media;
    private final CloudinaryService provider;

    public MediaCleanupService(
            PlatformTransactionManager manager, MediaService media, CloudinaryService provider) {
        transactions = new TransactionTemplate(manager);
        this.media = media;
        this.provider = provider;
    }

    @Scheduled(
            fixedDelay = SCAN_INTERVAL_MS,
            initialDelayString = "${media.cleanup.initial-delay-ms:" + SCAN_INTERVAL_MS + "}")
    public void run() {
        if (!provider.configured()) return;
        try {
            scan();
            for (int i = 0; i < BATCH_SIZE; i++) {
                Claim claim = claim();
                if (claim == null) break;
                String publicId = prepare(claim);
                if (publicId == null) continue;
                boolean success = false;
                try {
                    provider.destroy(publicId);
                    success = true;
                } catch (RuntimeException exception) {
                    log.warn(
                            "event=media_cleanup_failed asset_id={} attempt={}",
                            claim.assetId(),
                            claim.attempts());
                }
                finish(claim, success);
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "event=media_cleanup_unavailable error_type={}",
                    exception.getClass().getSimpleName());
        }
    }

    private void scan() {
        transactions.executeWithoutResult(
                status -> {
                    em.createNativeQuery(
                                    """
                UPDATE media_cleanup_jobs
                SET status = CASE WHEN attempts < :maxAttempts THEN 'PENDING' ELSE 'DEAD' END,
                    lease_until = NULL, available_at = clock_timestamp() + interval '1 minute',
                    last_error = 'LEASE_EXPIRED'
                WHERE status = 'RUNNING' AND lease_until <= clock_timestamp()
                """)
                            .setParameter("maxAttempts", MAX_ATTEMPTS)
                            .executeUpdate();
                });
        List<UUID> ids =
                transactions.execute(
                        status ->
                                em.createQuery(
                                                """
                select m.id from MediaAsset m where m.state = 'PENDING' and (
                    (m.providerAssetId is null and m.createdAt < :unconfirmed)
                    or (m.providerAssetId is not null and m.createdAt < :confirmed)) order by m.id
                """,
                                                UUID.class)
                                        .setParameter(
                                                "unconfirmed",
                                                Instant.now()
                                                        .minusSeconds(UNCONFIRMED_MAX_AGE_SECONDS))
                                        .setParameter(
                                                "confirmed",
                                                Instant.now()
                                                        .minusSeconds(CONFIRMED_MAX_AGE_SECONDS))
                                        .setMaxResults(BATCH_SIZE)
                                        .getResultList());
        for (UUID id : ids) transactions.executeWithoutResult(status -> media.enqueue(id));
    }

    private Claim claim() {
        return transactions.execute(
                status -> {
                    List<?> rows =
                            em.createNativeQuery(
                                            """
                WITH ready AS (
                    SELECT id FROM media_cleanup_jobs
                    WHERE status = 'PENDING' AND available_at <= clock_timestamp() AND attempts < :maxAttempts
                    ORDER BY available_at, id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE media_cleanup_jobs j
                SET status = 'RUNNING', attempts = j.attempts + 1,
                    lease_until = clock_timestamp() + make_interval(secs => :leaseSeconds)
                FROM ready WHERE j.id = ready.id
                RETURNING j.id, j.asset_id, j.attempts, j.lease_until
                """)
                                    .setParameter("maxAttempts", MAX_ATTEMPTS)
                                    .setParameter("leaseSeconds", LEASE_SECONDS)
                                    .getResultList();
                    if (rows.isEmpty()) return null;
                    Object[] row = (Object[]) rows.getFirst();
                    Instant lease =
                            row[3] instanceof OffsetDateTime time
                                    ? time.toInstant()
                                    : row[3] instanceof java.sql.Timestamp time
                                            ? time.toInstant()
                                            : (Instant) row[3];
                    return new Claim(
                            (UUID) row[0], (UUID) row[1], ((Number) row[2]).intValue(), lease);
                });
    }

    private String prepare(Claim claim) {
        return transactions.execute(
                status -> {
                    MediaAsset asset =
                            em.find(
                                    MediaAsset.class,
                                    claim.assetId(),
                                    LockModeType.PESSIMISTIC_WRITE);
                    if (asset == null || !ownsLease(claim)) return null;
                    boolean linked =
                            !em.createNativeQuery(
                                            """
                SELECT 1 FROM blog_images WHERE asset_id = :id
                UNION ALL SELECT 1 FROM users WHERE avatar_media_id = :id
                """)
                                    .setParameter("id", claim.assetId())
                                    .setMaxResults(1)
                                    .getResultList()
                                    .isEmpty();
                    if (!"DELETE_PENDING".equals(asset.getState())
                            || linked
                            || !provider.owns(asset.getProviderPublicId())) {
                        em.createNativeQuery(
                                        """
                    UPDATE media_cleanup_jobs SET status = 'DEAD', lease_until = NULL,
                        last_error = 'CLEANUP_SAFETY_CHECK_FAILED' WHERE id = :id
                    """)
                                .setParameter("id", claim.id())
                                .executeUpdate();
                        return null;
                    }
                    return asset.getProviderPublicId();
                });
    }

    private void finish(Claim claim, boolean success) {
        transactions.executeWithoutResult(
                status -> {
                    MediaAsset asset =
                            em.find(
                                    MediaAsset.class,
                                    claim.assetId(),
                                    LockModeType.PESSIMISTIC_WRITE);
                    if (asset == null || !ownsLease(claim)) return;
                    if (success) {
                        em.remove(asset);
                        log.info("event=media_cleaned asset_id={}", claim.assetId());
                    } else {
                        em.createNativeQuery(
                                        """
                    UPDATE media_cleanup_jobs SET status = :status, lease_until = NULL,
                        available_at = clock_timestamp() + make_interval(secs => :delay),
                        last_error = 'PROVIDER_ERROR' WHERE id = :id
                    """)
                                .setParameter(
                                        "status",
                                        claim.attempts() >= MAX_ATTEMPTS ? "DEAD" : "PENDING")
                                .setParameter(
                                        "delay",
                                        claim.attempts() >= MAX_ATTEMPTS
                                                ? 0
                                                : BACKOFF_SECONDS[claim.attempts() - 1])
                                .setParameter("id", claim.id())
                                .executeUpdate();
                    }
                });
    }

    private boolean ownsLease(Claim claim) {
        boolean owned =
                !em.createNativeQuery(
                                """
            SELECT id FROM media_cleanup_jobs
            WHERE id = :id AND status = 'RUNNING' AND attempts = :attempts
                AND lease_until = :lease FOR UPDATE
            """)
                        .setParameter("id", claim.id())
                        .setParameter("attempts", claim.attempts())
                        .setParameter("lease", claim.lease())
                        .getResultList()
                        .isEmpty();
        // Evaluate the clock after acquiring the job lock, including time spent waiting for it.
        return owned
                && !em.createNativeQuery(
                                """
            SELECT id FROM media_cleanup_jobs WHERE id = :id AND lease_until > clock_timestamp()
            """)
                        .setParameter("id", claim.id())
                        .getResultList()
                        .isEmpty();
    }

    private record Claim(UUID id, UUID assetId, int attempts, Instant lease) {}
}
