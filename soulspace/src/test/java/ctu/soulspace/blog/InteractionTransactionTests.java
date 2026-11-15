package ctu.soulspace.blog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.entities.Blog;
import ctu.soulspace.exception.AppException;
import ctu.soulspace.repositories.UserRepository;
import ctu.soulspace.service.BlogService;
import ctu.soulspace.service.InteractionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class InteractionTransactionTests {
    @Test
    void serializationFailureRollsBackAndStartsANewTransaction() {
        Fixture fixture = fixture();
        when(fixture.query().executeUpdate())
                .thenThrow(sql("40001", "serialization failure"))
                .thenReturn(0);
        Blog blog = mock(Blog.class);
        when(blog.getLikesCount()).thenReturn(1L);
        UUID id = UUID.randomUUID();
        when(fixture.em().find(Blog.class, id)).thenReturn(blog);
        assertThat(fixture.service().setLike(UUID.randomUUID(), id, null, true).likesCount())
                .isEqualTo(1);
        verify(fixture.manager(), times(2)).getTransaction(any());
        verify(fixture.manager()).rollback(any());
        verify(fixture.manager()).commit(any());
    }

    @Test
    void deadlockRetriesStopAfterThreeRolledBackTransactions() {
        Fixture fixture = fixture();
        when(fixture.query().executeUpdate()).thenThrow(sql("40P01", "deadlock detected"));
        assertThatThrownBy(
                        () ->
                                fixture.service()
                                        .setLike(UUID.randomUUID(), UUID.randomUUID(), null, true))
                .isInstanceOf(AppException.class)
                .satisfies(
                        failure ->
                                assertThat(((AppException) failure).getErrorCode())
                                        .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
        verify(fixture.manager(), times(3)).getTransaction(any());
        verify(fixture.manager(), times(3)).rollback(any());
        verify(fixture.manager(), never()).commit(any());
    }

    @Test
    void businessConflictsAndLockTimeoutsDoNotReplayTheWrite() {
        for (String state : new String[] {"23505", "55P03"}) {
            Fixture fixture = fixture();
            when(fixture.query().executeUpdate())
                    .thenThrow(
                            sql(
                                    state,
                                    "23505".equals(state)
                                            ? "ERROR: ALREADY_SHARED"
                                            : "lock timeout"));
            assertThatThrownBy(() -> fixture.service().share(UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(AppException.class)
                    .satisfies(
                            failure ->
                                    assertThat(((AppException) failure).getErrorCode())
                                            .isEqualTo(
                                                    "23505".equals(state)
                                                            ? ErrorCode.ALREADY_SHARED
                                                            : ErrorCode.CONCURRENT_MODIFICATION));
            verify(fixture.manager()).getTransaction(any());
            verify(fixture.manager()).rollback(any());
            verify(fixture.manager(), never()).commit(any());
        }
    }

    private PersistenceException sql(String state, String message) {
        return new PersistenceException(new SQLException(message, state));
    }

    private Fixture fixture() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class, RETURNS_SELF);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        InteractionService service =
                new InteractionService(
                        mock(UserRepository.class), mock(BlogService.class), manager);
        ReflectionTestUtils.setField(service, "em", em);
        return new Fixture(service, manager, em, query);
    }

    private record Fixture(
            InteractionService service,
            PlatformTransactionManager manager,
            EntityManager em,
            Query query) {}
}
