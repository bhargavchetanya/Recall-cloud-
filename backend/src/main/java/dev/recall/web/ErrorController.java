package dev.recall.web;

import dev.recall.domain.Answer;
import dev.recall.security.AuthUser;
import dev.recall.service.errors.FixFeedbackService;
import dev.recall.service.errors.FixResolver;
import dev.recall.web.dto.ErrorDtos.FeedbackRequest;
import dev.recall.web.dto.ErrorDtos.ResolveRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ErrorController {

    private final FixResolver resolver;
    private final FixFeedbackService feedback;

    public ErrorController(FixResolver resolver, FixFeedbackService feedback) {
        this.resolver = resolver;
        this.feedback = feedback;
    }

    /** The team's fix for an error that just happened, or nothing. */
    @PostMapping("/errors/resolve")
    public Answer resolve(AuthUser user, @Valid @RequestBody ResolveRequest request) {
        return resolver.resolve(user.teamId(), request.toQuery());
    }

    @PostMapping("/fixes/{id}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void feedback(AuthUser user, @PathVariable UUID id, @Valid @RequestBody FeedbackRequest request) {
        feedback.record(user.teamId(), id, request.worked());
    }
}
