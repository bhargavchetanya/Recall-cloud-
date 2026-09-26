package dev.recall.service.learning;

import dev.recall.config.RecallProperties;
import dev.recall.domain.CapturedEvent;
import dev.recall.repository.FixRepository;
import dev.recall.repository.PendingErrorRepository;
import dev.recall.repository.PendingErrorRepository.Pending;
import dev.recall.service.errors.ErrorFingerprinter.Fingerprint;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The learning loop (spec §5.5), run by the consumer for every stored event, per terminal session:
 * a failure with output opens a pending error; a later command in the same session closes it as a team fix when it
 * succeeded, came within the window, runs the same tool or is a known unblocker, is not trivial and is not the failed
 * command again. Repeats of a known fix count as one more success.
 */
@Service
public class LearningService {

    /** What happened with one event; returned so every rule can be tested on its own. */
    public enum Outcome {
        /** Unknown exit code, or a failure without output: nothing to learn from. */
        IGNORED,
        /** A failure with output: now the pending error of its session. */
        OPENED,
        /** A success with no open error in its session. */
        NOTHING_PENDING,
        /** Arrived with a sequence number at or before the pending failure (out of order). */
        STALE,
        /** Too late or too many commands after the failure; the pending error was dropped. */
        EXPIRED,
        TRIVIAL,
        IDENTICAL,
        /** A different tool that is not a known unblocker; the error stays pending. */
        UNRELATED,
        /** Saved (or counted again) as the team's fix for the pending error. */
        LEARNED
    }

    private static final Logger log = LoggerFactory.getLogger(LearningService.class);

    private final PendingErrorRepository pending;
    private final FixRepository fixes;
    private final CommandRules rules;
    private final Duration window;
    private final int maxCommands;

    public LearningService(PendingErrorRepository pending, FixRepository fixes, RecallProperties properties) {
        this.pending = pending;
        this.fixes = fixes;
        RecallProperties.Learning l = properties.learning();
        this.rules = new CommandRules(l.unblockers(), l.trivial());
        this.window = l.window();
        this.maxCommands = l.maxCommands();
    }

    /**
     * @param error fingerprint of the event's error output, or null when it has none
     * @param errorVector embedding of the normalized error, or null
     */
    public Outcome observe(UUID teamId, UUID userId, CapturedEvent e, Fingerprint error, float[] errorVector) {
        if (e.exitCode() == null) {
            return Outcome.IGNORED;
        }
        if (e.exitCode() != 0) {
            if (error == null) {
                return Outcome.IGNORED;
            }
            pending.save(new Pending(teamId, userId, e.sessionId(), e.seq(), e.commandText(), error.sha256(),
                    error.normalized(), errorVector, e.startedAt()));
            return Outcome.OPENED;
        }

        Optional<Pending> open = pending.find(userId, e.sessionId());
        if (open.isEmpty()) {
            return Outcome.NOTHING_PENDING;
        }
        Pending p = open.get();
        if (e.seq() <= p.seq()) {
            return Outcome.STALE;
        }
        if (e.seq() - p.seq() > maxCommands || e.startedAt().isAfter(p.failedAt().plus(window))) {
            pending.delete(userId, e.sessionId());
            return Outcome.EXPIRED;
        }
        if (rules.isTrivial(e.commandText())) {
            return Outcome.TRIVIAL;
        }
        if (e.commandText().equals(p.commandText())) {
            return Outcome.IDENTICAL;
        }
        if (!CommandRules.tool(e.commandText()).equals(CommandRules.tool(p.commandText()))
                && !rules.isUnblocker(e.commandText())) {
            return Outcome.UNRELATED;
        }
        UUID fixId = fixes.upsertLearned(teamId, p.fingerprint(), p.normalizedError(), e.commandText(), userId,
                p.failedAt(), e.startedAt(), p.errorVector());
        pending.delete(userId, e.sessionId());
        log.info("Learned fix {} for \"{}\": {}", fixId, p.commandText(), e.commandText());
        return Outcome.LEARNED;
    }
}
