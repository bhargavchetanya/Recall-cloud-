package dev.recall.service;

import dev.recall.domain.CapturedEvent;
import dev.recall.domain.EventBatch;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** API side of ingestion: mask again, stamp the caller's team and user, publish. Persistence happens in the consumer. */
@Service
public class EventIngestService {

    private final SecretMasker masker;
    private final EventPublisher publisher;

    public EventIngestService(SecretMasker masker, EventPublisher publisher) {
        this.masker = masker;
        this.publisher = publisher;
    }

    public int accept(UUID teamId, UUID userId, List<CapturedEvent> events) {
        List<CapturedEvent> masked = events.stream().map(this::mask).toList();
        publisher.publish(new EventBatch(teamId, userId, masked));
        return masked.size();
    }

    CapturedEvent mask(CapturedEvent e) {
        SecretMasker.Result command = masker.mask(e.commandText());
        SecretMasker.Result error = masker.mask(e.errorText());
        boolean redacted = e.redacted() || command.redacted() || error.redacted();
        return new CapturedEvent(e.sessionId(), e.seq(), command.text(), redacted, e.cwd(), e.repoName(), e.shell(),
                e.os(), e.exitCode(), e.startedAt(), e.durationMs(), error.text(), e.osUser());
    }
}
