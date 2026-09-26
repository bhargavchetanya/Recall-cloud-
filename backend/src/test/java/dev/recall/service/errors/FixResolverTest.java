package dev.recall.service.errors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.recall.config.RecallProperties;
import dev.recall.domain.Answer;
import dev.recall.repository.FixRepository;
import dev.recall.repository.FixRepository.Fix;
import dev.recall.service.SecretMasker;
import dev.recall.service.ai.FixSuggester;
import dev.recall.service.embedding.EmbeddingService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FixResolverTest {

    private static final Instant USED = Instant.parse("2026-09-20T09:00:00Z");
    private static final String ERROR = "Error: listen EADDRINUSE: address already in use :::8080";

    private final UUID team = UUID.randomUUID();
    private final ErrorFingerprinter fingerprinter = new ErrorFingerprinter();
    private FixRepository fixes;
    private FixSuggester suggester;
    private EmbeddingService embeddings;
    private FixResolver resolver;

    @BeforeEach
    void setUp() {
        fixes = mock(FixRepository.class);
        suggester = mock(FixSuggester.class);
        embeddings = mock(EmbeddingService.class);
        when(embeddings.embed(any())).thenReturn(new float[] {1f});
        when(suggester.suggest(any())).thenReturn(Optional.empty());
        resolver = new FixResolver(fingerprinter, new SecretMasker(), embeddings, fixes, suggester,
                new dev.recall.service.ai.CommandSafety(),
                new RecallProperties(null, null, null, null, null, new RecallProperties.Errors(0.80, 3), null, null));
    }

    @Test
    void exactFingerprintMatchWinsWithProvenanceAndFixId() {
        Fix fix = new Fix(UUID.randomUUID(), team, "npx kill-port 3000", "Ada", USED, 3, 0, 1.0);
        String fp = fingerprinter.fingerprint(ERROR, null).sha256();
        when(fixes.bestExact(team, fp, 3)).thenReturn(Optional.of(fix));

        Answer a = resolver.resolve(team, query(ERROR));

        assertThat(a.command()).isEqualTo("npx kill-port 3000");
        assertThat(a.source()).isEqualTo(Answer.Source.TEAM);
        assertThat(a.confidence()).isEqualTo(1.0);
        assertThat(a.provenance()).isEqualTo(new Answer.Provenance("Ada", USED, 3));
        assertThat(a.fixId()).isEqualTo(fix.id());
        assertThat(a.explanation()).isEqualTo("Ada fixed this exact error; it has worked 3 times.");
        verify(fixes, never()).nearest(any(), any(), anyInt());
    }

    @Test
    void similarErrorAboveThresholdIsUsed() {
        when(fixes.bestExact(eq(team), any(), eq(3))).thenReturn(Optional.empty());
        when(fixes.nearest(eq(team), any(), eq(3)))
                .thenReturn(Optional.of(new Fix(UUID.randomUUID(), team, "nvm use 20", null, USED, 1, 0, 0.8712)));

        Answer a = resolver.resolve(team, query("error: unsupported engine node"));

        assertThat(a.command()).isEqualTo("nvm use 20");
        assertThat(a.confidence()).isEqualTo(0.871);
        assertThat(a.explanation()).isEqualTo("A teammate fixed a very similar error (87% alike).");
    }

    @Test
    void similarErrorBelowThresholdFallsThroughToNone() {
        when(fixes.bestExact(eq(team), any(), eq(3))).thenReturn(Optional.empty());
        when(fixes.nearest(eq(team), any(), eq(3)))
                .thenReturn(Optional.of(new Fix(UUID.randomUUID(), team, "x", "Bo", USED, 1, 0, 0.79)));

        assertThat(resolver.resolve(team, query("error: something else"))).isEqualTo(
                Answer.none(FixResolver.NONE_EXPLANATION));
    }

    @Test
    void noFixAsksTheSuggesterWithMaskedCommandAndNormalizedError() {
        when(fixes.bestExact(eq(team), any(), eq(3))).thenReturn(Optional.empty());
        when(fixes.nearest(eq(team), any(), eq(3))).thenReturn(Optional.empty());
        when(suggester.suggest(any())).thenReturn(Optional.of(new FixSuggester.Suggestion("chmod +x run.sh", null)));

        Answer a = resolver.resolve(team, new FixResolver.ErrorQuery("PGPASSWORD=hunter2 ./run.sh",
                "bash: ./run.sh: Permission denied", 126, "/w", "app", "bash", "linux", null));

        assertThat(a).isEqualTo(Answer.ai("chmod +x run.sh", FixResolver.AI_EXPLANATION));
        ArgumentCaptor<FixSuggester.SuggestionRequest> captor =
                ArgumentCaptor.forClass(FixSuggester.SuggestionRequest.class);
        verify(suggester).suggest(captor.capture());
        assertThat(captor.getValue().commandText()).isEqualTo("PGPASSWORD=*** ./run.sh");
        assertThat(captor.getValue().errorText()).as("the masked output itself, not the normalized form")
                .isEqualTo("bash: ./run.sh: Permission denied");
        assertThat(captor.getValue().task()).isNull();
    }

    @Test
    void theModelSeesOnlyTheLastPartOfLongOutput() {
        when(fixes.bestExact(eq(team), any(), eq(3))).thenReturn(Optional.empty());
        when(fixes.nearest(eq(team), any(), eq(3))).thenReturn(Optional.empty());
        String longOutput = "noise ".repeat(1000) + "Error: the real problem";

        resolver.resolve(team, query(longOutput));

        ArgumentCaptor<FixSuggester.SuggestionRequest> captor =
                ArgumentCaptor.forClass(FixSuggester.SuggestionRequest.class);
        verify(suggester).suggest(captor.capture());
        assertThat(captor.getValue().errorText()).hasSize(FixResolver.AI_ERROR_CHARS).endsWith("Error: the real problem");
    }

    @Test
    void destructiveAiFixIsNeverShown() {
        when(fixes.bestExact(eq(team), any(), eq(3))).thenReturn(Optional.empty());
        when(fixes.nearest(eq(team), any(), eq(3))).thenReturn(Optional.empty());
        when(suggester.suggest(any())).thenReturn(Optional.of(new FixSuggester.Suggestion("git push --force", null)));

        assertThat(resolver.resolve(team, query("! [rejected] main -> main (non-fast-forward)")).source())
                .isEqualTo(Answer.Source.NONE);
    }

    @Test
    void secretsInTheErrorAreMaskedBeforeFingerprinting() {
        when(fixes.bestExact(eq(team), any(), eq(3))).thenReturn(Optional.empty());
        when(fixes.nearest(eq(team), any(), eq(3))).thenReturn(Optional.empty());

        resolver.resolve(team, query("error: auth failed for token=abc123"));

        verify(embeddings).embed("error: auth failed for token=***");
    }

    private static FixResolver.ErrorQuery query(String error) {
        return new FixResolver.ErrorQuery("npm run dev", error, 1, null, null, null, null, null);
    }
}
