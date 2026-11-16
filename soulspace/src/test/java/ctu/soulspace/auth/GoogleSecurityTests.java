package ctu.soulspace.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import ctu.soulspace.configuration.GoogleConfig;
import ctu.soulspace.controller.GoogleController;
import ctu.soulspace.entities.User;
import ctu.soulspace.repositories.UserRepository;
import ctu.soulspace.service.GoogleService;
import ctu.soulspace.service.ProfileService;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        classes = LocalAuthenticationTests.TestApplication.class,
        properties = {
            "soulspace.google.enabled=true",
            "soulspace.google.client-id=test-client",
            "soulspace.google.client-secret=test-secret",
            "soulspace.google.redirect-uri=http://localhost:5173/api/v1/auth/google/callback",
            "soulspace.google.frontend-url=http://localhost:5173"
        })
@Import({GoogleConfig.class, GoogleController.class, GoogleSecurityTests.ProviderFixture.class})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class GoogleSecurityTests {
    private static final String BASE = "/api/v1/auth";
    private static final String CONTEXT =
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
    private static final RSAKey KEY = key();
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired SessionRegistry sessions;
    @MockitoBean UserRepository users;
    @MockitoBean ProfileService profiles;
    @MockitoBean GoogleService google;
    @MockitoBean OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokens;
    private User user;
    private String ip;

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderFixture {
        @Bean
        org.springframework.security.oauth2.client.userinfo.OAuth2UserService<
                        org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest,
                        org.springframework.security.oauth2.core.oidc.user.OidcUser>
                testUserInfo() {
            return request ->
                    new org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser(
                            List.of(new SimpleGrantedAuthority("ROLE_USER")), request.getIdToken());
        }

        @Bean
        JwtDecoderFactory<ClientRegistration> testDecoder() {
            return registration -> {
                try {
                    NimbusJwtDecoder decoder =
                            NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
                    decoder.setJwtValidator(new OidcIdTokenValidator(registration));
                    return decoder;
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            };
        }
    }

    @BeforeEach
    void setup() {
        ip = UUID.randomUUID().toString();
        user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("googleuser");
        user.setFullname("Google user");
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(users.existsById(user.getId())).thenReturn(true);
        when(google.begin(
                        any(), any(), anyString(), nullable(String.class), nullable(String.class)))
                .thenAnswer(
                        call -> {
                            MockHttpSession session = call.getArgument(0);
                            var auth =
                                    call
                                            .<org.springframework.security.core.Authentication>
                                                    getArgument(1);
                            var flow =
                                    new GoogleService.Flow(
                                            call.getArgument(2),
                                            call.getArgument(3),
                                            GoogleService.actor(auth),
                                            null,
                                            session.getId(),
                                            Instant.now().plusSeconds(600));
                            GoogleService.state(session).flow = flow;
                            return flow;
                        });
        when(google.verified(any(), any(), eq("verified-subject"), any())).thenReturn(user.getId());
    }

    @Test
    void loginValidatesSignedIdTokenRotatesSessionAndStoresOnlyLocalPrincipal() throws Exception {
        Flow flow = start(null, "LOGIN");
        String previousId = flow.session().getId();
        token(flow.nonce(), "test-client", false);
        callback(flow, flow.state())
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:5173/#/auth/google/result"));
        assertThat(flow.session().getId()).isNotEqualTo(previousId);
        var context =
                (org.springframework.security.core.context.SecurityContext)
                        flow.session().getAttribute(CONTEXT);
        assertThat(context.getAuthentication())
                .isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(context.getAuthentication().getName()).isEqualTo(user.getId().toString());
        assertThat(context.getAuthentication().getCredentials()).isNull();
        assertThat(sessions.getSessionInformation(flow.session().getId())).isNotNull();
        mvc.perform(client(get(BASE + "/me")).session(flow.session())).andExpect(status().isOk());
        assertThat(java.util.Collections.list(flow.session().getAttributeNames()))
                .noneMatch(name -> name.contains("AUTHORIZED_CLIENT"));
        clearInvocations(google, tokens);
        callback(flow, flow.state())
                .andExpect(redirectedUrl("http://localhost:5173/#/auth/google/error"));
        verifyNoInteractions(tokens);
        verify(google, never()).verified(any(), any(), any(), any());
    }

    @Test
    void missingIdentityLeavesOnlyPendingSessionWithoutBusinessAccess() throws Exception {
        when(google.verified(any(), any(), anyString(), any())).thenReturn(null);
        Flow flow = start(null, "LOGIN");
        token(flow.nonce(), "test-client", false);
        callback(flow, flow.state())
                .andExpect(redirectedUrl("http://localhost:5173/#/auth/google/complete"));
        assertThat(flow.session().getAttribute(CONTEXT)).isNull();
        assertThat(sessions.getSessionInformation(flow.session().getId())).isNull();
        mvc.perform(client(get(BASE + "/me")).session(flow.session()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidStateDoesNotExchangeCodeAndCannotStartWithGetOrWithoutCsrf() throws Exception {
        mvc.perform(client(get("/login/oauth2/code/google"))).andExpect(status().isUnauthorized());
        Flow flow = start(null, "LOGIN");
        callback(flow, "forged")
                .andExpect(redirectedUrl("http://localhost:5173/#/auth/google/error"));
        verifyNoInteractions(tokens);
        mvc.perform(
                        client(post(BASE + "/google/start"))
                                .session(flow.session())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"intent\":\"LOGIN\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(client(get("/oauth2/authorization/google")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidNonceAudienceAndSignatureNeverReachIdentityWrites() throws Exception {
        for (String failure : List.of("nonce", "audience", "signature")) {
            Flow flow = start(null, "LOGIN");
            token(
                    failure.equals("nonce") ? "wrong-nonce" : flow.nonce(),
                    failure.equals("audience") ? "other-client" : "test-client",
                    failure.equals("signature"));
            callback(flow, flow.state())
                    .andExpect(redirectedUrl("http://localhost:5173/#/auth/google/error"));
        }
        verify(google, never()).verified(any(), any(), any(), any());
    }

    @Test
    void linkKeepsLocalActorAndSessionIdInsteadOfSavingGoogleAuthentication() throws Exception {
        MockHttpSession session = new MockHttpSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        user.getId().toString(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        session.setAttribute(CONTEXT, context);
        sessions.registerNewSession(session.getId(), user.getId().toString());
        Flow flow = start(session, "LINK");
        String id = session.getId();
        token(flow.nonce(), "test-client", false);
        callback(flow, flow.state())
                .andExpect(redirectedUrl("http://localhost:5173/#/auth/google/result"));
        assertThat(session.getId()).isEqualTo(id);
        var saved =
                (org.springframework.security.core.context.SecurityContext)
                        session.getAttribute(CONTEXT);
        assertThat(saved.getAuthentication().getName()).isEqualTo(user.getId().toString());
        assertThat(saved.getAuthentication())
                .isInstanceOf(UsernamePasswordAuthenticationToken.class);
    }

    @Test
    void firstPasswordPreservesSurroundingSpacesConsistentlyWithLocalPasswordPolicy()
            throws Exception {
        MockHttpSession session = new MockHttpSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        user.getId().toString(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        session.setAttribute(CONTEXT, context);
        sessions.registerNewSession(session.getId(), user.getId().toString());
        var csrf = mvc.perform(client(get(BASE + "/csrf")).session(session)).andReturn();
        mvc.perform(
                        client(post(BASE + "/password"))
                                .session(session)
                                .header("X-CSRF-TOKEN", json(csrf).get("token").asText())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\" abcd \",\"reauthProof\":\"proof\"}"))
                .andExpect(status().isOk());
        verify(google).setPassword(session, user.getId(), " abcd ", "proof");
    }

    private Flow start(MockHttpSession existing, String intent) throws Exception {
        var request = client(get(BASE + "/csrf"));
        if (existing != null) request.session(existing);
        var csrf = mvc.perform(request).andReturn();
        MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession();
        var result =
                mvc.perform(
                                client(post(BASE + "/google/start"))
                                        .session(session)
                                        .header("X-CSRF-TOKEN", json(csrf).get("token").asText())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                mapper.writeValueAsString(
                                                        Map.of("intent", intent))))
                        .andExpect(status().isOk())
                        .andReturn();
        var params =
                UriComponentsBuilder.fromUriString(json(result).get("authorizationUrl").asText())
                        .build()
                        .getQueryParams();
        assertThat(params.getFirst("nonce")).isNotBlank();
        return new Flow(
                session,
                java.net.URLDecoder.decode(
                        params.getFirst("state"), java.nio.charset.StandardCharsets.UTF_8),
                java.net.URLDecoder.decode(
                        params.getFirst("nonce"), java.nio.charset.StandardCharsets.UTF_8));
    }

    private org.springframework.test.web.servlet.ResultActions callback(Flow flow, String state)
            throws Exception {
        return mvc.perform(
                client(get("/api/v1/auth/google/callback"))
                        .session(flow.session())
                        .param("code", "test-code")
                        .param("state", state));
    }

    private void token(String nonce, String audience, boolean invalidSignature) throws Exception {
        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader(JWSAlgorithm.RS256),
                        new JWTClaimsSet.Builder()
                                .issuer("https://accounts.google.com")
                                .audience(audience)
                                .subject("verified-subject")
                                .issueTime(new Date())
                                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                                .claim("nonce", nonce)
                                .claim("name", "Google user")
                                .build());
        jwt.sign(new RSASSASigner(invalidSignature ? key() : KEY));
        when(tokens.getTokenResponse(any()))
                .thenReturn(
                        OAuth2AccessTokenResponse.withToken("test-access-token")
                                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                                .expiresIn(300)
                                .scopes(Set.of("openid"))
                                .additionalParameters(Map.of("id_token", jwt.serialize()))
                                .build());
    }

    private MockHttpServletRequestBuilder client(MockHttpServletRequestBuilder request) {
        return request.with(
                http -> {
                    http.setRemoteAddr(ip);
                    http.setServletPath(http.getRequestURI());
                    return http;
                });
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private static RSAKey key() {
        try {
            return new RSAKeyGenerator(2048).generate();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Flow(MockHttpSession session, String state, String nonce) {}
}
