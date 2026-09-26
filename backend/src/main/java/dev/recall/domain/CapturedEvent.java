package dev.recall.domain;

import java.time.Instant;

/**
 * One finished terminal command, already masked. {@code exitCode} is null when the shell could not report it.
 * {@code osUser} is the local account name, used only to normalize it out of error text; it may be null.
 */
public record CapturedEvent(
        String sessionId,
        long seq,
        String commandText,
        boolean redacted,
        String cwd,
        String repoName,
        String shell,
        String os,
        Integer exitCode,
        Instant startedAt,
        Long durationMs,
        String errorText,
        String osUser) {

    /** Only commands known to have worked are offered as search answers. */
    public boolean isSuccess() {
        return exitCode != null && exitCode == 0;
    }

    public boolean isFailureWithOutput() {
        return exitCode != null && exitCode != 0 && errorText != null && !errorText.isBlank();
    }
}
