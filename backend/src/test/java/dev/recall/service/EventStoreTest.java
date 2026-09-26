package dev.recall.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.recall.config.RecallProperties;
import dev.recall.domain.CapturedEvent;
import dev.recall.domain.EventBatch;
import dev.recall.repository.CommandEventRepository;
import dev.recall.service.embedding.CommandGlossary;
import dev.recall.service.embedding.EmbeddingService;
import dev.recall.service.errors.ErrorFingerprinter;
import dev.recall.service.learning.LearningService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventStoreTest {

    private static final int MAX_ERROR = 16;

    private final CommandEventRepository repo = mock(CommandEventRepository.class);
    private final EmbeddingService embeddings = mock(EmbeddingService.class);
    private final CommandGlossary glossary = new CommandGlossary();
    private final ErrorFingerprinter fingerprinter = new ErrorFingerprinter();
    private final LearningService learning = mock(LearningService.class);
    private final EventStore store = new EventStore(repo, embeddings, glossary, fingerprinter, learning,
            new RecallProperties(null, new RecallProperties.Events(500, MAX_ERROR, Duration.ofSeconds(5), ""), null,
                    null, null, null, null, null));
    private final UUID team = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @Test
    void storesFailureOutputAsErrorEvent() {
        UUID id = UUID.randomUUID();
        when(repo.insertIfAbsent(eq(team), eq(user), any(), any())).thenReturn(Optional.of(id));

        EventStore.StoreResult result = store.persist(batch(event(1, 1, "boom")));

        assertThat(result).isEqualTo(new EventStore.StoreResult(1, 0, 1));
        verify(repo).insertError(eq(id), eq(team), eq("boom"), eq("boom"), eq(fingerprinter.fingerprint("boom", null).sha256()), any());
    }

    @Test
    void successfulCommandHasNoErrorEvent() {
        when(repo.insertIfAbsent(eq(team), eq(user), any(), any())).thenReturn(Optional.of(UUID.randomUUID()));

        assertThat(store.persist(batch(event(1, 0, "some output")))).isEqualTo(new EventStore.StoreResult(1, 0, 0));
        verify(repo, never()).insertError(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void failureWithoutOutputOrUnknownExitHasNoErrorEvent() {
        when(repo.insertIfAbsent(eq(team), eq(user), any(), any())).thenReturn(Optional.of(UUID.randomUUID()));

        EventStore.StoreResult result = store.persist(batch(event(1, 2, "  "), event(2, null, "boom"), event(3, 1, null)));

        assertThat(result).isEqualTo(new EventStore.StoreResult(3, 0, 0));
        verify(repo, never()).insertError(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void duplicatesAreCountedAndSkipped() {
        when(repo.insertIfAbsent(eq(team), eq(user), any(), any()))
                .thenReturn(Optional.of(UUID.randomUUID()), Optional.empty());

        EventStore.StoreResult result = store.persist(batch(event(1, 0, null), event(1, 1, "boom")));

        assertThat(result).isEqualTo(new EventStore.StoreResult(1, 1, 0));
        verify(repo, never()).insertError(any(), any(), anyString(), any(), any(), any());
    }

    @Test
    void longErrorOutputKeepsTheTail() {
        UUID id = UUID.randomUUID();
        when(repo.insertIfAbsent(eq(team), eq(user), any(), any())).thenReturn(Optional.of(id));

        store.persist(batch(event(1, 1, "0123456789abcdefFINAL-ERROR")));

        verify(repo).insertError(eq(id), eq(team), eq("bcdefFINAL-ERROR"), any(), any(), any());
    }

    @Test
    void successfulCommandsGetAnIntentVectorFromCommandPlusGlossary() {
        float[] vector = new float[EmbeddingService.DIMENSIONS];
        when(embeddings.embed(glossary.intentText("git reset --soft HEAD~1"))).thenReturn(vector);
        when(repo.insertIfAbsent(eq(team), eq(user), any(), any())).thenReturn(Optional.of(UUID.randomUUID()));

        store.persist(batch(event(1, 0, null, "git reset --soft HEAD~1")));

        verify(repo).insertIfAbsent(eq(team), eq(user), any(), same(vector));
        assertThat(glossary.intentText("git reset --soft HEAD~1")).contains("undo my last commit");
    }

    @Test
    void aCommandTheTeamAlreadyHasAVectorForIsStoredWithoutOne() {
        when(repo.hasIntentVector(team, "git status")).thenReturn(true);
        when(repo.insertIfAbsent(eq(team), eq(user), any(), any())).thenReturn(Optional.of(UUID.randomUUID()));

        store.persist(batch(event(1, 0, null, "git status")));

        verify(repo).insertIfAbsent(eq(team), eq(user), any(), isNull());
        org.mockito.Mockito.verifyNoInteractions(embeddings);
    }

    @Test
    void failedAndUnknownCommandsAreNotEmbedded() {
        when(repo.insertIfAbsent(eq(team), eq(user), any(), any())).thenReturn(Optional.of(UUID.randomUUID()));

        store.persist(batch(event(1, 1, "boom"), event(2, null, null)));

        verify(repo, org.mockito.Mockito.times(2)).insertIfAbsent(eq(team), eq(user), any(), isNull());
        // only the error text of the failure is embedded (for similar-error lookups), never an intent
        verify(embeddings).embed("boom");
        org.mockito.Mockito.verifyNoMoreInteractions(embeddings);
    }

    @Test
    void everyNewEventGoesThroughTheLearningLoopWithItsFingerprint() {
        float[] vector = new float[EmbeddingService.DIMENSIONS];
        when(embeddings.embed(any())).thenReturn(vector);
        when(repo.insertIfAbsent(eq(team), eq(user), any(), any())).thenReturn(Optional.of(UUID.randomUUID()));

        store.persist(batch(event(1, 1, "EADDRINUSE :3000"), event(2, 0, null, "npx kill-port 3000")));

        verify(learning).observe(eq(team), eq(user), any(), eq(fingerprinter.fingerprint("EADDRINUSE :3000", null)),
                same(vector));
        verify(learning).observe(eq(team), eq(user), any(), isNull(), isNull());
    }

    @Test
    void duplicatesDoNotReachTheLearningLoop() {
        when(repo.insertIfAbsent(eq(team), eq(user), any(), any())).thenReturn(Optional.empty());

        store.persist(batch(event(1, 1, "boom")));

        org.mockito.Mockito.verifyNoInteractions(learning);
    }

    @Test
    void keepTailLeavesShortTextAlone() {
        assertThat(EventStore.keepTail("short", 10)).isEqualTo("short");
        assertThat(EventStore.keepTail("0123456789", 4)).isEqualTo("6789");
    }

    private EventBatch batch(CapturedEvent... events) {
        return new EventBatch(team, user, List.of(events));
    }

    private static CapturedEvent event(long seq, Integer exitCode, String errorText) {
        return event(seq, exitCode, errorText, "npm run dev");
    }

    private static CapturedEvent event(long seq, Integer exitCode, String errorText, String command) {
        return new CapturedEvent("s1", seq, command, false, "/work", "app", "bash", "linux", exitCode,
                Instant.parse("2026-09-01T10:00:00Z"), 10L, errorText, null);
    }
}
