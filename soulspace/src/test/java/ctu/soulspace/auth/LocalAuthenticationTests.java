package ctu.soulspace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ctu.soulspace.configuration.PasswordConfig;
import ctu.soulspace.configuration.SecurityConfig;
import ctu.soulspace.controller.AuthenticationController;
import ctu.soulspace.controller.UserController;
import ctu.soulspace.entities.User;
import ctu.soulspace.exception.GlobalExceptionHandler;
import ctu.soulspace.repositories.UserRepository;
import ctu.soulspace.security.AccessDeniedHandler;
import ctu.soulspace.security.ApiErrorWriter;
import ctu.soulspace.security.AuthenticationEntryPoint;
import ctu.soulspace.security.SessionGuardFilter;
import ctu.soulspace.service.AuthenticationService;
import ctu.soulspace.service.UserDetailsService;
import ctu.soulspace.service.UserService;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        classes = LocalAuthenticationTests.TestApplication.class,
        properties = {
            "spring.jackson.deserialization.fail-on-unknown-properties=true",
            "server.servlet.session.cookie.name=SOULSPACESESSION"
        })
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class LocalAuthenticationTests {

    private static final String PASSWORD = "simplepassword123";
    private static final String LOGIN = "/api/v1/auth/log-in";
    private static final String CSRF = "/api/v1/auth/csrf";
    private static final String ME = "/api/v1/auth/me";
    private static final String LOGOUT = "/api/v1/auth/log-out";
    private static final String SIGNUP = "/api/v1/users/sign-up";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(
            exclude = {DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({
        PasswordConfig.class,
        SecurityConfig.class,
        GlobalExceptionHandler.class,
        AuthenticationController.class,
        UserController.class,
        UserService.class,
        AuthenticationService.class,
        UserDetailsService.class,
        ApiErrorWriter.class,
        AuthenticationEntryPoint.class,
        AccessDeniedHandler.class
    })
    static class TestApplication {}

    @Autowired private MockMvc mvc;

    @Autowired private JsonMapper mapper;

    @Autowired private PasswordEncoder encoder;

    @Autowired private SessionRegistry sessions;

    @MockitoBean private UserRepository users;
    @MockitoBean private ctu.soulspace.service.ProfileService profiles;

    private User user;
    private String clientAddress;

    @BeforeEach
    void setup() {
        clientAddress = "test-" + UUID.randomUUID();
        user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("user01");
        user.setFullname("Test user");
        user.setPasswordHash(encoder.encode(PASSWORD));
        when(users.findByUsername("user01")).thenReturn(Optional.of(user));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(users.existsById(user.getId())).thenReturn(true);
    }

    @Test
    void signupNormalizesFieldsHashesPasswordAndDoesNotLogin() throws Exception {
        String password = "aaaa";
        when(users.saveAndFlush(any(User.class)))
                .thenAnswer(
                        invocation -> {
                            User saved = invocation.getArgument(0);
                            assertThat(saved.getUsername()).isEqualTo("newuser");
                            assertThat(saved.getFullname()).isEqualTo("Full name");
                            assertThat(saved.getDob()).isEqualTo(LocalDate.of(2000, 1, 1));
                            assertThat(saved.getPasswordHash())
                                    .isNotEqualTo(password)
                                    .startsWith("$argon2id$");
                            assertThat(encoder.matches(password, saved.getPasswordHash())).isTrue();
                            saved.setId(UUID.randomUUID());
                            return saved;
                        });
        CsrfSession csrf = csrf(null);
        mvc.perform(
                        client(post(SIGNUP))
                                .session(csrf.session())
                                .header("X-CSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                    {"username":" NewUser ","fullname":" Full name ",
                     "password":"%s","birthday":"2000-01-01"}
                    """
                                                .formatted(password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("newuser"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(
                        jsonPath("$.userAvatarUrl")
                                .value(ctu.soulspace.constants.MediaConstants.DEFAULT_AVATAR_URL))
                .andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(client(get(ME)).session(csrf.session())).andExpect(status().isUnauthorized());
    }

    @Test
    void loginRotatesSessionPersistsContextAndMeUsesInternalId() throws Exception {
        CsrfSession csrf = csrf(null);
        String oldId = csrf.session().getId();
        MvcResult result = login(csrf, " USER01 ", PASSWORD, 200);
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session.getId()).isNotEqualTo(oldId);
        SecurityContext context =
                (SecurityContext)
                        session.getAttribute(
                                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().getName()).isEqualTo(user.getId().toString());
        assertThat(context.getAuthentication().getCredentials()).isNull();
        assertThat(sessions.getSessionInformation(session.getId())).isNotNull();
        mvc.perform(client(get(ME)).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authentication").value(true))
                .andExpect(jsonPath("$.user.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
        mvc.perform(client(get(ME)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void missingAndIncorrectCsrfAreJson403() throws Exception {
        mvc.perform(
                        client(post(LOGIN))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"user01\",\"password\":\""
                                                + PASSWORD
                                                + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        CsrfSession csrf = csrf(null);
        mvc.perform(client(post(LOGOUT)).session(csrf.session()).header("X-CSRF-TOKEN", "wrong"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verify(users, never()).findByUsername(anyString());
    }

    @Test
    void wrongPasswordMissingUserAndGoogleOnlyShareSameError() throws Exception {
        CsrfSession csrf = csrf(null);
        JsonNode wrong = body(login(csrf, "user01", "incorrect password", 401));
        JsonNode missing = body(login(csrf, "missinguser", PASSWORD, 401));
        user.setPasswordHash(null);
        JsonNode googleOnly = body(login(csrf, "user01", PASSWORD, 401));
        for (JsonNode error : new JsonNode[] {wrong, missing, googleOnly}) {
            assertThat(error.get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
            assertThat(error.get("message").asText()).isEqualTo("Invalid username or password");
            assertThat(error.get("authentication").asBoolean()).isFalse();
            assertThat(error.get("user").isNull()).isTrue();
            assertThat(error.toString()).doesNotContain("passwordHash", "$argon2id$");
        }
    }

    @Test
    void logoutRequiresNewCsrfAndRemovesSession() throws Exception {
        CsrfSession old = csrf(null);
        MockHttpSession session =
                (MockHttpSession)
                        login(old, "user01", PASSWORD, 200).getRequest().getSession(false);
        mvc.perform(client(post(LOGOUT)).session(session).header("X-CSRF-TOKEN", old.token()))
                .andExpect(status().isForbidden());
        CsrfSession fresh = csrf(session);
        String id = session.getId();
        mvc.perform(client(post(LOGOUT)).session(session).header("X-CSRF-TOKEN", fresh.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"));
        assertThat(session.isInvalid()).isTrue();
        assertThat(sessions.getSessionInformation(id)).isNull();
        mvc.perform(client(get(ME))).andExpect(status().isUnauthorized());
    }

    @Test
    void guestCanLogoutWithValidCsrf() throws Exception {
        CsrfSession csrf = csrf(null);
        mvc.perform(
                        client(post(LOGOUT))
                                .session(csrf.session())
                                .header("X-CSRF-TOKEN", csrf.token()))
                .andExpect(status().isOk());
    }

    @Test
    void revokedSessionIsRejected() throws Exception {
        MockHttpSession session = authenticatedSession();
        sessions.getSessionInformation(session.getId()).expireNow();
        mvc.perform(client(get(ME)).session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void activeSessionOlderThanEightHoursRemainsValid() throws Exception {
        MockHttpSession session = authenticatedSession();
        session.setAttribute(
                "SOULSPACE_AUTHENTICATED_AT",
                System.currentTimeMillis() - Duration.ofHours(9).toMillis());
        mvc.perform(client(get(ME)).session(session)).andExpect(status().isOk());
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void deletedUserIsRejectedAndDatabaseOutageIsNotMisreportedAsDeleted() throws Exception {
        MockHttpSession session = authenticatedSession();
        when(users.existsById(user.getId()))
                .thenThrow(new DataAccessResourceFailureException("private details"));
        mvc.perform(client(get(ME)).session(session))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        assertThat(session.isInvalid()).isFalse();
        doReturn(false).when(users).existsById(user.getId());
        mvc.perform(client(get(ME)).session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidSignupAndUnknownFieldsAreRejectedWithoutEchoingPassword() throws Exception {
        CsrfSession csrf = csrf(null);
        String[] bodies = {
            "{\"username\":\"ab\",\"fullname\":\"User\",\"password\":\"simplepassword123\"}",
            "{\"username\":\"validuser\",\"fullname\":\"User\",\"password\":\"abc\"}",
            "{\"username\":\"validuser\",\"fullname\":\"User\",\"password\":\"simplepassword123\",\"role\":\"ADMIN\"}",
            "{\"username\":\"validuser\",\"fullname\":\"User\",\"password\":\"simplepassword123\",\"dob\":\"2000-01-01\",\"birthday\":\"2001-01-01\"}"
        };
        for (String json : bodies) {
            MvcResult result =
                    mvc.perform(
                                    client(post(SIGNUP))
                                            .session(csrf.session())
                                            .header("X-CSRF-TOKEN", csrf.token())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(json))
                            .andExpect(status().isBadRequest())
                            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                            .andReturn();
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain(PASSWORD, "abc", "ADMIN");
        }
        verify(users, never()).saveAndFlush(any(User.class));
    }

    @Test
    void duplicateUsernameDetectedAtInsertBecomes409() throws Exception {
        SQLException sql = new SQLException("private SQL and values", "23505");
        when(users.saveAndFlush(any(User.class)))
                .thenThrow(
                        new DataIntegrityViolationException(
                                "private details",
                                new ConstraintViolationException(
                                        "private details", sql, "users_username_key")));
        CsrfSession csrf = csrf(null);
        mvc.perform(
                        client(post(SIGNUP))
                                .session(csrf.session())
                                .header("X-CSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"newuser\",\"fullname\":\"User\",\"password\":\""
                                                + PASSWORD
                                                + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_EXISTS"));
    }

    @Test
    void rateLimitStopsAttemptsBeforeRepositoryAndHash() throws Exception {
        CsrfSession csrf = csrf(null);
        for (int index = 0; index < 10; index++) {
            login(csrf, "missinguser", PASSWORD, 401);
        }
        MvcResult limited = login(csrf, "user01", PASSWORD, 429);
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("300");
        assertThat(body(limited).get("code").asText()).isEqualTo("RATE_LIMITED");
        verify(users, never()).findByUsername("user01");
    }

    @Test
    void authenticatedBusinessWritesAreLimitedBeforeReachingTheController() throws Exception {
        MockHttpSession session = authenticatedSession();
        var context =
                (SecurityContext)
                        session.getAttribute(
                                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        var filter = new SessionGuardFilter(sessions, users, new ApiErrorWriter(mapper));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        try {
            for (int attempt = 0; attempt <= 60; attempt++) {
                var request =
                        new org.springframework.mock.web.MockHttpServletRequest(
                                "POST", "/api/v1/blogs/test/comments");
                request.setServletPath("/api/v1/blogs/test/comments");
                request.setSession(session);
                response = new org.springframework.mock.web.MockHttpServletResponse();
                filter.doFilter(request, response, (incoming, outgoing) -> calls.incrementAndGet());
            }
            assertThat(calls.get()).isEqualTo(60);
            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getHeader("Retry-After")).isEqualTo("60");
            assertThat(mapper.readTree(response.getContentAsString()).get("code").asText())
                    .isEqualTo("RATE_LIMITED");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void guestPeopleSearchReturnsAnEmptyListForBlankQueries() throws Exception {
        mvc.perform(client(get("/api/v1/users")).param("q", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void guestPeopleSearchTrimsQueryLimitsResultsAndReturnsOnlyPublicFields() throws Exception {
        user.setFullname("Nguyễn Thị Minh");
        when(users.searchByName("ThỊ", PageRequest.of(0, 20)))
                .thenReturn(List.of(user));

        mvc.perform(client(get("/api/v1/users")).param("q", "  ThỊ  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(user.getId().toString()))
                .andExpect(jsonPath("$[0].username").value(user.getUsername()))
                .andExpect(jsonPath("$[0].fullname").value("Nguyễn Thị Minh"))
                .andExpect(jsonPath("$[0].userAvatarUrl").isNotEmpty())
                .andExpect(jsonPath("$[0].dob").doesNotExist())
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    void peopleSearchRejectsOversizedQueries() throws Exception {
        mvc.perform(client(get("/api/v1/users")).param("q", "a".repeat(2001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private MockHttpSession authenticatedSession() throws Exception {
        return (MockHttpSession)
                login(csrf(null), "user01", PASSWORD, 200).getRequest().getSession(false);
    }

    private CsrfSession csrf(MockHttpSession session) throws Exception {
        MockHttpServletRequestBuilder request = client(get(CSRF));
        if (session != null) {
            request.session(session);
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return new CsrfSession(
                (MockHttpSession) result.getRequest().getSession(false),
                body(result).get("token").asText());
    }

    private MvcResult login(CsrfSession csrf, String username, String password, int status)
            throws Exception {
        String json =
                mapper.writeValueAsString(
                        java.util.Map.of("username", username, "password", password));
        return mvc.perform(
                        client(post(LOGIN))
                                .session(csrf.session())
                                .header("X-CSRF-TOKEN", csrf.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json))
                .andExpect(status().is(status))
                .andReturn();
    }

    private MockHttpServletRequestBuilder client(MockHttpServletRequestBuilder request) {
        return request.with(
                servletRequest -> {
                    servletRequest.setRemoteAddr(clientAddress);
                    servletRequest.setServletPath(servletRequest.getRequestURI());
                    return servletRequest;
                });
    }

    private JsonNode body(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private record CsrfSession(MockHttpSession session, String token) {}
}
