package com.example.notification.api.handler;

import com.example.notification.api.dto.ServiceUnavailableResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The DB-outage handler mapping, in particular the commit-time gate: a
 * {@link TransactionSystemException} is a 503 only when its cause chain shows a
 * connection failure — any other commit failure must keep surfacing as a 500.
 * (The end-to-end 503 behavior is covered by {@link
 * com.example.notification.DatabaseOutageIntegrationTest}; the commit-time window
 * cannot be hit deterministically there.)
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void connectionAcquisitionFailureMapsTo503Body() {
        CannotCreateTransactionException e = new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction",
                new SQLTransientConnectionException("connection is not available"));

        ServiceUnavailableResponse body = handler.handleDatabaseUnavailable(e);

        assertThat(body.error()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void transientFailureMapsTo503Body() {
        QueryTimeoutException e = new QueryTimeoutException("statement timed out");

        assertThat(handler.handleDatabaseUnavailable(e).error()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void commitFailureWithConnectionCauseMapsTo503Body() {
        TransactionSystemException e = new TransactionSystemException("Could not commit JPA transaction",
                new RuntimeException("commit failed", new ConnectException("Connection refused")));

        assertThat(handler.handleDatabaseUnavailable(e).error()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void commitFailureWithConnectionSqlStateMapsTo503Body() {
        TransactionSystemException e = new TransactionSystemException("Could not commit JPA transaction",
                new RuntimeException("commit failed",
                        new SQLException("terminating connection due to administrator command", "57P01",
                                new SQLException("connection failure", "08006"))));

        assertThat(handler.handleDatabaseUnavailable(e).error()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void rollbackFailureOnDeadPooledConnectionMapsTo503Body() {
        // Observed shape when the DB dies mid-request: rollback fails on the evicted
        // connection and Hikari's proxy throws a stateless "Connection is closed".
        JpaSystemException e = new JpaSystemException(
                new RuntimeException("Unable to rollback against JDBC Connection",
                        new SQLException("Connection is closed")));

        assertThat(handler.handleDatabaseUnavailable(e).error()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void postmasterShutdownDuringStatementMapsTo503Body() {
        // Postgres reports its own shutdown with SQLState 57P01 (operator intervention).
        JpaSystemException e = new JpaSystemException(
                new RuntimeException("JDBC exception executing SQL",
                        new SQLException("FATAL: terminating connection due to unexpected postmaster exit", "57P01")));

        assertThat(handler.handleDatabaseUnavailable(e).error()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void commitFailureWithoutConnectionCauseIsRethrown() {
        TransactionSystemException e = new TransactionSystemException("Could not commit JPA transaction",
                new RuntimeException("flush failed: check constraint violated"));

        assertThatThrownBy(() -> handler.handleDatabaseUnavailable(e)).isSameAs(e);
    }

    @Test
    void jpaSystemExceptionWithoutConnectionCauseIsRethrown() {
        JpaSystemException e = new JpaSystemException(
                new RuntimeException("mapping bug: could not extract ResultSet"));

        assertThatThrownBy(() -> handler.handleDatabaseUnavailable(e)).isSameAs(e);
    }
}
