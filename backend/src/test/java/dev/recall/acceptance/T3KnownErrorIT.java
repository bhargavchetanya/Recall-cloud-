package dev.recall.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import dev.recall.IntegrationTestBase;
import dev.recall.repository.FixRepository;
import dev.recall.service.embedding.EmbeddingService;
import dev.recall.service.errors.ErrorFingerprinter;
import dev.recall.service.errors.ErrorSamples;
import io.restassured.response.Response;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** T3 — the team already has a fix for EADDRINUSE; user B hitting it gets that fix, credited to user A. */
class T3KnownErrorIT extends IntegrationTestBase {

    static final String ERROR_ON_3000 = ErrorSamples.SAME.get(0).a();
    static final String ERROR_ON_8080 = ErrorSamples.SAME.get(0).b();

    @Autowired
    FixRepository fixes;

    @Autowired
    ErrorFingerprinter fingerprinter;

    @Autowired
    EmbeddingService embeddings;

    @Test
    void teammateGetsTheKnownFixWithProvenanceNamingWhoSolvedIt() {
        Member ada = registerTeam("Ada");
        Member bo = joinTeam(ada, "Bo");
        Instant solvedAt = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        UUID fixId = storeFix(ada, ERROR_ON_3000, "npx kill-port 3000", solvedAt);

        Response r = resolve(bo, "npm run dev", ERROR_ON_8080);

        assertThat(r.<String>path("command")).isEqualTo("npx kill-port 3000");
        assertThat(r.<String>path("source")).isEqualTo("TEAM");
        assertThat(r.<Float>path("confidence")).isEqualTo(1.0f);
        assertThat(r.<String>path("fixId")).isEqualTo(fixId.toString());
        assertThat(r.<String>path("provenance.solvedBy")).isEqualTo("Ada");
        assertThat(r.<Integer>path("provenance.timesUsed")).isEqualTo(1);
        assertThat(Instant.parse(r.path("provenance.lastUsedAt"))).isEqualTo(solvedAt);
        assertThat(r.<String>path("explanation")).isEqualTo("Ada fixed this exact error.");
    }

    @Test
    void aSimilarButNotIdenticalErrorStillFindsTheFix() {
        Member ada = registerTeam("Ada");
        Member bo = joinTeam(ada, "Bo");
        storeFix(ada, ERROR_ON_3000, "npx kill-port 3000", Instant.now());
        String sameProblemOtherWording = ERROR_ON_8080.replace("address already in use", "address in use");

        Response r = resolve(bo, "npm run dev", sameProblemOtherWording);

        assertThat(r.<String>path("command")).isEqualTo("npx kill-port 3000");
        assertThat(r.<String>path("source")).isEqualTo("TEAM");
        assertThat(r.<Float>path("confidence")).isGreaterThanOrEqualTo(0.8f).isLessThan(1.0f);
    }

    @Test
    void anErrorNobodyHasFixedIsNone() {
        Member bo = registerTeam("Bo");
        Response r = resolve(bo, "cargo build", "error[E0425]: cannot find value `x` in this scope");

        assertThat(r.<String>path("source")).isEqualTo("NONE");
        assertThat(r.<String>path("command")).isNull();
        assertThat(r.<String>path("fixId")).isNull();
    }

    private UUID storeFix(Member solver, String error, String fixCommand, Instant at) {
        var fp = fingerprinter.fingerprint(error, null);
        return fixes.upsertLearned(solver.teamId(), fp.sha256(), fp.normalized(), fixCommand, solver.userId(), at, at,
                embeddings.embed(fp.normalized()));
    }
}
