package com.example.notification.api.handler;

import com.example.notification.api.dto.InvalidRecipientsResponse;
import com.example.notification.api.dto.NotFoundResponse;
import com.example.notification.api.dto.ServiceUnavailableResponse;
import com.example.notification.api.dto.ValidationErrorResponse;
import com.example.notification.exception.CampaignNotFoundException;
import com.example.notification.exception.InvalidRecipientsException;
import org.hibernate.exception.JDBCConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Syntactic problems (blank subject/message, bad email format, size limit) -> client error 400. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ValidationErrorResponse handleValidation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .sorted()
                .toList();
        return new ValidationErrorResponse("VALIDATION_ERROR", details);
    }

    /** Semantically invalid recipients (unknown / inactive user) -> 422, whole campaign rejected. */
    @ExceptionHandler(InvalidRecipientsException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public InvalidRecipientsResponse handleInvalidRecipients(InvalidRecipientsException e) {
        return new InvalidRecipientsResponse("INVALID_RECIPIENTS", e.getUnknown(), e.getInactive());
    }

    @ExceptionHandler(CampaignNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public NotFoundResponse handleNotFound(CampaignNotFoundException e) {
        return new NotFoundResponse("CAMPAIGN_NOT_FOUND", e.getCampaignId());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ValidationErrorResponse handleMalformedRequest(Exception e) {
        return new ValidationErrorResponse("MALFORMED_REQUEST", List.of("request could not be parsed"));
    }

    /**
     * Database unreachable or temporarily failing — no connection for the transaction
     * ({@link CannotCreateTransactionException}), a pooled connection died mid-query
     * ({@link DataAccessResourceFailureException}), a transient failure where retrying
     * may succeed ({@link TransientDataAccessException}: query timeout, lock contention),
     * or the connection died at commit/rollback time ({@link TransactionSystemException},
     * {@link JpaSystemException} — those two only when the root cause is a connection
     * failure; rejected for any other reason they are rethrown and surface as the
     * generic 500, because "retry later" would be a lie for, say, a constraint
     * violation at flush). This is an infrastructure outage, not a client or
     * application error: 503 tells the caller to retry later, and the body stays
     * stable and free of driver/stack-trace details.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
            TransientDataAccessException.class, TransactionSystemException.class, JpaSystemException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ServiceUnavailableResponse handleDatabaseUnavailable(RuntimeException e) {
        boolean ambiguousWrapper = e instanceof TransactionSystemException || e instanceof JpaSystemException;
        if (ambiguousWrapper && !hasConnectionFailureCause(e)) {
            throw e;
        }
        log.error("Database unavailable while serving request", e);
        return new ServiceUnavailableResponse("SERVICE_UNAVAILABLE",
                "The service is temporarily unable to process requests. Please retry later.");
    }

    /**
     * SQLState class 08 = connection exception, class 57 = operator intervention
     * (Postgres reports server shutdown as 57P01/57P02/57P03). The pool's proxy throws
     * a stateless "Connection is closed" SQLException after evicting a dead connection,
     * and Hibernate classifies connection problems as {@link JDBCConnectionException}.
     */
    private static boolean hasConnectionFailureCause(Throwable t) {
        int depth = 0;
        for (Throwable cause = t; cause != null && depth++ < 50; cause = cause.getCause()) {
            if (cause instanceof ConnectException
                    || cause instanceof SQLRecoverableException
                    || cause instanceof SQLTransientConnectionException
                    || cause instanceof SQLNonTransientConnectionException
                    || cause instanceof JDBCConnectionException) {
                return true;
            }
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.startsWith("57"))) {
                    return true;
                }
                if (state == null && sql.getMessage() != null && sql.getMessage().contains("closed")) {
                    return true;
                }
            }
        }
        return false;
    }
}
