package dev.recall.service.errors;

import dev.recall.repository.FixRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** "It worked" / "it didn't work" on a fix, only for fixes of the caller's own team. */
@Service
public class FixFeedbackService {

    public static class FixNotFound extends RuntimeException {
        public FixNotFound() {
            super("No fix with this id.");
        }
    }

    public static class NotYourTeamsFix extends RuntimeException {
        public NotYourTeamsFix() {
            super("This fix belongs to another team.");
        }
    }

    private final FixRepository fixes;
    private final Clock clock;

    public FixFeedbackService(FixRepository fixes, Clock clock) {
        this.fixes = fixes;
        this.clock = clock;
    }

    @Transactional
    public void record(UUID teamId, UUID fixId, boolean worked) {
        UUID owner = fixes.teamOf(fixId).orElseThrow(FixNotFound::new);
        if (!owner.equals(teamId)) {
            throw new NotYourTeamsFix();
        }
        fixes.recordFeedback(fixId, worked, clock.instant());
    }
}
