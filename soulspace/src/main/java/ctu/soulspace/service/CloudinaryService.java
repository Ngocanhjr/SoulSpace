package ctu.soulspace.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.cloudinary.http5.UploaderStrategy;
import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.exception.AppException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CloudinaryService {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int DOWNLOAD_URL_TTL_SECONDS = 60;
    private final Cloudinary cloudinary;
    private final Uploader uploader;
    private final String namespace;

    public CloudinaryService(
            @Value("${CLOUD_NAME:}") String name,
            @Value("${CLOUD_API_KEY:}") String key,
            @Value("${CLOUD_API_SECRET:}") String secret,
            @Value("${MEDIA_NAMESPACE:}") String namespace) {
        this.namespace = namespace;
        if (name.isBlank()
                || key.isBlank()
                || secret.isBlank()
                || !namespace.matches("[a-zA-Z0-9_-]{1,80}(?:/[a-zA-Z0-9_-]{1,80}){0,4}")) {
            cloudinary = null;
            uploader = null;
            return;
        }
        cloudinary =
                new Cloudinary(
                        Map.of(
                                "cloud_name",
                                name,
                                "api_key",
                                key,
                                "api_secret",
                                secret,
                                "secure",
                                true));
        uploader =
                new Uploader(
                        cloudinary,
                        new UploaderStrategy() {
                            @Override
                            public RequestConfig buildRequestConfig() {
                                return RequestConfig.custom()
                                        .setConnectTimeout(
                                                Timeout.ofMilliseconds(CONNECT_TIMEOUT_MS))
                                        .setConnectionRequestTimeout(
                                                Timeout.ofMilliseconds(CONNECT_TIMEOUT_MS))
                                        .setResponseTimeout(Timeout.ofMilliseconds(READ_TIMEOUT_MS))
                                        .build();
                            }
                        });
    }

    public String publicId(String id) {
        return publicId(id, "BLOG");
    }

    public String publicId(String id, String purpose) {
        requireConfigured();
        String folder =
                switch (purpose) {
                    case "BLOG" -> "blogs";
                    case "AVATAR" -> "avatars";
                    default -> throw new IllegalArgumentException("Unsupported media purpose");
                };
        return namespace + "/" + folder + "/" + id;
    }

    public Upload upload(byte[] bytes, String publicId) {
        requireConfigured();
        try {
            Map<?, ?> result =
                    uploader.upload(
                            bytes,
                            Map.of(
                                    "public_id",
                                    publicId,
                                    "type",
                                    "authenticated",
                                    "resource_type",
                                    "image",
                                    "overwrite",
                                    false,
                                    "unique_filename",
                                    false,
                                    "asset_folder",
                                    publicId.substring(0, publicId.lastIndexOf('/'))));
            String format =
                    "jpg".equals(result.get("format"))
                            ? "jpeg"
                            : String.valueOf(result.get("format"));
            if (!publicId.equals(result.get("public_id"))
                    || !"authenticated".equals(result.get("type"))
                    || !(result.get("asset_id") instanceof String assetId)
                    || assetId.isBlank()
                    || !java.util.Set.of("jpeg", "png").contains(format)
                    || !(result.get("bytes") instanceof Number size)
                    || size.longValue() <= 0) {
                throw new IOException("Invalid upload receipt");
            }
            return new Upload(assetId, format, size.longValue());
        } catch (IOException | RuntimeException exception) {
            throw new AppException(ErrorCode.MEDIA_PROVIDER_ERROR);
        }
    }

    public byte[] download(String publicId, String format) {
        requireConfigured();
        HttpURLConnection connection = null;
        try {
            String url =
                    cloudinary.privateDownload(
                            publicId,
                            "jpeg".equals(format) ? "jpg" : format,
                            Map.of(
                                    "type",
                                    "authenticated",
                                    "attachment",
                                    false,
                                    "expires_at",
                                    Instant.now()
                                            .plusSeconds(DOWNLOAD_URL_TTL_SECONDS)
                                            .getEpochSecond()));
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || !"api.cloudinary.com".equals(uri.getHost())) {
                throw new IOException("Invalid provider host");
            }
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Provider download failed");
            }
            try (var input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(ImageValidator.MAX_BYTES + 1);
                if (bytes.length == 0 || bytes.length > ImageValidator.MAX_BYTES) {
                    throw new IOException("Invalid provider size");
                }
                return bytes;
            }
        } catch (Exception exception) {
            throw new AppException(ErrorCode.MEDIA_PROVIDER_ERROR);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public void destroy(String publicId) {
        requireConfigured();
        if (!owns(publicId)) {
            throw new AppException(ErrorCode.MEDIA_PROVIDER_ERROR);
        }
        try {
            Map<?, ?> result =
                    uploader.destroy(
                            publicId,
                            Map.of(
                                    "type",
                                    "authenticated",
                                    "resource_type",
                                    "image",
                                    "invalidate",
                                    true));
            if (!java.util.Set.of("ok", "not found").contains(result.get("result"))) {
                throw new IOException("Provider deletion failed");
            }
        } catch (IOException | RuntimeException exception) {
            throw new AppException(ErrorCode.MEDIA_PROVIDER_ERROR);
        }
    }

    private void requireConfigured() {
        if (cloudinary == null) throw new AppException(ErrorCode.MEDIA_PROVIDER_ERROR);
    }

    public boolean configured() {
        return cloudinary != null;
    }

    public boolean owns(String publicId) {
        return configured()
                && publicId != null
                && (publicId.startsWith(namespace + "/blogs/")
                        || publicId.startsWith(namespace + "/avatars/"));
    }

    public record Upload(String assetId, String format, long size) {}
}
