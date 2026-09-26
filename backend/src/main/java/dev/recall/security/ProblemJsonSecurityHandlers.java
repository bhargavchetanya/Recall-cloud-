package dev.recall.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** 401 and 403 from the security filters, as RFC 7807 problem documents like every other error. */
@Component
public class ProblemJsonSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper json;

    public ProblemJsonSecurityHandlers(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(request, response, HttpStatus.UNAUTHORIZED, "A valid bearer token is required.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "You do not have access to this resource.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
