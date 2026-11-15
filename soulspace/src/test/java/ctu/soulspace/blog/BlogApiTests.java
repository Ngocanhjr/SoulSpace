package ctu.soulspace.blog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.entities.User;
import ctu.soulspace.exception.AppException;
import ctu.soulspace.repositories.UserRepository;
import ctu.soulspace.service.CloudinaryService;
import ctu.soulspace.service.MediaCleanupService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
class BlogApiTests {
    private static final String PASSWORD = "aaaa";
    private static final String BLOGS = "/api/v1/blogs";
    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder encoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MediaCleanupService cleanup;
    @MockitoBean private CloudinaryService cloud;
    private User owner;
    private User other;

    @BeforeEach
    void setup() {
        owner = user();
        other = user();
        when(cloud.configured()).thenReturn(true);
        when(cloud.owns(anyString()))
                .thenAnswer(
                        call -> call.<String>getArgument(0).startsWith("soulspace/test/blogs/"));
        when(cloud.publicId(anyString()))
                .thenAnswer(call -> "soulspace/test/blogs/" + call.getArgument(0));
        when(cloud.upload(any(byte[].class), anyString()))
                .thenAnswer(
                        call ->
                                new CloudinaryService.Upload(
                                        UUID.randomUUID().toString(),
                                        "png",
                                        call.<byte[]>getArgument(0).length));
        when(cloud.download(anyString(), anyString())).thenReturn(new byte[] {1, 2, 3});
    }

