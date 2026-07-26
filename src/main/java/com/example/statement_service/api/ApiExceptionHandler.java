package com.example.statement_service.api;

import com.example.statement_service.service.AuditLoggingException;
import com.example.statement_service.service.TooManyRequestsException;
import com.example.statement_service.observability.StatementMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.example.statement_service.service.BadRequestException;
import com.example.statement_service.service.NotFoundException;

/**
 * Global exception handler for the API.
 * Maps application-specific exceptions to standard HTTP problem details.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private final StatementMetrics metrics;

    public ApiExceptionHandler(StatementMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Handles {@link NotFoundException} and returns a 404 Not Found response.
     *
     * @param ex the exception to handle
     * @return a {@link ProblemDetail} describing the error
     */
    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ProblemDetail notFound(NotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * Handles {@link BadRequestException} and returns a 400 Bad Request response.
     *
     * @param ex the exception to handle
     * @return a {@link ProblemDetail} describing the error
     */
    @ExceptionHandler(BadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ProblemDetail badRequest(BadRequestException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ProblemDetail typeMismatch(MethodArgumentTypeMismatchException ex) {
        String name = ex.getName() == null ? "request parameter" : ex.getName();
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, name + " has an invalid value");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    ProblemDetail uploadTooLarge(MaxUploadSizeExceededException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "PDF file exceeds the upload limit");
    }

    /**
     * Handles validation errors and returns a 400 Bad Request response with details.
     *
     * @param ex the exception to handle
     * @return a {@link ProblemDetail} containing validation error messages
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ProblemDetail validation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation error");
        var errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        pd.setProperty("errors", errors);
        return pd;
    }

    /**
     * Handles {@link TooManyRequestsException} and returns a 429 Too Many Requests response.
     *
     * @param ex the exception to handle
     * @return a {@link ProblemDetail} describing the error, including the status and
     *         the exception's detail message
     */
    @ExceptionHandler(TooManyRequestsException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    ProblemDetail tooMany(TooManyRequestsException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    @ExceptionHandler(AuditLoggingException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    ProblemDetail auditUnavailable(AuditLoggingException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Audit logging is unavailable");
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ProblemDetail accessDenied(AccessDeniedException ex) {
        metrics.authForbidden();
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
    }
}
