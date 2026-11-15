package ctu.soulspace.service;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.entities.BlogImage;
import ctu.soulspace.entities.MediaAsset;
import ctu.soulspace.entities.MediaCleanupJob;
import ctu.soulspace.exception.AppException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MediaService {
    private static final int MAX_TRACKED_USERS = 4_096;
    private static final int MAX_CONCURRENT_UPLOADS = 2;
    private static final int MAX_UPLOADS_PER_HOUR = 30;
    private static final long UPLOAD_WINDOW_MS = 3_600_000;
    private static final int UNCERTAIN_UPLOAD_GRACE_SECONDS = 3_600;
    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    @PersistenceContext private EntityManager em;
    private final CloudinaryService provider;
    private final ImageValidator validator;
    private final TransactionTemplate transactions;
    private final Map<UUID, UploadLimit> limits = new HashMap<>();

    public MediaService(
            CloudinaryService provider,
            ImageValidator validator,
            PlatformTransactionManager transactionManager) {
        this.provider = provider;
        this.validator = validator;
        transactions = new TransactionTemplate(transactionManager);
    }

    public List<UploadedImage> upload(
            UUID actor, List<MultipartFile> files, List<String> failures) {
        if (files.isEmpty()) return List.of();
        beginUpload(actor);
        List<UploadedImage> uploaded = new ArrayList<>();
        try {
            List<ImageValidator.Image> images = validator.validate(files);
            if (!provider.configured()) {
                for (int i = 0; i < images.size(); i++) failures.add("image-" + (i + 1));
                return List.of();
            }
            for (int i = 0; i < images.size(); i++) {
                ImageValidator.Image image = images.get(i);
                // Persist a receipt before calling the provider, including for an uncertain
                // timeout.
                MediaAsset asset =
                        transactions.execute(
                                status -> {
                                    MediaAsset pending = new MediaAsset();
                                    pending.setOwnerId(actor);
                                    pending.setPurpose("BLOG");
                                    pending.setState("PENDING");
                                    pending.setProviderPublicId(
                                            provider.publicId(UUID.randomUUID().toString()));
                                    em.persist(pending);
                                    em.flush();
                                    return pending;
                                });
                try {
                    CloudinaryService.Upload receipt =
                            provider.upload(image.bytes(), asset.getProviderPublicId());
                    if (!image.format().equals(receipt.format())
                            || receipt.size() > ImageValidator.MAX_BYTES) {
                        throw new AppException(ErrorCode.MEDIA_PROVIDER_ERROR);
                    }
                    uploaded.add(new UploadedImage(asset.getId(), receipt));
                } catch (AppException exception) {
                    failures.add("image-" + (i + 1));
                    log.warn("event=image_upload_failed asset_id={}", asset.getId());
                    queueUnconfirmed(asset.getId());
                }
            }
            return uploaded;
        } catch (RuntimeException exception) {
            discard(uploaded);
            throw exception;
        } finally {
            endUpload(actor);
        }
    }

    public void discard(List<UploadedImage> images) {
        for (UploadedImage image :
                images.stream().sorted(Comparator.comparing(UploadedImage::id)).toList()) {
            try {
                transactions.executeWithoutResult(
                        status -> {
                            MediaAsset asset =
                                    em.find(
                                            MediaAsset.class,
                                            image.id(),
                                            LockModeType.PESSIMISTIC_WRITE);
                            if (asset == null || !"PENDING".equals(asset.getState())) return;
                            asset.setProviderAssetId(image.receipt().assetId());
                            asset.setFormat(image.receipt().format());
                            asset.setSizeBytes(image.receipt().size());
                            em.flush();
                            enqueue(image.id());
                        });
            } catch (RuntimeException exception) {
                // The stale receipt scanner can retry once the database is available again.
                log.warn("event=media_cleanup_enqueue_failed asset_id={}", image.id());
            }
        }
    }

    private void queueUnconfirmed(UUID id) {
        try {
            transactions.executeWithoutResult(status -> enqueue(id));
        } catch (RuntimeException exception) {
            log.warn("event=media_cleanup_enqueue_failed asset_id={}", id);
        }
    }

    // Called inside the blog transaction, after locking the actor and creating the blog.
    public List<String> attach(UUID actor, UUID blogId, List<UploadedImage> images) {
        Map<UUID, MediaAsset> assets = new HashMap<>();
        images.stream()
                .sorted(Comparator.comparing(UploadedImage::id))
                .forEach(
                        image -> {
                            MediaAsset asset =
                                    em.find(
                                            MediaAsset.class,
                                            image.id(),
                                            LockModeType.PESSIMISTIC_WRITE);
                            if (asset == null
                                    || !actor.equals(asset.getOwnerId())
                                    || !"BLOG".equals(asset.getPurpose())
                                    || !"PENDING".equals(asset.getState()))
                                throw new AppException(ErrorCode.MEDIA_NOT_FOUND);
                            assets.put(image.id(), asset);
                        });
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            UploadedImage image = images.get(i);
            MediaAsset asset = assets.get(image.id());
            asset.setProviderAssetId(image.receipt().assetId());
            asset.setFormat(image.receipt().format());
            asset.setSizeBytes(image.receipt().size());
            asset.setState("ATTACHED");
            em.flush();
            BlogImage link = new BlogImage();
            link.setAssetId(asset.getId());
            link.setBlogId(blogId);
            link.setPosition((short) i);
            em.persist(link);
            urls.add("/api/v1/media/" + asset.getId());
        }
        return urls;
    }

    public ImageData read(UUID id, UUID viewer) {
        MediaAsset asset =
                transactions.execute(
                        status -> {
                            List<MediaAsset> visible =
                                    em.createQuery(
                                                    """
                select m from MediaAsset m where m.id = :id and m.ownerId is not null
                and m.providerAssetId is not null and (
                    (m.state = 'PENDING' and m.ownerId = :viewer)
                    or (m.state = 'ATTACHED' and (
                        exists (select u.id from User u where u.avatarMediaId = m.id)
                        or exists (select b.id from Blog b, BlogImage i
                            where i.assetId = m.id and i.blogId = b.id
                            and (b.published = true or b.authorId = :viewer))
                    ))
                )
                """,
                                                    MediaAsset.class)
                                            .setParameter("id", id)
                                            .setParameter("viewer", viewer)
                                            .getResultList();
                            if (visible.isEmpty())
                                throw new AppException(ErrorCode.MEDIA_NOT_FOUND);
                            return visible.getFirst();
                        });
        // The signed URL stays inside the backend; no database lock spans the network call.
        return new ImageData(
                provider.download(asset.getProviderPublicId(), asset.getFormat()),
                asset.getFormat());
    }

    public void enqueue(UUID id) {
        MediaAsset asset = em.find(MediaAsset.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (asset == null || !"PENDING".equals(asset.getState())) return;
        if (!em.createQuery("select i.assetId from BlogImage i where i.assetId = :id", UUID.class)
                        .setParameter("id", id)
                        .getResultList()
                        .isEmpty()
                || !em.createQuery(
                                "select u.id from User u where u.avatarMediaId = :id", UUID.class)
                        .setParameter("id", id)
                        .getResultList()
                        .isEmpty()) return;
        asset.setState("DELETE_PENDING");
        em.flush();
        List<?> existing =
                em.createQuery("select j.id from MediaCleanupJob j where j.assetId = :id")
                        .setParameter("id", id)
                        .getResultList();
        if (existing.isEmpty()) {
            MediaCleanupJob job = new MediaCleanupJob();
            job.setAssetId(id);
            job.setAvailableAt(
                    asset.getProviderAssetId() == null
                            ? Instant.now().plusSeconds(UNCERTAIN_UPLOAD_GRACE_SECONDS)
                            : Instant.now());
            em.persist(job);
        }
    }

    private synchronized void beginUpload(UUID actor) {
        long now = System.currentTimeMillis();
        limits.entrySet()
                .removeIf(entry -> entry.getValue().active == 0 && entry.getValue().expires <= now);
        UploadLimit limit = limits.get(actor);
        if (limit == null) {
            if (limits.size() >= MAX_TRACKED_USERS) throw new AppException(ErrorCode.RATE_LIMITED);
            limit = new UploadLimit();
            limit.expires = now + UPLOAD_WINDOW_MS;
            limits.put(actor, limit);
        }
        if (limit.active >= MAX_CONCURRENT_UPLOADS || limit.requests >= MAX_UPLOADS_PER_HOUR) {
            throw new AppException(ErrorCode.RATE_LIMITED);
        }
        limit.active++;
        limit.requests++;
    }

    private synchronized void endUpload(UUID actor) {
        limits.get(actor).active--;
    }

    private static class UploadLimit {
        long expires;
        int requests;
        int active;
    }

    public record UploadedImage(UUID id, CloudinaryService.Upload receipt) {}

    public record ImageData(byte[] bytes, String format) {}
}
