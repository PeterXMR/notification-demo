package com.example.notification.api.handler;

import com.example.notification.api.dto.InvalidRecipientsResponse;
import com.example.notification.api.dto.NotFoundResponse;
import com.example.notification.api.dto.ServiceUnavailableResponse;
import com.example.notification.api.dto.ValidationErrorResponse;
import com.example.notification.exception.CampaignNotFoundException;
import com.example.notification.exception.InvalidRecipientsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
     * Database unreachable — either no connection could be obtained for the transaction
     * ({@link CannotCreateTransactionException}) or a pooled connection died mid-query
     * ({@link DataAccessResourceFailureException}). This is a temporary infrastructure
     * outage, not a client or application error: 503 tells the caller to retry later,
     * and the body stays stable and free of driver/stack-trace details.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ServiceUnavailableResponse handleDatabaseUnavailable(Exception e) {
        log.error("Database unavailable while serving request: {}", e.getMessage());
        return new ServiceUnavailableResponse("SERVICE_UNAVAILABLE",
                "The service is temporarily unable to process requests. Please retry later.");
    }
}
