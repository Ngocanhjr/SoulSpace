package ctu.soulspace.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.entities.ExternalIdentity;
import ctu.soulspace.entities.ExternalIdentityId;
import ctu.soulspace.entities.User;
import ctu.soulspace.exception.AppException;
import ctu.soulspace.repositories.ExternalIdentityRepository;
import ctu.soulspace.repositories.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class GoogleServiceTests {
    private UserRepository users;
    private ExternalIdentityRepository identities;
    private PasswordEncoder encoder;
    private SessionRegistryImpl sessions;
    private PlatformTransactionManager manager;
    private GoogleService service;
    private User user;
    private MockHttpSession session;

    @BeforeEach
    void setup() {
        users = mock(UserRepository.class);
        identities = mock(ExternalIdentityRepository.class);
        encoder = mock(PasswordEncoder.class);
        sessions = new SessionRegistryImpl();
        manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        service =
                new GoogleService(
                        users,
                        identities,
                        new UserService(users, encoder),
                        encoder,
                        sessions,
                        manager);
        user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("existing");
        user.setFullname("Existing user");
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        session = new MockHttpSession();
        sessions.registerNewSession(session.getId(), user.getId().toString());
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(user.getId()));
        when(em.find(User.class, user.getId())).thenReturn(user);
        ReflectionTestUtils.setField(service, "em", em);
    }

    @Test
    void onboardingOnlyCreatesUserAfterFieldsAreValidAndConsumesPendingOnce() {
        MockHttpSession guest = new MockHttpSession();
        pending(guest, "new-subject");
        assertThat(service.onboarding(guest)).containsEntry("required", true);
        expect(ErrorCode.VALIDATION_FAILED, () -> service.complete(guest, "a!", "Name", null));
        verify(users, never()).saveAndFlush(any());
        UUID created = UUID.randomUUID();
        when(users.saveAndFlush(any()))
                .thenAnswer(
                        call -> {
                            User saved = call.getArgument(0);
                            saved.setId(created);
                            assertThat(saved.getPasswordHash()).isNull();
                            assertThat(saved.getUsername()).isEqualTo("newuser");
                            assertThat(saved.getAvatarMediaId()).isNull();
                            return saved;
                        });
        assertThat(service.complete(guest, " NewUser ", " Name ", null)).isEqualTo(created);
        verify(identities)
                .saveAndFlush(
                        argThat(
                                identity ->
                                        identity.getUserId().equals(created)
                                                && identity.getId()
                                                        .getSubject()
                                                        .equals("new-subject")));
        expect(
                ErrorCode.ONBOARDING_ALREADY_COMPLETED,
                () -> service.complete(guest, "another", "Name", null));
    }

    @Test
    void usernameConflictRollsBackAndKeepsPendingAvailableForRetry() {
        MockHttpSession guest = new MockHttpSession();
        pending(guest, "new-subject");
        when(users.saveAndFlush(any()))
                .thenThrow(
                        new DataIntegrityViolationException(
                                "constraint", new SQLException("users_username_key", "23505")));
        expect(ErrorCode.USERNAME_EXISTS, () -> service.complete(guest, "taken", "Name", null));
        verify(manager).rollback(any());
        verify(identities, never()).saveAndFlush(any());
        assertThat(service.onboarding(guest)).containsEntry("required", true);
    }

    @Test
    void googleProofIsBoundToActorSessionIntentAndCanBeUsedOnlyOnce() {
        reauth("SET_PASSWORD");
        var proof = service.reauthenticate(session, user.getId(), "SET_PASSWORD", null);
        expect(
                ErrorCode.REAUTH_REQUIRED,
                () ->
                        service.consumeProof(
                                session,
                                user.getId(),
                                "DELETE_ACCOUNT",
                                proof.reauthProof(),
                                null));
        MockHttpSession otherSession = new MockHttpSession();
        sessions.registerNewSession(otherSession.getId(), user.getId().toString());
        expect(
                ErrorCode.REAUTH_REQUIRED,
                () ->
                        service.consumeProof(
                                otherSession,
                                user.getId(),
                                "SET_PASSWORD",
                                proof.reauthProof(),
                                null));
        service.consumeProof(session, user.getId(), "SET_PASSWORD", proof.reauthProof(), null);
        expect(
                ErrorCode.REAUTH_REQUIRED,
                () ->
                        service.consumeProof(
                                session, user.getId(), "SET_PASSWORD", proof.reauthProof(), null));
        expect(
                ErrorCode.REAUTH_REQUIRED,
                () -> service.reauthenticate(session, user.getId(), "SET_PASSWORD", null));
    }

    @Test
    void revokedSessionsAndChangedPasswordCannotReuseProofOrFlow() {
        user.setPasswordHash("old-hash");
        when(encoder.matches("correct", "old-hash")).thenReturn(true);
        var proof = service.reauthenticate(session, user.getId(), "LINK_GOOGLE", "correct");
        user.setPasswordHash("new-hash");
        expect(
                ErrorCode.REAUTH_REQUIRED,
                () ->
                        service.consumeProof(
                                session,
                                user.getId(),
                                "LINK_GOOGLE",
                                proof.reauthProof(),
                                "new-hash"));
        sessions.getSessionInformation(session.getId()).expireNow();
        expect(
                ErrorCode.AUTH_REQUIRED,
                () ->
                        service.consumeProof(
                                session,
                                user.getId(),
                                "LINK_GOOGLE",
                                proof.reauthProof(),
                                "old-hash"));
    }

    @Test
    void reauthRejectsDifferentGoogleAccountAndDoesNotGiveAProof() {
        when(identities.findByUserId(user.getId()))
                .thenReturn(
                        Optional.of(
                                new ExternalIdentity(
                                        new ExternalIdentityId("GOOGLE", "own-subject"),
                                        user.getId())));
        var flow = service.begin(session, authentication(), "REAUTH", "DELETE_ACCOUNT", null);
        GoogleService.state(session).inFlight = flow;
        expect(
                ErrorCode.REAUTH_REQUIRED,
                () -> service.verified(session, flow, "other-subject", "Name"));
        expect(
                ErrorCode.REAUTH_REQUIRED,
                () -> service.reauthenticate(session, user.getId(), "DELETE_ACCOUNT", null));
    }

    @Test
    void linkCannotMoveIdentityFromAnotherUser() {
        user.setPasswordHash("hash");
        when(encoder.matches("correct", "hash")).thenReturn(true);
        var proof = service.reauthenticate(session, user.getId(), "LINK_GOOGLE", "correct");
        var flow = service.begin(session, authentication(), "LINK", null, proof.reauthProof());
        GoogleService.state(session).inFlight = flow;
        when(identities.findById(any()))
                .thenReturn(
                        Optional.of(
                                new ExternalIdentity(
                                        new ExternalIdentityId("GOOGLE", "taken"),
                                        UUID.randomUUID())));
        expect(ErrorCode.IDENTITY_CONFLICT, () -> service.verified(session, flow, "taken", "Name"));
        verify(identities, never()).saveAndFlush(any());
    }

    @Test
    void firstPasswordRequiresProofCommitsHashAndRevokesAllSessions() {
        reauth("SET_PASSWORD");
        var proof = service.reauthenticate(session, user.getId(), "SET_PASSWORD", null);
        when(encoder.encode("aaaa")).thenReturn("argon-hash");
        service.setPassword(session, user.getId(), "aaaa", proof.reauthProof());
        assertThat(user.getPasswordHash()).isEqualTo("argon-hash");
        assertThat(sessions.getSessionInformation(session.getId()).isExpired()).isTrue();
        verify(manager).commit(any());
    }

    @Test
    void expiredAndSupersededCallbacksCannotStageOnboarding() {
        MockHttpSession guest = new MockHttpSession();
        var old = service.begin(guest, null, "LOGIN", null, null);
        GoogleService.state(guest).inFlight = old;
        service.begin(guest, null, "LOGIN", null, null);
        expect(ErrorCode.OIDC_FLOW_INVALID, () -> service.verified(guest, old, "subject", "Name"));
        var expired =
                new GoogleService.Flow(
                        "LOGIN", null, null, null, guest.getId(), Instant.now().minusSeconds(1));
        GoogleService.state(guest).inFlight = expired;
        expect(
                ErrorCode.OIDC_FLOW_INVALID,
                () -> service.verified(guest, expired, "subject", "Name"));
        expect(ErrorCode.ONBOARDING_REQUIRED, () -> service.onboarding(guest));
    }

    @Test
    void providerAndHashWorkIsRateLimitedByIpEvenAcrossNewSessions() {
        for (int i = 0; i < 10; i++) service.rate(new MockHttpServletRequest(), "start");
        expect(ErrorCode.RATE_LIMITED, () -> service.rate(new MockHttpServletRequest(), "start"));
        verifyNoInteractions(encoder);
    }

    @Test
    void blankFirstPasswordIsRejectedBeforeHashingOrConsumingProof() {
        reauth("SET_PASSWORD");
        var proof = service.reauthenticate(session, user.getId(), "SET_PASSWORD", null);
        expect(
                ErrorCode.VALIDATION_FAILED,
                () -> service.setPassword(session, user.getId(), "    ", proof.reauthProof()));
        verifyNoInteractions(encoder);
        service.consumeProof(session, user.getId(), "SET_PASSWORD", proof.reauthProof(), null);
    }

    private void pending(MockHttpSession guest, String subject) {
        var flow = service.begin(guest, null, "LOGIN", null, null);
        GoogleService.state(guest).inFlight = flow;
        service.verified(guest, flow, subject, "Suggested name");
    }

    private void reauth(String action) {
        when(identities.findByUserId(user.getId()))
                .thenReturn(
                        Optional.of(
                                new ExternalIdentity(
                                        new ExternalIdentityId("GOOGLE", "own-subject"),
                                        user.getId())));
        var flow = service.begin(session, authentication(), "REAUTH", action, null);
        GoogleService.state(session).inFlight = flow;
        service.verified(session, flow, "own-subject", "Name");
    }

    private org.springframework.security.core.Authentication authentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                user.getId().toString(), null, List.of());
    }

    private void expect(
            ErrorCode code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        AppException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(code));
    }
}
