package ctu.soulspace.blog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.entities.User;
import ctu.soulspace.exception.AppException;
import ctu.soulspace.repositories.UserRepository;
import ctu.soulspace.service.CloudinaryService;
import ctu.soulspace.service.MediaService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "SOULSPACE_BLOG_TEST_DB", matches = "true")
@SpringBootTest(
        properties = {
            "media.cleanup.initial-delay-ms=3600000",
            "spring.datasource.url=${BLOG_TEST_DB_URL}",
            "spring.datasource.username=soulspace_runtime",
            "spring.datasource.password=${BLOG_TEST_RUNTIME_PASSWORD}",
            "spring.flyway.url=${BLOG_TEST_DB_URL}",
            "spring.flyway.user=soulspace_migrator",
            "spring.flyway.password=${BLOG_TEST_MIGRATOR_PASSWORD}"
        })
@ActiveProfiles("test")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ProfileApiTests {
    private static final String PASSWORD = "aaaa";
    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper mapper;
    @Autowired private UserRepository users;
    @MockitoSpyBean private PasswordEncoder encoder;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CloudinaryService cloud;
    @MockitoSpyBean private MediaService media;
    private User owner;
    private User other;
    private Session session;

    @BeforeEach
    void setup() throws Exception {
        owner = user();
        other = user();
        session = login(owner, PASSWORD);
        when(cloud.configured()).thenReturn(true);
        when(cloud.owns(anyString()))
                .thenAnswer(call -> call.<String>getArgument(0).startsWith("soulspace/test/"));
        when(cloud.publicId(anyString()))
                .thenAnswer(call -> "soulspace/test/blogs/" + call.getArgument(0));
        when(cloud.publicId(anyString(), anyString()))
                .thenAnswer(call -> "soulspace/test/avatars/" + call.getArgument(0));
        when(cloud.upload(any(byte[].class), anyString()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return new CloudinaryService.Upload(
                                    UUID.randomUUID().toString(),
                                    "png",
                                    call.<byte[]>getArgument(0).length);
                        });
        when(cloud.download(anyString(), anyString())).thenReturn(new byte[] {1, 2, 3});
    }

    @Test
    void profilePatchPreservesAbsentFieldsClearsDobAndRejectsSpoofedFields() throws Exception {
        write(session, patch(userPath()), "{\"fullname\":\"  Updated name  \"}", 200)
                .andExpect(jsonPath("$.fullname").value("Updated name"))
                .andExpect(jsonPath("$.username").value(owner.getUsername()))
                .andExpect(jsonPath("$.dob").value("2000-01-01"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        write(session, patch(userPath()), "{\"dob\":null}", 200)
                .andExpect(jsonPath("$.dob").isEmpty());
        for (String body :
                new String[] {
                    "{\"fullname\":null}",
                    "{\"username\":\"\"}",
                    "{\"dob\":\"2020-02-30\"}",
                    "{\"dob\":\"2999-01-01\"}",
                    "{\"password\":\"bbbb\"}",
                    "{\"id\":\"" + other.getId() + "\",\"fullname\":\"x\"}",
                    "{\"userAvatarUrl\":\"https://example.com/x\",\"fullname\":\"x\"}"
                }) {
            write(session, patch(userPath()), body, 400);
        }
        write(session, patch("/api/v1/users/" + other.getId()), "{\"fullname\":\"x\"}", 403);
        mvc.perform(client(get("/api/v1/users/patch/" + owner.getId())))
                .andExpect(status().isUnauthorized());
        mvc.perform(client(get(userPath())).session(session.session()))
                .andExpect(jsonPath("$.fullname").value("Updated name"));
    }

    @Test
    void usernameConflictRollsBackOtherChangesAndSameUsernameIsAllowed() throws Exception {
        write(
                session,
                patch(userPath()),
                mapper.writeValueAsString(Map.of("username", owner.getUsername())),
                200);
        write(
                        session,
                        patch(userPath()),
                        mapper.writeValueAsString(
                                Map.of(
                                        "username",
                                        other.getUsername(),
                                        "fullname",
                                        "Must rollback")),
                        409)
                .andExpect(jsonPath("$.code").value("USERNAME_EXISTS"));
        assertThat(users.findById(owner.getId()).orElseThrow().getFullname())
                .isEqualTo("Profile test");
    }

    @Test
    void passwordChangeRequiresProofAndRevokesEverySessionOnlyAfterCommit() throws Exception {
        Session second = login(owner, PASSWORD);
        write(
                        session,
                        put(userPath()),
                        "{\"fullname\":\"Must rollback\",\"password\":\"bbbb\",\"currentPassword\":\"wrong\"}",
                        401)
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(client(get("/api/v1/auth/me")).session(session.session()))
                .andExpect(status().isOk());
        assertThat(users.findById(owner.getId()).orElseThrow().getFullname())
                .isEqualTo("Profile test");
        write(
                session,
                put(userPath()),
                "{\"password\":\"bbbb\",\"currentPassword\":\"aaaa\"}",
                200);
        assertThat(session.session().isInvalid()).isTrue();
        mvc.perform(client(get("/api/v1/auth/me")).session(second.session()))
                .andExpect(status().isUnauthorized());
        assertThat(
                        encoder.matches(
                                "bbbb",
                                users.findById(owner.getId()).orElseThrow().getPasswordHash()))
                .isTrue();
        login(owner, "bbbb");
    }

    @Test
    void sensitiveOperationsRejectGoogleProofBypassAndThrottlePasswordAttempts() throws Exception {
        write(session, delete(userPath()), "{\"reauthProof\":\"fake\"}", 403);
        write(
                session,
                delete(userPath()),
                "{\"reauthProof\":\"fake\",\"currentPassword\":\"aaaa\"}",
                400);
        for (int attempt = 0; attempt < 10; attempt++)
            write(
                    session,
                    put(userPath()),
                    "{\"password\":\"bbbb\",\"currentPassword\":\"wrong\"}",
                    401);
        write(
                session,
                put(userPath()),
                "{\"password\":\"bbbb\",\"currentPassword\":\"wrong\"}",
                429);
        assertThat(users.existsById(owner.getId())).isTrue();
    }

    @Test
    void avatarReplacementAndRemovalQueueOldAssetsAndKeepDefaultShared() throws Exception {
        JsonNode first = avatar();
        UUID oldId =
                UUID.fromString(
                        first.get("userAvatarUrl").asText().substring("/api/v1/media/".length()));
        mvc.perform(client(get("/api/v1/media/" + oldId))).andExpect(status().isOk());
        JsonNode second = avatar();
        assertThat(first.get("userAvatarUrl").asText())
                .isNotEqualTo(second.get("userAvatarUrl").asText());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from media_cleanup_jobs where asset_id = ?",
                                Long.class,
                                oldId))
                .isEqualTo(1);
        mvc.perform(client(get("/api/v1/media/" + oldId))).andExpect(status().isNotFound());
        write(session, delete(userPath() + "/avatar"), null, 200);
        write(session, delete(userPath() + "/avatar"), null, 200);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from media_cleanup_jobs j join media_assets m on m.id = j.asset_id where m.owner_id = ?",
                                Long.class,
                                owner.getId()))
                .isEqualTo(2);
        mvc.perform(client(get(userPath())))
                .andExpect(
                        jsonPath("$.userAvatarUrl")
                                .value(ctu.soulspace.constants.MediaConstants.DEFAULT_AVATAR_URL));
    }

    @Test
    void avatarRejectsOtherOwnersBadFilesAndProviderFailureKeepsOldAvatar() throws Exception {
        avatar();
        UUID previous = users.findById(owner.getId()).orElseThrow().getAvatarMediaId();
        write(
                session,
                multipart(HttpMethod.PATCH, "/api/v1/users/" + other.getId() + "/avatar")
                        .file(png("file")),
                null,
                403);
        write(
                session,
                multipart(HttpMethod.PATCH, userPath() + "/avatar").file(png("unexpected")),
                null,
                400);
        write(
                session,
                multipart(HttpMethod.PATCH, userPath() + "/avatar")
                        .file(
                                new MockMultipartFile(
                                        "file", "bad.svg", "image/svg+xml", "bad".getBytes())),
                null,
                415);
        when(cloud.upload(any(byte[].class), anyString()))
                .thenThrow(new AppException(ErrorCode.MEDIA_PROVIDER_ERROR));
        write(
                session,
                multipart(HttpMethod.PATCH, userPath() + "/avatar").file(png("file")),
                null,
                502);
        assertThat(users.findById(owner.getId()).orElseThrow().getAvatarMediaId())
                .isEqualTo(previous);
    }

    @Test
    void cleanupFailureRollsBackAvatarAndAccountDeletion() throws Exception {
        avatar();
        UUID previous = users.findById(owner.getId()).orElseThrow().getAvatarMediaId();
        doThrow(new IllegalStateException("queue unavailable")).when(media).enqueue(previous);
        write(session, delete(userPath() + "/avatar"), null, 500);
        assertThat(users.findById(owner.getId()).orElseThrow().getAvatarMediaId())
                .isEqualTo(previous);
        write(session, delete(userPath()), "{\"currentPassword\":\"aaaa\"}", 500);
        assertThat(users.existsById(owner.getId())).isTrue();
        mvc.perform(client(get("/api/v1/auth/me")).session(session.session()))
                .andExpect(status().isOk());
    }

    @Test
    void editBlogCommitsTextAccessAndImageOrderTogether() throws Exception {
        JsonNode created = create("original", png("files"), png("files"));
        String id = created.get("blogId").asText();
        String removed = created.get("successUrls").get(0).asText();
        var result =
                write(
                        session,
                        multipart(HttpMethod.PATCH, "/api/v1/blogs-details")
                                .file(png("newImages"))
                                .param("blogId", id)
                                .param("content", "updated")
                                .param("published", "false")
                                .param("removeImagesUrl", removed),
                        null,
                        200);
        JsonNode updated = json(result.andReturn());
        assertThat(updated.get("successUrls").size()).isEqualTo(2);
        assertThat(updated.get("failedDeleteFiles").size()).isZero();
        mvc.perform(client(get("/api/v1/blogs/" + id))).andExpect(status().isNotFound());
        assertThat(
                        jdbc.queryForList(
                                "select position from blog_images where blog_id = ? order by position",
                                Short.class,
                                UUID.fromString(id)))
                .containsExactly((short) 0, (short) 1);
        assertThat(
                        jdbc.queryForObject(
                                "select state from media_assets where id = ?",
                                String.class,
                                UUID.fromString(removed.substring("/api/v1/media/".length()))))
                .isEqualTo("DELETE_PENDING");
        write(
                session,
                patch("/api/v1/blogs/access"),
                "{\"blogId\":\"" + id + "\",\"published\":true}",
                200);
        mvc.perform(client(get("/api/v1/blogs/" + id)))
                .andExpect(jsonPath("$.content").value("updated"));
        write(
                        session,
                        multipart(HttpMethod.PATCH, "/api/v1/blogs-details")
                                .param("blogId", id)
                                .param("content", "text only change"),
                        null,
                        200)
                .andExpect(jsonPath("$.published").value(true));
    }

    @Test
    void editBlogRejectsForeignReferencesEmptyBlogWrongTypesAndNonOwner() throws Exception {
        String id = create("original").get("blogId").asText();
        String foreign = create("other image", png("files")).get("successUrls").get(0).asText();
        write(
                session,
                multipart(HttpMethod.PATCH, "/api/v1/blogs-details")
                        .param("blogId", id)
                        .param("content", "")
                        .param("removeImagesUrl", foreign),
                null,
                400);
        write(
                session,
                multipart(HttpMethod.PATCH, "/api/v1/blogs-details")
                        .param("blogId", id)
                        .param("content", ""),
                null,
                400);
        write(
                session,
                patch("/api/v1/blogs/access"),
                "{\"blogId\":\"" + id + "\",\"published\":\"false\"}",
                400);
        write(
                login(other, PASSWORD),
                multipart(HttpMethod.PATCH, "/api/v1/blogs-details")
                        .param("blogId", id)
                        .param("content", "stolen"),
                null,
                403);
        mvc.perform(client(get("/api/v1/blogs/" + id)))
                .andExpect(jsonPath("$.content").value("original"));
    }

    @Test
    void deleteBlogCascadesInteractionsAndQueuesImagesButRollbackProtectsData() throws Exception {
        JsonNode created = create("post", png("files"));
        String id = created.get("blogId").asText();
        UUID asset =
                UUID.fromString(
                        created.get("successUrls")
                                .get(0)
                                .asText()
                                .substring("/api/v1/media/".length()));
        Session visitor = login(other, PASSWORD);
        write(visitor, post("/api/v1/blogs/" + id + "/like"), "{\"liked\":true}", 200);
        write(visitor, post("/api/v1/blogs/" + id + "/comments"), "{\"content\":\"comment\"}", 200);
        write(visitor, delete("/api/v1/blogs/" + id), null, 403);
        doThrow(new IllegalStateException("queue unavailable")).when(media).enqueue(asset);
        write(session, delete("/api/v1/blogs/" + id), null, 500);
        assertThat(
                        jdbc.queryForObject(
                                "select likes_count from blogs where id = ?",
                                Long.class,
                                UUID.fromString(id)))
                .isEqualTo(1);
        org.mockito.Mockito.doCallRealMethod().when(media).enqueue(asset);
        write(session, delete("/api/v1/blogs/" + id), null, 200);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from comments where blog_id = ?",
                                Long.class,
                                UUID.fromString(id)))
                .isZero();
        mvc.perform(client(get("/api/v1/media/" + asset))).andExpect(status().isNotFound());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from media_cleanup_jobs where asset_id = ?",
                                Long.class,
                                asset))
                .isEqualTo(1);
    }

    @Test
    void deletingAccountPreservesOthersRepliesAndUpdatesCountersAtomically() throws Exception {
        avatar();
        JsonNode ownBlog = create("own", png("files"));
        Session visitor = login(other, PASSWORD);
        String external =
                json(write(
                                        visitor,
                                        multipart("/api/v1/blogs").param("content", "external"),
                                        null,
                                        200)
                                .andReturn())
                        .get("blogId")
                        .asText();
        String comment =
                json(write(
                                        session,
                                        post("/api/v1/blogs/" + external + "/comments"),
                                        "{\"content\":\"mine\"}",
                                        200)
                                .andReturn())
                        .get("comment")
                        .get("id")
                        .asText();
        String reply =
                json(write(
                                        visitor,
                                        post(
                                                "/api/v1/blogs/"
                                                        + external
                                                        + "/comments/"
                                                        + comment
                                                        + "/reply"),
                                        "{\"content\":\"keep reply\"}",
                                        200)
                                .andReturn())
                        .get("reply")
                        .get("id")
                        .asText();
        write(
                visitor,
                post("/api/v1/blogs/" + external + "/comments/" + comment + "/like"),
                "{\"liked\":true}",
                200);
        write(session, post("/api/v1/blogs/" + external + "/like"), "{\"liked\":true}", 200);
        write(session, post("/api/v1/blogs/" + external + "/share"), "{}", 200);
        write(session, delete(userPath()), "{\"currentPassword\":\"wrong\"}", 401);
        assertThat(users.existsById(owner.getId())).isTrue();
        write(session, delete(userPath()), "{\"currentPassword\":\"aaaa\"}", 200);
        assertThat(session.session().isInvalid()).isTrue();
        assertThat(users.existsById(owner.getId())).isFalse();
        assertThat(users.existsById(other.getId())).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from blogs where id = ?",
                                Long.class,
                                UUID.fromString(ownBlog.get("blogId").asText())))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select content from comments where id = ?",
                                String.class,
                                UUID.fromString(reply)))
                .isEqualTo("keep reply");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from comments where id = ? and author_id is null and deleted_at is not null and content is null and likes_count = 0",
                                Long.class,
                                UUID.fromString(comment)))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForMap(
                                "select likes_count, comments_count, shares_count from blogs where id = ?",
                                UUID.fromString(external)))
                .containsEntry("likes_count", 0L)
                .containsEntry("comments_count", 1L)
                .containsEntry("shares_count", 0L);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from media_assets where owner_id = ?",
                                Long.class,
                                owner.getId()))
                .isZero();
    }

    @Test
    void writesRequireCsrfAndUnknownMultipartFieldsAreRejected() throws Exception {
        mvc.perform(
                        client(patch(userPath()))
                                .session(session.session())
                                .contentType("application/json")
                                .content("{\"fullname\":\"x\"}"))
                .andExpect(status().isForbidden());
        String id = create("post").get("blogId").asText();
        write(
                session,
                multipart(HttpMethod.PATCH, "/api/v1/blogs-details")
                        .param("blogId", id)
                        .param("published", "false")
                        .param("admin", "true"),
                null,
                400);
        write(
                session,
                multipart(HttpMethod.PATCH, userPath() + "/avatar")
                        .file(png("file"))
                        .file(png("file")),
                null,
                400);
    }

    @Test
    void passwordChangedAfterVerificationCannotBeOverwritten() throws Exception {
        String concurrentHash = encoder.encode("cccc");
        org.mockito.Mockito.doAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            jdbc.update(
                                    "update users set password_hash = ? where id = ?",
                                    concurrentHash,
                                    owner.getId());
                            return call.callRealMethod();
                        })
                .when(encoder)
                .encode("bbbb");
        write(
                session,
                put(userPath()),
                "{\"password\":\"bbbb\",\"currentPassword\":\"aaaa\"}",
                409);
        assertThat(users.findById(owner.getId()).orElseThrow().getPasswordHash())
                .isEqualTo(concurrentHash);
    }

    @Test
    void failedCleanupRollsBackAllBlogEditsAndDiscardsNewUpload() throws Exception {
        JsonNode created = create("original", png("files"));
        String blogId = created.get("blogId").asText();
        String oldUrl = created.get("successUrls").get(0).asText();
        UUID oldId = UUID.fromString(oldUrl.substring("/api/v1/media/".length()));
        doThrow(new IllegalStateException("queue unavailable")).when(media).enqueue(oldId);
        write(
                session,
                multipart(HttpMethod.PATCH, "/api/v1/blogs-details")
                        .file(png("newImages"))
                        .param("blogId", blogId)
                        .param("content", "must rollback")
                        .param("published", "false")
                        .param("removeImagesUrl", oldUrl),
                null,
                500);
        mvc.perform(client(get("/api/v1/blogs/" + blogId)))
                .andExpect(jsonPath("$.content").value("original"))
                .andExpect(jsonPath("$.published").value(true))
                .andExpect(jsonPath("$.imageContentUrls[0]").value(oldUrl));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from media_assets where owner_id = ? and state = 'DELETE_PENDING'",
                                Long.class,
                                owner.getId()))
                .isEqualTo(1);
    }

    private String userPath() {
        return "/api/v1/users/" + owner.getId();
    }

    private User user() {
        User user = new User();
        user.setUsername(
                "profile" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        user.setFullname("Profile test");
        user.setDob(LocalDate.of(2000, 1, 1));
        user.setPasswordHash(encoder.encode(PASSWORD));
        return users.saveAndFlush(user);
    }

    private Session login(User user, String password) throws Exception {
        MvcResult initial =
                mvc.perform(client(get("/api/v1/auth/csrf")))
                        .andExpect(status().isOk())
                        .andReturn();
        MockHttpSession http = (MockHttpSession) initial.getRequest().getSession(false);
        write(
                new Session(http, json(initial).get("token").asText()),
                post("/api/v1/auth/log-in"),
                mapper.writeValueAsString(
                        Map.of("username", user.getUsername(), "password", password)),
                200);
        MvcResult csrf =
                mvc.perform(client(get("/api/v1/auth/csrf")).session(http))
                        .andExpect(status().isOk())
                        .andReturn();
        return new Session(http, json(csrf).get("token").asText());
    }

    private JsonNode avatar() throws Exception {
        return json(
                write(
                                session,
                                multipart(HttpMethod.PATCH, userPath() + "/avatar")
                                        .file(png("file")),
                                null,
                                200)
                        .andReturn());
    }

    private JsonNode create(String content, MockMultipartFile... files) throws Exception {
        var request = multipart("/api/v1/blogs").param("content", content);
        for (var file : files) request.file(file);
        return json(write(session, request, null, 200).andReturn());
    }

    private MockMultipartFile png(String part) throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile(part, "image.png", "image/png", bytes.toByteArray());
    }

    private org.springframework.test.web.servlet.ResultActions write(
            Session session,
            AbstractMockHttpServletRequestBuilder<?> request,
            String body,
            int expected)
            throws Exception {
        request.session(session.session()).header("X-CSRF-TOKEN", session.token());
        if (body != null) request.contentType("application/json").content(body);
        return mvc.perform(client(request)).andExpect(status().is(expected));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private AbstractMockHttpServletRequestBuilder<?> client(
            AbstractMockHttpServletRequestBuilder<?> request) {
        return request.with(
                servletRequest -> {
                    servletRequest.setRemoteAddr("profile-test-" + UUID.randomUUID());
                    servletRequest.setServletPath(servletRequest.getRequestURI());
                    return servletRequest;
                });
    }

    private record Session(MockHttpSession session, String token) {}
}
