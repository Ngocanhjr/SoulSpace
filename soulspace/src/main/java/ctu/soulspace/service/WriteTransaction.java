package ctu.soulspace.service;

import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.exception.AppException;
import java.sql.SQLException;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

final class WriteTransaction extends TransactionTemplate {
    private static final int MAX_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MS = 50;
    private static final Set<String> RETRYABLE_STATES = Set.of("40P01", "40001");

    WriteTransaction(PlatformTransactionManager manager) {
        super(manager);
    }

    @Override
    public <T> T execute(TransactionCallback<T> action) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return super.execute(action);
            } catch (RuntimeException failure) {
                SQLException sql = sqlCause(failure);
                if (sql == null) throw failure;
                String state = sql.getSQLState();
                if ("55P03".equals(state))
                    throw new AppException(ErrorCode.CONCURRENT_MODIFICATION);
                if (!RETRYABLE_STATES.contains(state == null ? "" : state)) throw failure;
                if (attempt + 1 >= MAX_ATTEMPTS)
                    throw new AppException(ErrorCode.CONCURRENT_MODIFICATION);
                // Only PostgreSQL-confirmed rollbacks are replayed; uploads and hashing stay
                // outside.
                try {
                    Thread.sleep((long) RETRY_DELAY_MS * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AppException(ErrorCode.CONCURRENT_MODIFICATION);
                }
            }
        }
        throw new AppException(ErrorCode.CONCURRENT_MODIFICATION);
    }

    static SQLException sqlCause(Throwable failure) {
        SQLException sql = null;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException found) sql = found;
        }
        return sql;
    }
}
