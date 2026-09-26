package dev.recall.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.recall.service.EventRateLimiter;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void unexpectedErrorsBecome500ProblemsWithoutInternals() {
        ProblemDetail p = handler.unexpected(new IllegalStateException("db password is hunter2"));

        assertThat(p.getStatus()).isEqualTo(500);
        assertThat(p.getDetail()).doesNotContain("hunter2");
    }

    @Test
    void securityExceptionsInsideControllersStay401() {
        var r = handler.unauthenticated(new AuthenticationCredentialsNotFoundException("no jwt"));

        assertThat(r.getStatusCode().value()).isEqualTo(401);
        assertThat(r.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @Test
    void retryAfterIsRoundedUpToAtLeastOneSecond() {
        var r = handler.rateLimited(new EventRateLimiter.RateLimited(Duration.ofMillis(600)));

        assertThat(r.getStatusCode().value()).isEqualTo(429);
        assertThat(r.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }
}