    @Test
    void textPostsRespectFeedOwnProfileAndPrivateDetailPermissions() throws Exception {
        Session session = login(owner);
        String publicId = create(session, "public text", true, null);
        String privateId = create(session, "private text", false, null);
        String feed =
                mvc.perform(client(get("/api/v1/soulspaces")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(feed).contains(publicId).doesNotContain(privateId, "passwordHash");
        String publicProfile =
                mvc.perform(client(get(BLOGS + "/" + owner.getId() + "/blogs")))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(publicProfile).contains(publicId).doesNotContain(privateId);
        mvc.perform(client(get(BLOGS + "/" + owner.getId() + "/all")).session(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(privateId));
        mvc.perform(
                        client(get(BLOGS + "/" + owner.getId() + "/all"))
                                .session(login(other).session()))
                .andExpect(status().isForbidden());
        mvc.perform(client(get(BLOGS + "/" + privateId))).andExpect(status().isNotFound());
        mvc.perform(client(get(BLOGS + "/" + privateId)).session(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(false));
        mvc.perform(client(get("/api/v1/users/" + owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dob").isEmpty())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(client(get("/api/v1/users/" + owner.getId())).session(session.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dob").value("2000-01-01"));
        mvc.perform(client(get(BLOGS + "/not-a-uuid"))).andExpect(status().isBadRequest());
    }

    @Test
    void createRequiresSessionCsrfAndRejectsSpoofedActorAndUnknownFields() throws Exception {
        Session session = login(owner);
        mvc.perform(client(multipart(BLOGS).param("content", "text")).session(session.session()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        client(
                                        multipart(BLOGS)
                                                .param("content", "text")
                                                .param("userId", other.getId().toString()))
                                .session(session.session())
                                .header("X-CSRF-TOKEN", session.token()))
                .andExpect(status().isForbidden());
        for (String field : new String[] {"authorId", "likesCount"}) {
            mvc.perform(
                            client(multipart(BLOGS).param("content", "text").param(field, "spoof"))
                                    .session(session.session())
                                    .header("X-CSRF-TOKEN", session.token()))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        client(multipart(BLOGS).param("content", " "))
                                .session(session.session())
                                .header("X-CSRF-TOKEN", session.token()))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        client(multipart(BLOGS).param("content", "x".repeat(5001)))
                                .session(session.session())
                                .header("X-CSRF-TOKEN", session.token()))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        client(multipart(BLOGS).param("content", "text").param("published", "yes"))
                                .session(session.session())
                                .header("X-CSRF-TOKEN", session.token()))
                .andExpect(status().isBadRequest());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from blogs where author_id = ?",
                                Long.class,
                                owner.getId()))
                .isZero();
    }

    @Test
    void privateImageCannotBeOpenedByGuestOrAnotherUserEvenWithItsUrl() throws Exception {
        Session session = login(owner);
        String id = create(session, "", false, png());
        String url =
                jdbc.queryForObject(
                        "select '/api/v1/media/' || asset_id from blog_images where blog_id = ?",
                        String.class,
                        UUID.fromString(id));
        mvc.perform(client(get(url))).andExpect(status().isNotFound());
        mvc.perform(client(get(url)).session(login(other).session()))
                .andExpect(status().isNotFound());
        mvc.perform(client(get(url)).session(session.session()))
                .andExpect(status().isOk())
                .andExpect(
                        result ->
                                assertThat(result.getResponse().getHeader("Cache-Control"))
                                        .isEqualTo("no-store"));
        jdbc.update("update blogs set published = true where id = ?", UUID.fromString(id));
        mvc.perform(client(get(url))).andExpect(status().isOk());
        jdbc.update("update blogs set published = false where id = ?", UUID.fromString(id));
        mvc.perform(client(get(url))).andExpect(status().isNotFound());
        assertThat(
                        jdbc.queryForObject(
                                "select state from media_assets where id = (select asset_id from blog_images where blog_id = ?)",
                                String.class,
                                UUID.fromString(id)))
                .isEqualTo("ATTACHED");
        assertThat(UUID.fromString(id).version()).isEqualTo(7);
    }

    @Test
    void providerFailureKeepsTextButDoesNotCreateEmptyImageOnlyPost() throws Exception {
        Session session = login(owner);
        when(cloud.upload(any(byte[].class), anyString()))
                .thenThrow(new AppException(ErrorCode.MEDIA_PROVIDER_ERROR));
        mvc.perform(
                        client(multipart(BLOGS).file(png()).param("content", "text survives"))
                                .session(session.session())
                                .header("X-CSRF-TOKEN", session.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedFiles[0]").value("image-1"))
                .andExpect(jsonPath("$.successUrls").isEmpty());
        mvc.perform(
                        client(multipart(BLOGS).file(png()))
                                .session(session.session())
                                .header("X-CSRF-TOKEN", session.token()))
                .andExpect(status().isBadGateway());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from blogs where author_id = ?",
                                Long.class,
                                owner.getId()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from media_assets where owner_id = ? and state = 'DELETE_PENDING'",
                                Long.class,
                                owner.getId()))
                .isEqualTo(2);
        verify(cloud, never()).download(anyString(), anyString());
    }

    @Test
    void lostActorDuringUploadDoesNotCommitBlogAndQueuesTheUploadedImage() throws Exception {
        Session session = login(owner);
        when(cloud.upload(any(byte[].class), anyString()))
                .thenAnswer(
                        call -> {
                            jdbc.update("delete from users where id = ?", owner.getId());
                            return new CloudinaryService.Upload(
                                    UUID.randomUUID().toString(),
                                    "png",
                                    call.<byte[]>getArgument(0).length);
                        });
        mvc.perform(
                        client(multipart(BLOGS).file(png()).param("content", "text"))
                                .session(session.session())
                                .header("X-CSRF-TOKEN", session.token()))
                .andExpect(status().isUnauthorized());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from blogs where author_id = ?",
                                Long.class,
                                owner.getId()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                """
            select count(*) from media_assets m join media_cleanup_jobs j on j.asset_id = m.id
            where m.owner_id is null and m.state = 'DELETE_PENDING' and m.provider_asset_id is not null
            """,
                                Long.class))
                .isPositive();
    }

