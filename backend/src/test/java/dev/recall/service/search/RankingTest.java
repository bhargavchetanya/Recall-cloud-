package dev.recall.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.recall.config.RecallProperties;
import org.junit.jupiter.api.Test;

class RankingTest {

    static final RecallProperties.Ranking SPEC = new RecallProperties.Ranking(0.60, 0.15, 0.15, 0.10, 10, 30, 0.55,
            200, 50, 200);

    private final Ranking ranking = new Ranking(SPEC);

    @Test
    void perfectCandidateScoresOne() {
        assertThat(ranking.score(1.0, 10, 0, true)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void matchesTheSpecFormulaTermByTerm() {
        double expected = 0.60 * 0.5 + 0.15 * (Math.log(1 + 3) / Math.log(11)) + 0.15 * Math.exp(-7.0 / 30) + 0;
        assertThat(ranking.score(0.5, 3, 7, false)).isCloseTo(expected, within(1e-12));
    }

    @Test
    void usageSaturatesAtTheConfiguredCount() {
        assertThat(ranking.score(0, 10, 1e9, false)).isCloseTo(0.15, within(1e-9));
        assertThat(ranking.score(0, 10_000, 1e9, false)).isCloseTo(0.15, within(1e-9));
        assertThat(ranking.score(0, 0, 1e9, false)).isCloseTo(0, within(1e-9));
    }

    @Test
    void recencyDecaysWithAgeAndFutureTimestampsCountAsNow() {
        assertThat(ranking.score(0, 0, 30, false)).isCloseTo(0.15 * Math.exp(-1), within(1e-12));
        assertThat(ranking.score(0, 0, -5, false)).isCloseTo(0.15, within(1e-12));
    }

    @Test
    void contextAddsItsWeight() {
        assertThat(ranking.score(0.5, 1, 0, true) - ranking.score(0.5, 1, 0, false)).isCloseTo(0.10, within(1e-12));
    }

    @Test
    void thresholdIsInclusive() {
        assertThat(ranking.confident(0.55)).isTrue();
        assertThat(ranking.confident(0.5499)).isFalse();
        assertThat(ranking.threshold()).isEqualTo(0.55);
    }
}
