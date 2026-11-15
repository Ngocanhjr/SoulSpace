package ctu.soulspace.controller;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.dto.response.BlogCreateResponse;
import ctu.soulspace.dto.response.BlogResponse;
import ctu.soulspace.exception.AppException;
import ctu.soulspace.service.BlogService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
public class BlogController {
    private final BlogService blogs;

    public BlogController(BlogService blogs) {
        this.blogs = blogs;
    }

    @GetMapping("/api/v1/soulspaces")
    public ResponseEntity<List<BlogResponse>> feed(Authentication authentication) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(blogs.feed(viewer(authentication)));
    }

    @GetMapping("/api/v1/blogs/{author}/all")
    public ResponseEntity<List<BlogResponse>> own(
            @PathVariable UUID author, Authentication authentication) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(blogs.byAuthor(author, viewer(authentication), true));
    }

    @GetMapping("/api/v1/blogs/{author}/blogs")
    public ResponseEntity<List<BlogResponse>> author(
            @PathVariable UUID author, Authentication authentication) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(blogs.byAuthor(author, viewer(authentication), false));
    }

    @GetMapping("/api/v1/blogs/{id}")
    public ResponseEntity<BlogResponse> detail(
            @PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(blogs.find(id, viewer(authentication)));
    }

    @PostMapping(value = "/api/v1/blogs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BlogCreateResponse create(
            @RequestParam MultiValueMap<String, String> fields,
            MultipartHttpServletRequest request,
            Authentication authentication) {
        Set<String> allowed = Set.of("content", "published", "userId", "username");
        if (!allowed.containsAll(fields.keySet())
                || fields.values().stream().anyMatch(values -> values.size() != 1)
                || !Set.of("files").containsAll(request.getMultiFileMap().keySet())) {
            throw new AppException(ErrorCode.VALIDATION_FAILED);
        }
        UUID actor = viewer(authentication);
        if (actor == null) throw new AppException(ErrorCode.AUTH_REQUIRED);
        String claimed = fields.getFirst("userId");
        if (claimed != null && !actor.toString().equals(claimed))
            throw new AppException(ErrorCode.FORBIDDEN);
        String published = fields.getFirst("published");
        if (published != null && !Set.of("true", "false").contains(published)) {
            throw new AppException(
                    ErrorCode.VALIDATION_FAILED, Map.of("published", "Use true or false"));
        }
        List<MultipartFile> files = request.getFiles("files");
        return blogs.create(actor, fields.getFirst("content"), !"false".equals(published), files);
    }

    private UUID viewer(Authentication authentication) {
        return authentication == null
                        || !authentication.isAuthenticated()
                        || authentication instanceof AnonymousAuthenticationToken
                ? null
                : UUID.fromString(authentication.getName());
    }
}
