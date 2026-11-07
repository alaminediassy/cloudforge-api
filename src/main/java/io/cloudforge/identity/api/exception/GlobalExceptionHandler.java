package io.cloudforge.identity.api.exception;

import io.cloudforge.identity.domain.exception.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(CloudForgeException.class)
    public ResponseEntity<Object> handleBusinessException(CloudForgeException ex, WebRequest request) {
        HttpStatus status;
        String message;
        if (ex instanceof UserAlreadyExistsException) {
            status = HttpStatus.CONFLICT;
            message = "Registration cannot be completed for this email.";
        } else if (ex instanceof UserAlreadyActiveException || ex instanceof InvalidUserStateException) {
            status = HttpStatus.CONFLICT;
            message = "Account cannot be activated.";
        } else if (ex instanceof InvalidCredentialsException) {
            status = HttpStatus.UNAUTHORIZED;
            message = "Invalid email or password";
        } else if (ex instanceof InvalidRefreshTokenException) {
            status = HttpStatus.UNAUTHORIZED;
            message = "The refresh token is invalid or expired.";
        } else if (ex instanceof UserNotFoundException) {
            status = HttpStatus.NOT_FOUND;
            message = "Verification cannot be completed.";
        } else if (ex instanceof InvalidOtpException) {
            status = HttpStatus.BAD_REQUEST;
            message = "The verification code is invalid or expired.";
        } else if (ex instanceof VerificationBlockedException) {
            status = HttpStatus.TOO_MANY_REQUESTS;
            message = "Verification is temporarily blocked. Please try again later.";
        } else if (ex instanceof OtpCooldownException) {
            status = HttpStatus.TOO_MANY_REQUESTS;
            message = "Please wait before requesting another verification code.";
        } else {
            return handleUnexpectedException(ex, request);
        }
        return response(status, message, new HttpHeaders(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .distinct().sorted().collect(Collectors.joining(", "));
        return response(status, message, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        // Keep MVC statuses and headers, including Allow for HTTP 405.
        // Hide parser errors, SQL details and rejected values.
        if (status.is5xxServerError()) {
            logDiagnostic(ex);
        }
        return response(status, status.is5xxServerError()
                ? "An unexpected error occurred." : "Invalid request.", headers, request);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class,
            org.springframework.transaction.CannotCreateTransactionException.class,
            io.cloudforge.identity.application.port.NotificationDeliveryException.class})
    public ResponseEntity<Object> handleUnavailableDependency(Exception ex, WebRequest request) {
        logDiagnostic(ex);
        return response(HttpStatus.SERVICE_UNAVAILABLE,
                "Service temporarily unavailable. Please try again later.", new HttpHeaders(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception ex, WebRequest request) {
        logDiagnostic(ex);
        return response(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred.", new HttpHeaders(), request);
    }

    private void logDiagnostic(Exception ex) {
        // Read exception types and SQL states, not messages that may contain user data.
        // Limit the number of causes and stop cycles.
        List<String> causes = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(ex);
        while (!pending.isEmpty() && causes.size() < 8) {
            Throwable cause = pending.removeFirst();
            if (!seen.add(cause)) {
                continue;
            }
            String description = cause.getClass().getName();
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.matches("[0-9A-Z]{5}")) {
                    description += "[sqlState=" + state + "]";
                }
                if (sql.getNextException() != null) {
                    pending.addLast(sql.getNextException());
                }
            }
            causes.add(description);
            if (cause.getCause() != null) {
                pending.addLast(cause.getCause());
            }
        }
        List<String> frames = java.util.Arrays.stream(ex.getStackTrace())
                .limit(5).map(StackTraceElement::toString).toList();
        log.error("Request failed: causes={}, frames={}", causes, frames);
    }

    private ResponseEntity<Object> response(HttpStatusCode status, String message,
                                            HttpHeaders headers, WebRequest request) {
        HttpStatus knownStatus = HttpStatus.resolve(status.value());
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        return new ResponseEntity<>(new ErrorResponse(Instant.now(), status.value(),
                knownStatus == null ? "Error" : knownStatus.getReasonPhrase(), message, path), headers, status);
    }
}
