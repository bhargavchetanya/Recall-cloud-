package dev.recall.service.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.recall.config.RecallProperties;
import dev.recall.domain.CapturedEvent;
import dev.recall.repository.FixRepository;
import dev.recall.repository.PendingErrorRepository;
import dev.recall.repository.PendingErrorRepository.Pending;
import dev.recall.service.errors.ErrorFingerprinter.Fingerprint;
import dev.recall.service.learning.LearningService.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** One test per rule of the learning loop (spec §5.5). */
class LearningServiceTest {

    static final RecallProperties.Learning LEARNING = new RecallProperties.Learning(Duration.ofMinutes(10), 5,
            List.of("kill", "npx kill-port", "chmod", "rm -rf node_modules", "export", "nvm use", "$env:"),
            List.of("cd", "ls", "clear", "pwd", "git status", "exit"));

    private static final Instant FAILED_AT = Instant.parse("2026-09-24T10:00:00Z");
    private static final Fingerprint EADDRINUSE = new Fingerprint("listen eaddrinuse: address already in use :::<num>",
            "fp-eaddrinuse");
    private static final float[] VECTOR = {0.5f, 0.5f};

    private final UUID team = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private PendingErrorRepository pending;
    private FixRepository fixes;
    private LearningService learning;

    @BeforeEach
    void setUp() {
        pending = mock(PendingErrorRepository.class);
        fixes = mock(FixRepository.class);
        learning = new LearningService(pending, fixes,
                new RecallProperties(null, null, null, null, null, null, LEARNING, null));
    }

    @Test
    void failureWithOutputOpensAPendingError() {
        assertThat(observe(event(3, "npm run dev", 1, FAILED_AT), EADDRINUSE)).isEqualTo(Outcome.OPENED);

        ArgumentCaptor<Pending> saved = ArgumentCaptor.forClass(Pending.class);
        verify(pending).save(saved.capture());
        assertThat(saved.getValue()).usingRecursiveComparison().isEqualTo(new Pending(team, user, "s1", 3,
                "npm run dev", "fp-eaddrinuse", EADDRINUSE.normalized(), VECTOR, FAILED_AT));
    }

    @Test
    void failureWithoutOutputAndUnknownExitCodeAreIgnored() {
        assertThat(observe(event(3, "npm run dev", 1, FAILED_AT), null)).isEqualTo(Outcome.IGNORED);
        assertThat(observe(event(4, "make", null, FAILED_AT), null)).isEqualTo(Outcome.IGNORED);
        verifyNoInteractions(pending, fixes);
    }

    @Test
    void successWithoutPendingErrorDoesNothing() {
        when(pending.find(user, "s1")).thenReturn(Optional.empty());
        assertThat(observe(event(4, "npm run dev", 0, FAILED_AT), null)).isEqualTo(Outcome.NOTHING_PENDING);
        verifyNoInteractions(fixes);
    }

    @Test
    void sameToolSuccessWithinTheWindowIsLearned() {
        pendingFailure("npm run dev");

        assertThat(observe(event(5, "npm install", 0, FAILED_AT.plusSeconds(30)), null)).isEqualTo(Outcome.LEARNED);

        verify(fixes).upsertLearned(team, "fp-eaddrinuse", EADDRINUSE.normalized(), "npm install", user, FAILED_AT,
                FAILED_AT.plusSeconds(30), VECTOR);
        verify(pending).delete(user, "s1");
    }

    @Test
    void knownUnblockerFromAnotherToolIsLearned() {
        pendingFailure("npm run dev");
        assertThat(observe(event(4, "npx kill-port 3000", 0, FAILED_AT.plusSeconds(5)), null))
                .isEqualTo(Outcome.LEARNED);
        verify(fixes).upsertLearned(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unrelatedToolIsNotLearnedAndTheErrorStaysPending() {
        pendingFailure("npm run dev");
        assertThat(observe(event(4, "docker ps", 0, FAILED_AT.plusSeconds(5)), null)).isEqualTo(Outcome.UNRELATED);
        verify(fixes, never()).upsertLearned(any(), any(), any(), any(), any(), any(), any(), any());
        verify(pending, never()).delete(any(), any());
    }

    @Test
    void trivialCommandsAreNotLearned() {
        pendingFailure("git push");
        for (String trivial : List.of("cd ..", "ls -la", "clear", "pwd", "git status", "exit")) {
            assertThat(observe(event(4, trivial, 0, FAILED_AT.plusSeconds(5)), null)).as(trivial)
                    .isEqualTo(Outcome.TRIVIAL);
        }
        verifyNoInteractions(fixes);
    }

    @Test
    void rerunningTheSameCommandIsNotAFix() {
        pendingFailure("npm run dev");
        assertThat(observe(event(4, "npm run dev", 0, FAILED_AT.plusSeconds(60)), null)).isEqualTo(Outcome.IDENTICAL);
        verifyNoInteractions(fixes);
    }

    @Test
    void moreThanFiveCommandsLaterExpiresThePendingError() {
        pendingFailure("npm run dev");
        assertThat(observe(event(3 + 6, "npx kill-port 3000", 0, FAILED_AT.plusSeconds(30)), null))
                .isEqualTo(Outcome.EXPIRED);
        verify(pending).delete(user, "s1");
        verifyNoInteractions(fixes);
    }

    @Test
    void exactlyFiveCommandsLaterStillCounts() {
        pendingFailure("npm run dev");
        assertThat(observe(event(3 + 5, "npx kill-port 3000", 0, FAILED_AT.plusSeconds(30)), null))
                .isEqualTo(Outcome.LEARNED);
    }

    @Test
    void moreThanTenMinutesLaterExpiresThePendingError() {
        pendingFailure("npm run dev");
        assertThat(observe(event(4, "npx kill-port 3000", 0, FAILED_AT.plus(Duration.ofMinutes(10)).plusSeconds(1)),
                null)).isEqualTo(Outcome.EXPIRED);
        verify(pending).delete(user, "s1");
    }

    @Test
    void anOlderEventArrivingLateIsIgnored() {
        pendingFailure("npm run dev");
        assertThat(observe(event(2, "npm install", 0, FAILED_AT.minusSeconds(5)), null)).isEqualTo(Outcome.STALE);
        verifyNoInteractions(fixes);
    }

    @Test
    void aNewFailureReplacesThePendingOne() {
        pendingFailure("npm run dev");
        Fingerprint other = new Fingerprint("error: cannot find module <str>", "fp-module");

        assertThat(observe(event(4, "npm test", 1, FAILED_AT.plusSeconds(10)), other)).isEqualTo(Outcome.OPENED);

        ArgumentCaptor<Pending> saved = ArgumentCaptor.forClass(Pending.class);
        verify(pending).save(saved.capture());
        assertThat(saved.getValue().fingerprint()).isEqualTo("fp-module");
        assertThat(saved.getValue().seq()).isEqualTo(4);
    }

    private void pendingFailure(String command) {
        when(pending.find(user, "s1")).thenReturn(Optional.of(new Pending(team, user, "s1", 3, command,
                "fp-eaddrinuse", EADDRINUSE.normalized(), VECTOR, FAILED_AT)));
    }

    private Outcome observe(CapturedEvent e, Fingerprint fp) {
        return learning.observe(team, user, e, fp, fp == null ? null : VECTOR);
    }

    private static CapturedEvent event(long seq, String command, Integer exit, Instant startedAt) {
        return new CapturedEvent("s1", seq, command, false, "/w", "app", "bash", "linux", exit, startedAt, 10L,
                exit != null && exit != 0 ? "some output" : null, null);
    }
}
