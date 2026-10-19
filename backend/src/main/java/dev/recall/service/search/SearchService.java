package dev.recall.service.search;

import dev.recall.config.RecallProperties;
import dev.recall.domain.Answer;
import dev.recall.domain.Answer.Provenance;
import dev.recall.repository.CommandSearchRepository;
import dev.recall.repository.CommandSearchRepository.Neighbor;
import dev.recall.repository.CommandSearchRepository.Usage;
import dev.recall.service.embedding.EmbeddingService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Task search: one command from the team's history, else nothing. */
@Service
public class SearchService {

    public record Query(String text, String cwd, String repoName, String shell, String os) {}

    record Scored(Neighbor neighbor, Usage usage, double score) {}

    static final String NONE_EXPLANATION = "Nobody on your team has run anything like this yet.";

    private final EmbeddingService embeddings;
    private final CommandSearchRepository repository;
    private final RecallProperties.Ranking config;
    private final Ranking ranking;
    private final Clock clock;

    public SearchService(EmbeddingService embeddings, CommandSearchRepository repository, RecallProperties properties,
                         Clock clock) {
        this.embeddings = embeddings;
        this.repository = repository;
        this.config = properties.ranking();
        this.ranking = new Ranking(properties.ranking());
        this.clock = clock;
    }

    public Answer search(UUID teamId, Query q) {
        float[] vector = embeddings.embed(q.text());
        List<Neighbor> neighbors = repository.nearestCommands(teamId, vector, config.annLimit(),
                config.candidateLimit(), config.efSearch());
        Map<String, Usage> usage = repository.usage(teamId, neighbors.stream().map(Neighbor::commandText).toList(),
                q.repoName(), q.cwd());

        Instant now = clock.instant();
        Scored best = neighbors.stream()
                .filter(n -> usage.containsKey(n.commandText()))
                .map(n -> {
                    Usage u = usage.get(n.commandText());
                    double ageDays = Duration.between(u.lastUsedAt(), now).toMillis() / 86_400_000.0;
                    return new Scored(n, u, ranking.score(n.cosine(), u.uses(), ageDays, u.contextMatch()));
                })
                // Neighbors arrive nearest first and max() keeps the first of equal scores, so ties go to the closer one.
                .max(Comparator.comparingDouble(Scored::score))
                .orElse(null);

        if (best != null && ranking.confident(best.score())) {
            Usage u = best.usage();
            return new Answer(best.neighbor().commandText(), Answer.Source.TEAM, round(best.score()),
                    new Provenance(u.lastUsedBy(), u.lastUsedAt(), u.uses()),
                    "Closest match in your team's history (meaning %.2f, used %d×).".formatted(best.neighbor().cosine(),
                            u.uses()),
                    null);
        }
        return Answer.none(NONE_EXPLANATION);
    }

    static double round(double score) {
        return Math.round(score * 1000) / 1000.0;
    }
}
