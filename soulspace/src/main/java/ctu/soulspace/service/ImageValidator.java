package ctu.soulspace.service;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.exception.AppException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ImageValidator {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_IMAGES = 10;
    private static final long MAX_PIXELS = 25_000_000;
    private static final int PNG_SIGNATURE_BYTES = 8;
    private static final int PNG_CHUNK_OVERHEAD_BYTES = 12;

    public List<Image> validate(List<MultipartFile> files) {
        if (files.size() > MAX_IMAGES)
            throw new AppException(
                    ErrorCode.VALIDATION_FAILED,
                    java.util.Map.of("files", "A post can contain at most 10 images"));
        return files.stream().map(this::validate).toList();
    }

    private Image validate(MultipartFile file) {
        if (file.getSize() > MAX_BYTES) throw new AppException(ErrorCode.PAYLOAD_TOO_LARGE);
        String name = file.getOriginalFilename() == null ? "image" : file.getOriginalFilename();
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String expected =
                switch (extension) {
                    case "jpg", "jpeg" -> "jpeg";
                    case "png" -> "png";
                    default -> throw new AppException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
                };
        if (!("image/" + expected).equals(file.getContentType()) || file.isEmpty()) {
            throw new AppException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
        ImageReader reader = null;
        try {
            byte[] bytes;
            try (var input = file.getInputStream()) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) throw new AppException(ErrorCode.PAYLOAD_TOO_LARGE);
            if ("png".equals(expected)) rejectAnimatedPng(bytes);
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IOException("Invalid image");
                reader = readers.next();
                reader.setInput(input);
                if (!expected.equals(reader.getFormatName().toLowerCase(Locale.ROOT))) {
                    throw new IOException("Image format mismatch");
                }
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels < 1
                        || pixels > MAX_PIXELS
                        || reader.getNumImages(true) != 1
                        || reader.read(0) == null)
                    throw new IOException("Invalid image dimensions or frames");
            }
            // Return an ordinal in API failures, never a client-supplied path or filename.
            return new Image(bytes, expected);
        } catch (IOException | IllegalArgumentException exception) {
            throw new AppException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        } finally {
            if (reader != null) reader.dispose();
        }
    }

    private void rejectAnimatedPng(byte[] bytes) throws IOException {
        int position = PNG_SIGNATURE_BYTES;
        while (position + PNG_CHUNK_OVERHEAD_BYTES <= bytes.length) {
            long length = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, position, 4).getInt());
            if (length > bytes.length - position - (long) PNG_CHUNK_OVERHEAD_BYTES)
                throw new IOException("Invalid PNG chunk");
            String type = new String(bytes, position + 4, 4, StandardCharsets.US_ASCII);
            if (java.util.Set.of("acTL", "fcTL", "fdAT").contains(type))
                throw new IOException("Animated PNG");
            position += (int) length + PNG_CHUNK_OVERHEAD_BYTES;
            if ("IEND".equals(type)) return;
        }
        throw new IOException("Incomplete PNG");
    }

    public record Image(byte[] bytes, String format) {}
}
