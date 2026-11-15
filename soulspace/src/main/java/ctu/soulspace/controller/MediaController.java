package ctu.soulspace.controller;

import ctu.soulspace.service.MediaService;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MediaController {
    private final MediaService media;

    public MediaController(MediaService media) {
        this.media = media;
    }

    @GetMapping("/api/v1/media/{id}")
    public ResponseEntity<byte[]> read(@PathVariable UUID id, Authentication authentication) {
        UUID viewer =
                authentication == null || authentication instanceof AnonymousAuthenticationToken
                        ? null
                        : UUID.fromString(authentication.getName());
        MediaService.ImageData image = media.read(id, viewer);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        "Content-Disposition",
                        "inline; filename=\"" + id + "." + image.format() + "\"")
                .contentType(
                        "jpeg".equals(image.format()) ? MediaType.IMAGE_JPEG : MediaType.IMAGE_PNG)
                .body(image.bytes());
    }
}
