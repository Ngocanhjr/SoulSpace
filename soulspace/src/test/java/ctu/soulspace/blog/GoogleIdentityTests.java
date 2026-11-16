package ctu.soulspace.blog;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.entities.User;
import ctu.soulspace.exception.AppException;
import ctu.soulspace.repositories.ExternalIdentityRepository;
import ctu.soulspace.repositories.UserRepository;
import ctu.soulspace.service.CloudinaryService;
import ctu.soulspace.service.GoogleService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "SOULSPACE_BLOG_TEST_DB", matches = "true")
@SpringBootTest(
        properties = {
            "media.cleanup.initial-delay-ms=3600000", "soulspace.google.enabled=false",
            "spring.datasource.url=${BLOG_TEST_DB_URL}",
                    "spring.datasource.username=soulspace_runtime",
            "spring.datasource.password=${BLOG_TEST_RUNTIME_PASSWORD}",
                    "spring.flyway.url=${BLOG_TEST_DB_URL}",
            "spring.flyway.user=soulspace_migrator",
                    "spring.flyway.password=${BLOG_TEST_MIGRATOR_PASSWORD}"
        })
@ActiveProfiles("test")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class GoogleIdentityTests {
    @Autowired GoogleService google;
    @Autowired UserRepository users;
    @Autowired ExternalIdentityRepository identities;
    @Autowired SessionRegistry sessions;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @MockitoBean CloudinaryService cloud;

    @Test
    void onboardingCreatesV7UserAndIdentityTogetherAndRetriesUsernameConflict() {
        User existing = local();
        String subject = UUID.randomUUID().toString();
        MockHttpSession guest = pending(subject);
        long before = users.count();
        assertThatThrownBy(
                        () -> google.complete(guest, existing.getUsername(), "New profile", null))
                .isInstanceOfSatisfying(
                        AppException.class,
                        failure ->
                                assertThat(failure.getErrorCode())
                                        .isEqualTo(ErrorCode.USERNAME_EXISTS));
        assertThat(users.count()).isEqualTo(before);
        assertThat(google.onboarding(guest)).containsEntry("required", true);
        UUID id = google.complete(guest, username(), "New profile", null);
        assertThat(id.version()).isEqualTo(7);
        User created = users.findById(id).orElseThrow();
        assertThat(created.getPasswordHash()).isNull();
        assertThat(created.getAvatarMediaId()).isNull();
        assertThat(identities.findByUserId(id).orElseThrow().getId().getSubject())
                .isEqualTo(subject);
        assertThat(users.count()).isEqualTo(before + 1);
    }

    @Test
    void concurrentOnboardingForSameGoogleIdentityCannotLeaveAnOrphanUser() throws Exception {
        String subject = UUID.randomUUID().toString();
        var first = pending(subject);
        var second = pending(subject);
        long before = users.count();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> one = () -> completeTogether(first, ready, start);
            Callable<Boolean> two = () -> completeTogether(second, ready, start);
            var a = executor.submit(one);
            var b = executor.submit(two);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(users.count()).isEqualTo(before + 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM external_identities WHERE provider = 'GOOGLE' AND subject = ?",
                                Integer.class,
                                subject))
                .isEqualTo(1);
    }

    @Test
    void linkingIsIdempotentAndCannotTransferAnotherUsersIdentity() {
        User one = local();
        User two = local();
        String subject = UUID.randomUUID().toString();
        link(one, subject);
        link(one, subject);
        assertThatThrownBy(() -> link(two, subject))
                .isInstanceOfSatisfying(
                        AppException.class,
                        failure ->
                                assertThat(failure.getErrorCode())
                                        .isEqualTo(ErrorCode.IDENTITY_CONFLICT));
        assertThat(identities.findByUserId(one.getId()).orElseThrow().getId().getSubject())
                .isEqualTo(subject);
        assertThat(identities.findByUserId(two.getId())).isEmpty();
    }

    @Test
    void settingFirstPasswordRevokesAllSessionsAndEnablesLocalLogin() throws Exception {
        String subject = UUID.randomUUID().toString();
        UUID actor = google.complete(pending(subject), username(), "Google profile", null);
        var first = signedIn(actor);
        var second = signedIn(actor);
        String proof = reauth(first, actor, subject, "SET_PASSWORD");
        google.setPassword(first, actor, " abcd ", proof);
        assertThat(encoder.matches(" abcd ", users.findById(actor).orElseThrow().getPasswordHash()))
                .isTrue();
        assertThat(sessions.getSessionInformation(first.getId()).isExpired()).isTrue();
        assertThat(sessions.getSessionInformation(second.getId()).isExpired()).isTrue();
        var guest = new MockHttpSession();
        String csrf = csrf(guest);
        mvc.perform(
                        client(post("/api/v1/auth/log-in"))
                                .session(guest)
                                .header("X-CSRF-TOKEN", csrf)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "username",
                                                        users.findById(actor)
                                                                .orElseThrow()
                                                                .getUsername(),
                                                        "password",
                                                        " abcd "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(actor.toString()));
    }

    @Test
    void googleOnlyDeletionRequiresCorrectProofAndCascadesIdentity() throws Exception {
        String subject = UUID.randomUUID().toString();
        UUID actor = google.complete(pending(subject), username(), "Google profile", null);
        MockHttpSession session = signedIn(actor);
        String csrf = csrf(session);
        mvc.perform(
                        client(delete("/api/v1/users/" + actor))
                                .session(session)
                                .header("X-CSRF-TOKEN", csrf)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reauthProof\":\"forged\"}"))
                .andExpect(status().isForbidden());
        assertThat(users.existsById(actor)).isTrue();
        String proof = reauth(session, actor, subject, "DELETE_ACCOUNT");
        mvc.perform(
                        client(delete("/api/v1/users/" + actor))
                                .session(session)
                                .header("X-CSRF-TOKEN", csrf)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(Map.of("reauthProof", proof))))
                .andExpect(status().isOk());
        assertThat(users.existsById(actor)).isFalse();
        assertThat(identities.findByUserId(actor)).isEmpty();
    }

    private boolean completeTogether(
            MockHttpSession session, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS))
            throw new IllegalStateException("Test start timed out");
        try {
            google.complete(session, username(), "Profile", null);
            return true;
        } catch (AppException failure) {
            assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.IDENTITY_CONFLICT);
            return false;
        }
    }

    private MockHttpSession pending(String subject) {
        MockHttpSession session = new MockHttpSession();
        var flow = google.begin(session, null, "LOGIN", null, null);
        GoogleService.state(session).inFlight = flow;
        google.verified(session, flow, subject, "Suggested name");
        return session;
    }

    private User local() {
        User user = new User();
        user.setUsername(username());
        user.setFullname("Local profile");
        user.setPasswordHash(encoder.encode("aaaa"));
        return users.saveAndFlush(user);
    }

    private String username() {
        return "g" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private MockHttpSession signedIn(UUID actor) {
        MockHttpSession session = new MockHttpSession();
        sessions.registerNewSession(session.getId(), actor.toString());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        actor.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private void link(User user, String subject) {
        var session = signedIn(user.getId());
        var proof = google.reauthenticate(session, user.getId(), "LINK_GOOGLE", "aaaa");
        var auth =
                UsernamePasswordAuthenticationToken.authenticated(
                        user.getId().toString(), null, List.of());
        var flow = google.begin(session, auth, "LINK", null, proof.reauthProof());
        GoogleService.state(session).inFlight = flow;
        google.verified(session, flow, subject, "Name");
    }

    private String reauth(MockHttpSession session, UUID actor, String subject, String action) {
        var flow =
                google.begin(
                        session,
                        UsernamePasswordAuthenticationToken.authenticated(
                                actor.toString(), null, List.of()),
                        "REAUTH",
                        action,
                        null);
        GoogleService.state(session).inFlight = flow;
        google.verified(session, flow, subject, "Name");
        return google.reauthenticate(session, actor, action, null).reauthProof();
    }

    private String csrf(MockHttpSession session) throws Exception {
        return mapper.readTree(
                        mvc.perform(client(get("/api/v1/auth/csrf")).session(session))
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .get("token")
                .asText();
    }

    private MockHttpServletRequestBuilder client(MockHttpServletRequestBuilder request) {
        return request.with(
                http -> {
                    http.setRemoteAddr(UUID.randomUUID().toString());
                    http.setServletPath(http.getRequestURI());
                    return http;
                });
    }
}
