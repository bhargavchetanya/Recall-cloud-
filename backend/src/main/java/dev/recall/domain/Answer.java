package dev.recall.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The one answer Recall gives, to a search or to an error: a command, where it came from and why to trust it.
 * {@code fixId} is set only when the answer is a stored fix (Phase 3), so the client can send feedback on it.
 */
public record Answer(String command, Source source, Double confidence, Provenance provenance, String explanation,
                     UUID fixId) {

    public enum Source { TEAM, AI, NONE }

    /** Who on the team used or solved it, when last, and how often. */
    public record Provenance(String solvedBy, Instant lastUsedAt, long timesUsed) {}

    public static Answer none(String explanation) {
        return new Answer(null, Source.NONE, null, null, explanation, null);
    }

    public static Answer ai(String command, String explanation) {
        return new Answer(command, Source.AI, null, null, explanation, null);
    }
}
