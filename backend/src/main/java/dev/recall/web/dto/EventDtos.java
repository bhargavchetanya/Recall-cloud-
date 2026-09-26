package dev.recall.web.dto;

import dev.recall.domain.CapturedEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class EventDtos {

    /** Upper bound on events per request; the extension sends 50. */
    public static final int MAX_EVENTS_PER_REQUEST = 500;

    private EventDtos() {}

    public record EventsRequest(
            @NotNull @Size(min = 1, max = MAX_EVENTS_PER_REQUEST) List<@Valid @NotNull EventInput> events) {}

    /**
     * {@code redacted} lets the extension say it already masked something, which the server cannot see afterwards.
     * Unknown fields such as a {@code teamId} are ignored: the team always comes from the JWT.
     */
    public record EventInput(
            @NotBlank @Size(max = 100) String sessionId,
            @NotNull @PositiveOrZero Long seq,
            @NotBlank @Size(max = 8192) String commandText,
            Boolean redacted,
            @Size(max = 1024) String cwd,
            @Size(max = 200) String repoName,
            @Size(max = 100) String shell,
            @Size(max = 50) String os,
            Integer exitCode,
            @NotNull Instant startedAt,
            @PositiveOrZero Long durationMs,
            @Size(max = 65536) String errorText,
            @Size(max = 100) String osUser) {

        public CapturedEvent toCaptured() {
            return new CapturedEvent(sessionId, seq, commandText, Boolean.TRUE.equals(redacted), cwd, repoName, shell,
                    os, exitCode, startedAt, durationMs, errorText, osUser);
        }
    }

    public record EventsAccepted(int accepted) {}
}
