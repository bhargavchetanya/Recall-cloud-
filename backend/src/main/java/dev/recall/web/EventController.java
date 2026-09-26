package dev.recall.web;

import dev.recall.security.AuthUser;
import dev.recall.service.EventIngestService;
import dev.recall.service.EventRateLimiter;
import dev.recall.web.dto.EventDtos.EventInput;
import dev.recall.web.dto.EventDtos.EventsAccepted;
import dev.recall.web.dto.EventDtos.EventsRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class EventController {

    private final EventIngestService ingest;
    private final EventRateLimiter limiter;

    public EventController(EventIngestService ingest, EventRateLimiter limiter) {
        this.ingest = ingest;
        this.limiter = limiter;
    }

    /** Queues the batch and returns immediately; the consumer persists it. At most 100 requests a minute per user. */
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EventsAccepted post(AuthUser user, @Valid @RequestBody EventsRequest request) {
        limiter.acquire(user.userId());
        int accepted = ingest.accept(user.teamId(), user.userId(),
                request.events().stream().map(EventInput::toCaptured).toList());
        return new EventsAccepted(accepted);
    }
}
