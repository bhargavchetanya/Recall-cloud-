package dev.recall.service.errors;

import dev.recall.config.RecallProperties;
import dev.recall.domain.Answer;
import dev.recall.domain.Answer.Provenance;
import dev.recall.repository.FixRepository;
import dev.recall.repository.FixRepository.Fix;
import dev.recall.service.SecretMasker;
import dev.recall.service.embedding.EmbeddingService;
import dev.recall.service.errors.ErrorFingerprinter.Fingerprint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Error resolution (spec §5.4): the team's best fix for exactly this fingerprint; else the fix of the most similar
 * error if it is similar enough; else nothing. Always with who solved it and how often.
 */
@Service
public class FixResolver {

    public record ErrorQuery(String commandText, String errorText, int exitCode, String cwd, String repoName,
                             String shell, String os, String osUser) {}

    static final String NONE_EXPLANATION =
            "Nobody on your team has fixed this error yet. Recall will learn from whatever fixes it.";

    private final ErrorFingerprinter fingerprinter;
    private final SecretMasker masker;
    private final EmbeddingService embeddings;
    private final FixRepository fixes;
    private final double similarityThreshold;
    private final int demoteMinFailures;

    public FixResolver(ErrorFingerprinter fingerprinter, SecretMasker masker, EmbeddingService embeddings,
                       FixRepository fixes, RecallProperties properties) {
        this.fingerprinter = fingerprinter;
        this.masker = masker;
        this.embeddings = embeddings;
        this.fixes = fixes;
        this.similarityThreshold = properties.errors().similarityThreshold();
        this.demoteMinFailures = properties.errors().demoteMinFailures();
    }

    public Answer resolve(UUID teamId, ErrorQuery q) {
        String error = masker.mask(q.errorText()).text();
        Fingerprint fp = fingerprinter.fingerprint(error, q.osUser());

        Optional<Fix> exact = fixes.bestExact(teamId, fp.sha256(), demoteMinFailures);
        if (exact.isPresent()) {
            Fix f = exact.get();
            return team(f, 1.0, "%s fixed this exact error%s.".formatted(solver(f), times(f.workedCount())));
        }

        Optional<Fix> similar = fixes.nearest(teamId, embeddings.embed(fp.normalized()), demoteMinFailures)
                .filter(f -> f.cosine() >= similarityThreshold);
        if (similar.isPresent()) {
            Fix f = similar.get();
            return team(f, round(f.cosine()), "%s fixed a very similar error (%d%% alike)%s.".formatted(solver(f),
                    Math.round(f.cosine() * 100), times(f.workedCount())));
        }

        return Answer.none(NONE_EXPLANATION);
    }

    private static Answer team(Fix f, double confidence, String explanation) {
        return new Answer(f.fixCommand(), Answer.Source.TEAM, confidence,
                new Provenance(f.solvedBy(), f.lastUsedAt(), f.workedCount()), explanation, f.id());
    }

    private static String solver(Fix f) {
        return f.solvedBy() == null ? "A teammate" : f.solvedBy();
    }

    private static String times(int worked) {
        return worked == 1 ? "" : "; it has worked %d times".formatted(worked);
    }

    static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
