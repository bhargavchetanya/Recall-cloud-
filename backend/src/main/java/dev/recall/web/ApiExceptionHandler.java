package dev.recall.web;

import dev.recall.service.AuthExceptions;
import dev.recall.service.EventPublisher;
import dev.recall.service.EventRateLimiter;
import dev.recall.service.errors.FixFeedbackService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Every error leaves as application/problem+json (RFC 7807). */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AuthExceptions.EmailAlreadyRegistered.class)
    ProblemDetail emailTaken(AuthExceptions.EmailAlreadyRegistered e) {
        return problem(HttpStatus.CONFLICT, "Email already registered", e.getMessage());
    }

    @ExceptionHandler(AuthExceptions.InvalidInviteCode.class)
    ProblemDetail badInvite(AuthExceptions.InvalidInviteCode e) {
        return problem(HttpStatus.NOT_FOUND, "Unknown invite code", e.getMessage());
    }

    @ExceptionHandler(AuthExceptions.InvalidCredentials.class)
    ResponseEntity<ProblemDetail> badCredentials(AuthExceptions.InvalidCredentials e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problem(HttpStatus.UNAUTHORIZED, "Invalid credentials", e.getMessage()));
    }

    @ExceptionHandler(FixFeedbackService.FixNotFound.class)
    ProblemDetail fixNotFound(FixFeedbackService.FixNotFound e) {
        return problem(HttpStatus.NOT_FOUND, "Unknown fix", e.getMessage());
    }

    @ExceptionHandler(FixFeedbackService.NotYourTeamsFix.class)
    ProblemDetail otherTeamsFix(FixFeedbackService.NotYourTeamsFix e) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage());
    }

    @ExceptionHandler(EventRateLimiter.RateLimited.class)
    ResponseEntity<ProblemDetail> rateLimited(EventRateLimiter.RateLimited e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, e.retryAfter().toSeconds())))
                .body(problem(HttpStatus.TOO_MANY_REQUESTS, "Too many requests", e.getMessage()));
    }

    /** Security exceptions thrown inside a controller must stay 401/403, not fall into the catch-all below. */
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    ResponseEntity<ProblemDetail> unauthenticated(org.springframework.security.core.AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(problem(HttpStatus.UNAUTHORIZED, "Unauthorized", "A valid bearer token is required."));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ProblemDetail denied(org.springframework.security.access.AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", "You do not have access to this resource.");
    }

    /** Anything unexpected: logged with its stack trace, answered as a problem without internal details. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "Something went wrong on the Recall server. It has been logged.");
    }

    @ExceptionHandler(EventPublisher.QueueUnavailableException.class)
    ProblemDetail queueDown(EventPublisher.QueueUnavailableException e) {
        log.warn("Rejecting events: {}", e.getMessage(), e.getCause());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Queue unavailable",
                "Events could not be queued. Retry later; nothing was stored.");
    }

    /** Adds per-field messages to Spring's default 400 so clients can show what was wrong. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Invalid request", "One or more fields are invalid.");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setTitle(title);
        return p;
    }
}
