package dev.recall.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.recall.config.RecallProperties;
import dev.recall.domain.Answer;
import dev.recall.repository.CommandSearchRepository;
import dev.recall.repository.CommandSearchRepository.Neighbor;
import dev.recall.repository.CommandSearchRepository.Usage;
import dev.recall.service.embedding.EmbeddingService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    private final UUID team = UUID.randomUUID();
    private EmbeddingService embeddings;
    private CommandSearchRepository repo;
    private SearchService service;

    @BeforeEach
    void setUp() {
        embeddings = mock(EmbeddingService.class);
        repo = mock(CommandSearchRepository.class);
        when(embeddings.embed(any())).thenReturn(new float[EmbeddingService.DIMENSIONS]);
        service = new SearchService(embeddings, repo,
                new RecallProperties(null, null, null, RankingTest.SPEC, null, null, null), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void returnsTheBestScoringTeamCommandWithProvenance() {
        neighbors(new Neighbor("git reset --soft HEAD~1", 0.80), new Neighbor("git reset --hard", 0.78));
        usage(new Usage("git reset --soft HEAD~1", 4, NOW.minus(Duration.ofDays(6)), "Daksh", false),
                new Usage("git reset --hard", 1, NOW.minus(Duration.ofDays(90)), "Ana", false));

        Answer answer = service.search(team, query("undo my last commit but keep the changes"));

        double expected = new Ranking(RankingTest.SPEC).score(0.80, 4, 6, false);
        assertThat(answer.command()).isEqualTo("git reset --soft HEAD~1");
        assertThat(answer.source()).isEqualTo(Answer.Source.TEAM);
        assertThat(answer.confidence()).isEqualTo(Math.round(expected * 1000) / 1000.0);
        assertThat(answer.provenance()).isEqualTo(
                new Answer.Provenance("Daksh", NOW.minus(Duration.ofDays(6)), 4));
        assertThat(answer.explanation()).contains("0.80", "4×");
        assertThat(answer.fixId()).isNull();
    }

    @Test
    void usageAndRecencyCanBeatASlightlyCloserCommand() {
        neighbors(new Neighbor("docker compose up", 0.82), new Neighbor("docker compose up -d", 0.80));
        usage(new Usage("docker compose up", 1, NOW.minus(Duration.ofDays(200)), "Old", false),
                new Usage("docker compose up -d", 25, NOW.minus(Duration.ofHours(2)), "Mia", false));

        assertThat(service.search(team, query("start the stack")).command()).isEqualTo("docker compose up -d");
    }

    @Test
    void sameRepoOrCwdIsPassedThroughAndCanLiftAnAnswerOverTheThreshold() {
        neighbors(new Neighbor("make deploy", 0.50));
        when(repo.usage(eq(team), anyList(), eq("shop"), eq("/work/shop")))
                .thenReturn(Map.of("make deploy", new Usage("make deploy", 1, NOW, "Ana", true)));

        Answer answer = service.search(team, new SearchService.Query("deploy it", "/work/shop", "shop", "bash", "linux"));

        assertThat(answer.source()).isEqualTo(Answer.Source.TEAM);
        assertThat(answer.confidence()).isGreaterThanOrEqualTo(0.55);
    }

    @Test
    void equalScoresGoToTheNearerCommand() {
        neighbors(new Neighbor("nearer", 0.9), new Neighbor("further", 0.9));
        usage(new Usage("further", 3, NOW, "y", false), new Usage("nearer", 3, NOW, "x", false));

        assertThat(service.search(team, query("q")).command()).isEqualTo("nearer");
    }

    @Test
    void belowThresholdIsNone() {
        neighbors(new Neighbor("git status", 0.10));
        usage(new Usage("git status", 1, NOW.minus(Duration.ofDays(60)), "x", false));

        Answer answer = service.search(team, query("purple elephant sonata"));

        assertThat(answer).isEqualTo(Answer.none(SearchService.NONE_EXPLANATION));
    }

    @Test
    void neighborWithoutUsageRowIsIgnored() {
        neighbors(new Neighbor("vanished", 0.99));
        usage();

        assertThat(service.search(team, query("q")).source()).isEqualTo(Answer.Source.NONE);
    }

    @Test
    void roundsToThreeDecimals() {
        assertThat(SearchService.round(0.123456)).isEqualTo(0.123);
        assertThat(SearchService.round(0.9995)).isEqualTo(1.0);
    }

    private void neighbors(Neighbor... n) {
        when(repo.nearestCommands(eq(team), any(), anyInt(), anyInt(), anyInt())).thenReturn(List.of(n));
    }

    private void usage(Usage... u) {
        Map<String, Usage> map = new java.util.LinkedHashMap<>();
        for (Usage x : u) {
            map.put(x.commandText(), x);
        }
        when(repo.usage(eq(team), anyList(), any(), any())).thenReturn(map);
    }

    private static SearchService.Query query(String text) {
        return new SearchService.Query(text, null, null, null, null);
    }
}
