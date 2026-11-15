package ctu.soulspace.controller;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.dto.response.CommentResponse;
import ctu.soulspace.dto.response.CommentResult;
import ctu.soulspace.dto.response.LikeResponse;
import ctu.soulspace.dto.response.ShareResponse;
import ctu.soulspace.dto.response.SharedBlogResponse;
import ctu.soulspace.exception.AppException;
import ctu.soulspace.service.InteractionService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class InteractionController {
    private final InteractionService interactions;

    public InteractionController(InteractionService interactions) {
        this.interactions = interactions;
    }

    @ModelAttribute
    public void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @GetMapping("/api/v1/blogs/{blog}/comments")
    public List<CommentResponse> comments(@PathVariable UUID blog, Authentication authentication) {
        return interactions.comments(blog, viewer(authentication));
    }

    @PostMapping("/api/v1/blogs/{blog}/comments")
    public CommentResult comment(
            @PathVariable UUID blog,
            @RequestBody CommentInput input,
            Authentication authentication) {
        return interactions.addComment(
                actor(authentication, input.userId()), blog, null, input.content());
    }

    @PostMapping("/api/v1/blogs/{blog}/comments/{parent}/reply")
    public CommentResult reply(
            @PathVariable UUID blog,
            @PathVariable UUID parent,
            @RequestBody CommentInput input,
            Authentication authentication) {
        return interactions.addComment(
                actor(authentication, input.userId()), blog, parent, input.content());
    }

    @PatchMapping("/api/v1/blogs/{blog}/comments/{comment}")
    public CommentResult edit(
            @PathVariable UUID blog,
            @PathVariable UUID comment,
            @RequestBody EditInput input,
            Authentication authentication) {
        return interactions.changeComment(
                actor(authentication, null),
                blog,
                comment,
                input.content(),
                input.expectedVersion(),
                false);
    }

    @DeleteMapping("/api/v1/blogs/{blog}/comments/{comment}")
    public CommentResult delete(
            @PathVariable UUID blog,
            @PathVariable UUID comment,
            @RequestBody VersionInput input,
            Authentication authentication) {
        return interactions.changeComment(
                actor(authentication, null), blog, comment, null, input.expectedVersion(), true);
    }

    @PostMapping("/api/v1/blogs/{blog}/like")
    public LikeResponse blogLike(
            @PathVariable UUID blog, @RequestBody LikeInput input, Authentication authentication) {
        return interactions.setLike(
                actor(authentication, input.userId()), blog, null, desired(input.liked()));
    }

    @PostMapping("/api/v1/blogs/{blog}/comments/{comment}/like")
    public LikeResponse commentLike(
            @PathVariable UUID blog,
            @PathVariable UUID comment,
            @RequestBody LikeInput input,
            Authentication authentication) {
        return interactions.setLike(
                actor(authentication, input.userId()), blog, comment, desired(input.liked()));
    }

    @PostMapping("/api/v1/blogs/{blog}/share")
    public ShareResponse share(
            @PathVariable UUID blog,
            @RequestBody(required = false) ActorInput input,
            Authentication authentication) {
        return interactions.share(
                actor(authentication, input == null ? null : input.userId()), blog);
    }

    @GetMapping("/api/v1/users/{owner}/shared-blogs")
    public List<SharedBlogResponse> shares(
            @PathVariable UUID owner, Authentication authentication) {
        return interactions.shares(owner, viewer(authentication));
    }

    @DeleteMapping(
            value = "/api/v1/users/{owner}/shared-blogs/{blog}",
            produces = "text/plain;charset=UTF-8")
    public String unshare(
            @PathVariable UUID owner, @PathVariable UUID blog, Authentication authentication) {
        interactions.unshare(actor(authentication, null), owner, blog);
        return "Shared blog removed successfully";
    }

    private boolean desired(JsonNode value) {
        if (value == null || !value.isBoolean())
            throw new AppException(ErrorCode.VALIDATION_FAILED);
        return value.booleanValue();
    }

    private UUID actor(Authentication authentication, UUID claimed) {
        UUID actor = viewer(authentication);
        if (actor == null) throw new AppException(ErrorCode.AUTH_REQUIRED);
        if (claimed != null && !claimed.equals(actor)) throw new AppException(ErrorCode.FORBIDDEN);
        return actor;
    }

    private UUID viewer(Authentication authentication) {
        return authentication == null
                        || !authentication.isAuthenticated()
                        || authentication instanceof AnonymousAuthenticationToken
                ? null
                : UUID.fromString(authentication.getName());
    }

    public record CommentInput(String content, UUID userId) {}

    public record LikeInput(JsonNode liked, UUID userId) {}

    public record ActorInput(UUID userId) {}

    public record EditInput(String content, JsonNode expectedVersion) {}

    public record VersionInput(JsonNode expectedVersion) {}
}