    @Test
    void invalidImageIsRejectedBeforeUploadAndNoBlogIsWritten() throws Exception {
        Session session = login(owner);
        MockMultipartFile invalid =
                new MockMultipartFile("files", "fake.png", "image/png", "not an image".getBytes());
        mvc.perform(
                        client(multipart(BLOGS).file(invalid).param("content", "text"))
                                .session(session.session())
                                .header("X-CSRF-TOKEN", session.token()))
                .andExpect(status().isUnsupportedMediaType());
        verify(cloud, never()).upload(any(byte[].class), anyString());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from blogs where author_id = ?",
                                Long.class,
                                owner.getId()))
                .isZero();
    }

    @Test
    void cleanupUsesDatabaseLeaseAndStopsAfterFiveFailedAttempts() throws Exception {
        Session session = login(owner);
        when(cloud.upload(any(byte[].class), anyString()))
                .thenThrow(new AppException(ErrorCode.MEDIA_PROVIDER_ERROR));
        create(session, "text", true, png());
        jdbc.update(
                "update media_assets set created_at = now() - interval '20 minutes' where owner_id = ?",
                owner.getId());
        cleanup.run();
        UUID assetId =
                jdbc.queryForObject(
                        "select id from media_assets where owner_id = ?",
                        UUID.class,
                        owner.getId());
        jdbc.update(
                "update media_cleanup_jobs set available_at = now(), attempts = 4 where asset_id = ?",
                assetId);
        doThrow(new AppException(ErrorCode.MEDIA_PROVIDER_ERROR)).when(cloud).destroy(anyString());
        cleanup.run();
        assertThat(
                        jdbc.queryForObject(
                                "select status from media_cleanup_jobs where asset_id = ?",
                                String.class,
                                assetId))
                .isEqualTo("DEAD");
        assertThat(
                        jdbc.queryForObject(
                                "select attempts from media_cleanup_jobs where asset_id = ?",
                                Integer.class,
                                assetId))
                .isEqualTo(5);
        clearInvocations(cloud);
        cleanup.run();
        verify(cloud, never()).destroy(anyString());
    }

    @Test
    void commentsRepliesAndNotificationsCommitTogetherWithoutDepthLimit() throws Exception {
        Session author = login(owner);
        Session visitor = login(other);
        String blog = create(author, "public", true, null);
        var root =
                json(
                        write(
                                visitor,
                                post(BLOGS + "/" + blog + "/comments"),
                                "{\"content\":\" root \"}",
                                200));
        String parent = root.get("comment").get("id").asText();
        assertThat(root.get("comment").get("content").asText()).isEqualTo("root");
        assertThat(root.get("commentsCount").asLong()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from notifications where blog_id = ? and type = 'BLOG_COMMENT'",
                                Long.class,
                                UUID.fromString(blog)))
                .isEqualTo(1);
        for (int depth = 1; depth <= 8; depth++) {
            var reply =
                    json(
                            write(
                                    author,
                                    post(BLOGS + "/" + blog + "/comments/" + parent + "/reply"),
                                    "{\"content\":\"reply " + depth + "\"}",
                                    200));
            assertThat(reply.get("parentCommentId").asText()).isEqualTo(parent);
            assertThat(reply.get("commentsCount").asLong()).isEqualTo(depth + 1);
            parent = reply.get("reply").get("id").asText();
        }
        var tree =
                json(
                        mvc.perform(client(get(BLOGS + "/" + blog + "/comments")))
                                .andExpect(status().isOk())
                                .andReturn());
        var node = tree.get(0);
        for (int depth = 0; depth < 8; depth++) node = node.get("replies").get(0);
        assertThat(node.get("id").asText()).isEqualTo(parent);
        assertThat(node.get("likedUserIds").isArray()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select comments_count from blogs where id = ?",
                                Long.class,
                                UUID.fromString(blog)))
                .isEqualTo(9);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from notifications where blog_id = ? and recipient_id = actor_id",
                                Long.class,
                                UUID.fromString(blog)))
                .isZero();
    }

    @Test
    void editingAndSoftDeletionPreserveRepliesVersionAndCounters() throws Exception {
        Session author = login(owner);
        Session visitor = login(other);
        String blog = create(author, "public", true, null);
        String comments = BLOGS + "/" + blog + "/comments";
        var created = json(write(visitor, post(comments), "{\"content\":\"draft\"}", 200));
        String comment = created.get("comment").get("id").asText();
        String path = comments + "/" + comment;
        write(author, post(path + "/reply"), "{\"content\":\"child stays\"}", 200);
        write(author, patch(path), "{\"content\":\"unauthorized\",\"expectedVersion\":0}", 403);
        var edited =
                json(
                        write(
                                visitor,
                                patch(path),
                                "{\"content\":\"edited\",\"expectedVersion\":0}",
                                200));
        assertThat(edited.get("comment").get("version").asLong()).isEqualTo(1);
        var conflict =
                json(
                        write(
                                visitor,
                                patch(path),
                                "{\"content\":\"stale\",\"expectedVersion\":0}",
                                409));
        assertThat(conflict.get("code").asText()).isEqualTo("CONCURRENT_MODIFICATION");
        write(author, post(path + "/like"), "{\"liked\":true}", 200);
        var liked =
                json(mvc.perform(client(get(comments))).andExpect(status().isOk()).andReturn())
                        .get(0);
        assertThat(liked.get("version").asLong()).isEqualTo(1);
        assertThat(liked.get("updatedAt").asText())
                .isEqualTo(edited.get("comment").get("updatedAt").asText());
        var deleted = json(write(visitor, delete(path), "{\"expectedVersion\":1}", 200));
        assertThat(deleted.get("commentsCount").asLong()).isEqualTo(1);
        assertThat(deleted.get("comment").get("isDeleted").asBoolean()).isTrue();
        assertThat(deleted.get("comment").get("content").isNull()).isTrue();
        assertThat(deleted.get("comment").get("likesCount").asLong()).isZero();
        assertThat(deleted.get("comment").get("replies").size()).isEqualTo(1);
        var repeated = json(write(visitor, delete(path), "{\"expectedVersion\":0}", 200));
        assertThat(repeated.get("comment").get("version").asLong()).isEqualTo(2);
        assertThat(repeated.get("comment").get("updatedAt").asText())
                .isEqualTo(deleted.get("comment").get("updatedAt").asText());
        write(author, post(path + "/like"), "{\"liked\":false}", 409);
        write(author, post(path + "/reply"), "{\"content\":\"cannot reply to placeholder\"}", 409);
        write(visitor, patch(path), "{\"content\":\"cannot restore\",\"expectedVersion\":2}", 409);
        jdbc.update("update comments set author_id = null where id = ?", UUID.fromString(comment));
        var anonymized =
                json(mvc.perform(client(get(comments))).andExpect(status().isOk()).andReturn())
                        .get(0);
        assertThat(anonymized.get("userId").isNull()).isTrue();
        assertThat(anonymized.get("username").isNull()).isTrue();
        assertThat(anonymized.get("replies").size()).isEqualTo(1);
    }

    @Test
    void interactionsRejectPrivateAccessSpoofingInvalidTypesAndCrossBlogReplies() throws Exception {
        Session author = login(owner);
        Session visitor = login(other);
        String blog = create(author, "private", false, null);
        String comments = BLOGS + "/" + blog + "/comments";
        mvc.perform(client(get(comments))).andExpect(status().isNotFound());
        mvc.perform(client(get(comments)).session(visitor.session()))
                .andExpect(status().isNotFound());
        write(visitor, post(comments), "{\"content\":\"cannot see\"}", 404);
        var own = json(write(author, post(comments), "{\"content\":\"own private comment\"}", 200));
        String comment = own.get("comment").get("id").asText();
        String publicBlog = create(author, "public", true, null);
        write(
                visitor,
                post(BLOGS + "/" + publicBlog + "/comments/" + comment + "/reply"),
                "{\"content\":\"wrong blog\"}",
                400);
        write(
                author,
                post(comments),
                "{\"content\":\"x\",\"userId\":\"" + other.getId() + "\"}",
                403);
        write(
                author,
                post(comments),
                "{\"content\":\"x\",\"authorId\":\"" + owner.getId() + "\"}",
                400);
        write(author, post(comments), "{\"content\":\" \"}", 400);
        write(author, post(comments), "{\"content\":\"" + "x".repeat(2001) + "\"}", 400);
        String commentPath = comments + "/" + comment;
        for (String invalid : new String[] {"null", "\"0\"", "-1", "1.5"}) {
            write(author, delete(commentPath), "{\"expectedVersion\":" + invalid + "}", 400);
        }
        for (String invalid : new String[] {"null", "\"false\"", "0"}) {
            write(author, post(BLOGS + "/" + blog + "/like"), "{\"liked\":" + invalid + "}", 400);
        }
        mvc.perform(
                        client(post(comments))
                                .session(author.session())
                                .contentType("application/json")
                                .content("{\"content\":\"no csrf\"}"))
                .andExpect(status().isForbidden());
        assertThat(
                        jdbc.queryForObject(
                                "select comments_count from blogs where id = ?",
                                Long.class,
                                UUID.fromString(blog)))
                .isEqualTo(1);
    }

    @Test
    void sharesUseProceduresLiveContentPrivacyAndIdempotentUnshare() throws Exception {
        Session author = login(owner);
        Session visitor = login(other);
        String blog = create(author, "original", true, null);
        String share = BLOGS + "/" + blog + "/share";
        var first = json(write(visitor, post(share), "{}", 200));
        assertThat(first.get("sharesCount").asLong()).isEqualTo(1);
        assertThat(first.get("totalSharedBlogs").asLong()).isEqualTo(1);
        assertThat(json(write(visitor, post(share), "{}", 409)).get("code").asText())
                .isEqualTo("ALREADY_SHARED");
        write(author, post(share), "{}", 200);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from notifications where blog_id = ? and type = 'BLOG_SHARE'",
                                Long.class,
                                UUID.fromString(blog)))
                .isEqualTo(1);
        jdbc.update(
                "update blogs set content = 'current content' where id = ?", UUID.fromString(blog));
        String listing = "/api/v1/users/" + other.getId() + "/shared-blogs";
        mvc.perform(client(get(listing)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("current content"))
                .andExpect(jsonPath("$[0].sharesCount").value(2))
                .andExpect(jsonPath("$[0].sharedByMe").value(false))
                .andExpect(jsonPath("$[0].likedUserIds").isEmpty());
        write(visitor, post(BLOGS + "/" + blog + "/like"), "{\"liked\":true}", 200);
        mvc.perform(client(get(listing)).session(visitor.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sharedByMe").value(true))
                .andExpect(jsonPath("$[0].likedUserIds[0]").value(other.getId().toString()));
        jdbc.update("update blogs set published = false where id = ?", UUID.fromString(blog));
        mvc.perform(client(get(listing)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        write(author, post(share), "{}", 404);
        write(author, delete(listing + "/" + blog), null, 403);
        write(visitor, delete(listing + "/" + blog), null, 200);
        write(visitor, delete(listing + "/" + blog), null, 200);
        assertThat(
                        jdbc.queryForObject(
                                "select shares_count from blogs where id = ?",
                                Long.class,
                                UUID.fromString(blog)))
                .isEqualTo(1);
        jdbc.update("update blogs set published = true where id = ?", UUID.fromString(blog));
        mvc.perform(client(get("/api/v1/users/" + owner.getId() + "/shared-blogs")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].blogId").value(blog));
        jdbc.update("delete from blogs where id = ?", UUID.fromString(blog));
        write(visitor, delete(listing + "/" + blog), null, 200);
    }

    @Test
    void shareStateIsViewerSpecificAcrossFeedProfilesAndReloadedDetail() throws Exception {
        Session author = login(owner);
        Session visitor = login(other);
        String blog = create(author, "share state", true, null);
        String detail = BLOGS + "/" + blog;
        mvc.perform(client(get(detail)).session(visitor.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharedByMe").value(false));
        write(visitor, post(detail + "/share"), "{}", 200);
        mvc.perform(client(get(detail)).session(visitor.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharedByMe").value(true))
                .andExpect(jsonPath("$.sharesCount").value(1));
        mvc.perform(client(get(detail)).session(author.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharedByMe").value(false));
        mvc.perform(client(get(detail)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharedByMe").value(false));
        mvc.perform(client(get(BLOGS + "/" + owner.getId() + "/blogs")).session(visitor.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sharedByMe").value(true));
        var feed =
                json(
                        mvc.perform(client(get("/api/v1/soulspaces")).session(visitor.session()))
                                .andExpect(status().isOk())
                                .andReturn());
        boolean found = false;
        for (var row : feed)
            if (row.get("id").asText().equals(blog)) {
                assertThat(row.get("sharedByMe").asBoolean()).isTrue();
                found = true;
            }
        assertThat(found).isTrue();
        write(
                visitor,
                delete("/api/v1/users/" + other.getId() + "/shared-blogs/" + blog),
                null,
                200);
        mvc.perform(client(get(detail)).session(visitor.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharedByMe").value(false))
                .andExpect(jsonPath("$.sharesCount").value(0));
        write(author, post(detail + "/share"), "{}", 200);
        jdbc.update("update blogs set published = false where id = ?", UUID.fromString(blog));
        mvc.perform(client(get(detail)).session(author.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharedByMe").value(true));
        write(
                author,
                delete("/api/v1/users/" + owner.getId() + "/shared-blogs/" + blog),
                null,
                200);
        mvc.perform(client(get(detail)).session(author.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharedByMe").value(false));
    }

    @Test
    void repeatedLikesUseTriggerCountersWithoutChangingCommentVersionOrBlogTimestamp()
            throws Exception {
        Session author = login(owner);
        Session visitor = login(other);
        String blog = create(author, "public", true, null);
        var before =
                json(
                        mvc.perform(client(get(BLOGS + "/" + blog)))
                                .andExpect(status().isOk())
                                .andReturn());
        String path = BLOGS + "/" + blog + "/like";
        for (int i = 0; i < 2; i++)
            assertThat(
                            json(write(visitor, post(path), "{\"liked\":true}", 200))
                                    .get("likesCount")
                                    .asLong())
                    .isEqualTo(1);
        mvc.perform(client(get(BLOGS + "/" + blog)).session(visitor.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.likedUserIds[0]").value(other.getId().toString()))
                .andExpect(jsonPath("$.updatedAt").value(before.get("updatedAt").asText()));
        for (int i = 0; i < 2; i++)
            assertThat(
                            json(write(visitor, post(path), "{\"liked\":false}", 200))
                                    .get("likesCount")
                                    .asLong())
                    .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from blog_likes where blog_id = ?",
                                Long.class,
                                UUID.fromString(blog)))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from notifications where blog_id = ? and type = 'BLOG_LIKE'",
                                Long.class,
                                UUID.fromString(blog)))
                .isEqualTo(1);
    }

    @Test
    void concurrentDuplicateSharesOnlyCommitOnce() throws Exception {
        Session author = login(owner);
        Session visitor = login(other);
        String blog = create(author, "public", true, null);
        String path = BLOGS + "/" + blog + "/share";
        var first =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> concurrentWrite(visitor, path));
        var second =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> concurrentWrite(visitor, path));
        assertThat(
                        java.util.List.of(
                                first.get(15, java.util.concurrent.TimeUnit.SECONDS),
                                second.get(15, java.util.concurrent.TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200, 409);
        assertThat(
                        jdbc.queryForObject(
                                "select shares_count from blogs where id = ?",
                                Long.class,
                                UUID.fromString(blog)))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from blog_shares where blog_id = ?",
                                Long.class,
                                UUID.fromString(blog)))
                .isEqualTo(1);
    }

    @Test
    void concurrentCommentLikeAndDeletionLeaveNoLikesOnThePlaceholder() throws Exception {
        Session author = login(owner);
        Session visitor = login(other);
        String blog = create(author, "public", true, null);
        String comments = BLOGS + "/" + blog + "/comments";
        String comment =
                json(write(visitor, post(comments), "{\"content\":\"delete while liked\"}", 200))
                        .get("comment")
                        .get("id")
                        .asText();
        String path = comments + "/" + comment;
        var like =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return mvc.perform(
                                                client(post(path + "/like"))
                                                        .session(author.session())
                                                        .header("X-CSRF-TOKEN", author.token())
                                                        .contentType("application/json")
                                                        .content("{\"liked\":true}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            } catch (Exception exception) {
                                throw new RuntimeException(exception);
                            }
                        });
        var deletion =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return mvc.perform(
                                                client(delete(path))
                                                        .session(visitor.session())
                                                        .header("X-CSRF-TOKEN", visitor.token())
                                                        .contentType("application/json")
                                                        .content("{\"expectedVersion\":0}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus();
                            } catch (Exception exception) {
                                throw new RuntimeException(exception);
                            }
                        });
        assertThat(like.get(15, java.util.concurrent.TimeUnit.SECONDS)).isIn(200, 409);
        assertThat(deletion.get(15, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from comment_likes where comment_id = ?",
                                Long.class,
                                UUID.fromString(comment)))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select likes_count from comments where id = ?",
                                Long.class,
                                UUID.fromString(comment)))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select comments_count from blogs where id = ?",
                                Long.class,
                                UUID.fromString(blog)))
                .isZero();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void commentAndShareOrderingCanUseThePlannedIndexes() {
        jdbc.execute("SET LOCAL enable_seqscan = off");
        // Check ordered index-scan eligibility, not the cheapest plan on tiny fixtures.
        jdbc.execute("SET LOCAL enable_bitmapscan = off");
        String commentsPlan =
                String.join(
                        "\n",
                        jdbc.queryForList(
                                """
            EXPLAIN (COSTS OFF) SELECT id FROM comments WHERE blog_id = ? ORDER BY created_at DESC, id DESC
            """,
                                String.class,
                                UUID.randomUUID()));
        String sharesPlan =
                String.join(
                        "\n",
                        jdbc.queryForList(
                                """
            EXPLAIN (COSTS OFF) SELECT blog_id FROM blog_shares WHERE user_id = ? ORDER BY shared_at DESC, blog_id DESC
            """,
                                String.class,
                                other.getId()));
        assertThat(commentsPlan).contains("comments_blog_idx").doesNotContain("Sort");
        assertThat(sharesPlan).contains("blog_shares_user_time_idx").doesNotContain("Sort");
    }

    private int concurrentWrite(Session session, String path) {
        try {
            return mvc.perform(
                            client(post(path))
                                    .session(session.session())
                                    .header("X-CSRF-TOKEN", session.token())
                                    .contentType("application/json")
                                    .content("{}"))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private MvcResult write(
            Session session, MockHttpServletRequestBuilder request, String body, int expected)
            throws Exception {
        request.session(session.session()).header("X-CSRF-TOKEN", session.token());
        if (body != null) request.contentType("application/json").content(body);
        return mvc.perform(client(request)).andExpect(status().is(expected)).andReturn();
    }

    private tools.jackson.databind.JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void defaultAvatarIsSharedAcrossSessionProfileBlogsCommentsAndShares() throws Exception {
        String avatar = ctu.soulspace.constants.MediaConstants.DEFAULT_AVATAR_URL;
        Session author = login(owner);
        Session visitor = login(other);
        mvc.perform(client(get("/api/v1/auth/me")).session(author.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.avatarUrl").value(avatar));
        mvc.perform(client(get("/api/v1/users/" + owner.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userAvatarUrl").value(avatar));
        String blog = create(author, "default avatar", true, null);
        mvc.perform(client(get(BLOGS + "/" + blog)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userAvatarUrl").value(avatar));
        var comment =
                json(
                        write(
                                visitor,
                                post(BLOGS + "/" + blog + "/comments"),
                                "{\"content\":\"comment\"}",
                                200));
        assertThat(comment.get("comment").get("userAvatarUrl").asText()).isEqualTo(avatar);
        write(visitor, post(BLOGS + "/" + blog + "/share"), "{}", 200);
        mvc.perform(client(get("/api/v1/users/" + other.getId() + "/shared-blogs")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userAvatarUrl").value(avatar));
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from users where id in (?, ?) and avatar_media_id is null",
                                Long.class,
                                owner.getId(),
                                other.getId()))
                .isEqualTo(2);
    }

    private User user() {
        User user = new User();
        user.setUsername("test" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        user.setFullname("Test user");
        user.setDob(LocalDate.of(2000, 1, 1));
        user.setPasswordHash(encoder.encode(PASSWORD));
        return users.saveAndFlush(user);
    }

    private Session login(User user) throws Exception {
        MvcResult initial =
                mvc.perform(client(get("/api/v1/auth/csrf")))
                        .andExpect(status().isOk())
                        .andReturn();
        MockHttpSession session = (MockHttpSession) initial.getRequest().getSession(false);
        mvc.perform(
                        client(post("/api/v1/auth/log-in"))
                                .session(session)
                                .header(
                                        "X-CSRF-TOKEN",
                                        mapper.readTree(initial.getResponse().getContentAsString())
                                                .get("token")
                                                .asText())
                                .contentType("application/json")
                                .content(
                                        mapper.writeValueAsString(
                                                java.util.Map.of(
                                                        "username",
                                                        user.getUsername(),
                                                        "password",
                                                        PASSWORD))))
                .andExpect(status().isOk());
        MvcResult csrf =
                mvc.perform(client(get("/api/v1/auth/csrf")).session(session))
                        .andExpect(status().isOk())
                        .andReturn();
        return new Session(
                session,
                mapper.readTree(csrf.getResponse().getContentAsString()).get("token").asText());
    }

    private String create(Session session, String text, boolean published, MockMultipartFile file)
            throws Exception {
        var request =
                multipart(BLOGS)
                        .param("content", text)
                        .param("published", String.valueOf(published));
        if (file != null) request.file(file);
        MvcResult result =
                mvc.perform(
                                client(request)
                                        .session(session.session())
                                        .header("X-CSRF-TOKEN", session.token()))
                        .andExpect(status().isOk())
                        .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).get("blogId").asText();
    }

    private MockMultipartFile png() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return new MockMultipartFile("files", "image.png", "image/png", output.toByteArray());
    }

    private <T extends AbstractMockHttpServletRequestBuilder<T>> T client(T request) {
        return request.with(
                servletRequest -> {
                    servletRequest.setRemoteAddr("test-" + UUID.randomUUID());
                    servletRequest.setServletPath(servletRequest.getRequestURI());
                    return servletRequest;
                });
    }

    private record Session(MockHttpSession session, String token) {}
}
