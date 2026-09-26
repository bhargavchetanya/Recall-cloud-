package dev.recall.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import dev.recall.IntegrationTestBase;
import dev.recall.service.errors.ErrorSamples;
import dev.recall.service.errors.ErrorSamples.Pair;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T7 — table-driven over 20 real error samples: the same problem with different ports, paths, ids or times gets one
 * fingerprint and therefore one fix row (worked twice); look-alike but different problems get two rows.
 * Every sample goes through HTTP, RabbitMQ and the consumer.
 */
class T7FingerprintEquivalenceIT extends IntegrationTestBase {

    static Stream<Pair> same() {
        return ErrorSamples.SAME.stream();
    }

    static Stream<Pair> different() {
        return ErrorSamples.DIFFERENT.stream();
    }

    @Test
    void tableCoversAtLeastFifteenSamples() {
        assertThat(ErrorSamples.all()).hasSizeGreaterThanOrEqualTo(15);
    }

    @Test
    void thePortExampleFromTheSpec() {
        Member dev = registerTeam("Tariq");
        failThenFix(dev, "npm run dev", "listen EADDRINUSE: address already in use :::3000", "npx kill-port 3000");
        failThenFix(dev, "npm run dev", "listen EADDRINUSE: address already in use :::8080", "npx kill-port 3000");

        assertThat(errorFingerprints(dev)).hasSize(2).containsOnly(errorFingerprints(dev).get(0));
        List<Map<String, Object>> fixes = fixRows(dev.teamId());
        assertThat(fixes).hasSize(1);
        assertThat(fixes.get(0)).containsEntry("worked_count", 2);
    }

    @ParameterizedTest(name = "one fix row: {0}")
    @MethodSource("same")
    void sameProblemLearnsIntoOneFixRow(Pair p) {
        Member dev = registerTeam("Same");
        failThenFix(dev, "make run", p.a(), "make clean");
        failThenFix(dev, "make run", p.b(), "make clean");

        List<String> fingerprints = errorFingerprints(dev);
        assertThat(fingerprints).hasSize(2);
        assertThat(fingerprints.get(0)).isEqualTo(fingerprints.get(1));
        assertThat(fixRows(dev.teamId())).singleElement().satisfies(row -> assertThat(row).containsEntry("worked_count", 2));
    }

    @ParameterizedTest(name = "two fix rows: {0}")
    @MethodSource("different")
    void differentProblemsLearnSeparately(Pair p) {
        Member dev = registerTeam("Different");
        failThenFix(dev, "make run", p.a(), "make clean");
        failThenFix(dev, "make run", p.b(), "make clean");

        List<String> fingerprints = errorFingerprints(dev);
        assertThat(fingerprints.get(0)).isNotEqualTo(fingerprints.get(1));
        assertThat(fixRows(dev.teamId())).hasSize(2);
    }

    private List<String> errorFingerprints(Member m) {
        return jdbc.queryForList("select fingerprint from error_events where team_id = ? order by created_at",
                String.class, m.teamId());
    }
}
