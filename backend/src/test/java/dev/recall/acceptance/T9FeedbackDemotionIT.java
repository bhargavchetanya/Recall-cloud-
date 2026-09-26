package dev.recall.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import dev.recall.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * T9 — three "didn't work" reports stop a fix from being offered; a better fix learned afterwards takes its place.
 */
class T9FeedbackDemotionIT extends IntegrationTestBase {

    @Test
    void threeFailuresDemoteAFixAndABetterOneTakesItsPlace() {
        Member ada = registerTeam("Ada");
        Member bo = joinTeam(ada, "Bo");
        failThenFix(ada, "npm run dev", T3KnownErrorIT.ERROR_ON_3000, "npm run dev -- --port 3001");
        String bad = resolve(bo, "npm run dev", T3KnownErrorIT.ERROR_ON_8080).path("fixId");

        feedback(bo, bad, false);
        feedback(bo, bad, false);
        assertThat(resolve(bo, "npm run dev", T3KnownErrorIT.ERROR_ON_8080).<String>path("fixId"))
                .as("two failures against one success: still offered").isEqualTo(bad);

        feedback(bo, bad, false);
        assertThat(resolve(bo, "npm run dev", T3KnownErrorIT.ERROR_ON_8080).<String>path("source"))
                .as("three failures, more than successes: retired").isEqualTo("NONE");
        assertThat(fixRows(ada.teamId()).get(0)).containsEntry("failed_count", 3).containsEntry("worked_count", 1);

        failThenFix(bo, "npm run dev", T3KnownErrorIT.ERROR_ON_8080, "npx kill-port 8080");
        var better = resolve(ada, "npm run dev", T3KnownErrorIT.ERROR_ON_3000);
        assertThat(better.<String>path("command")).isEqualTo("npx kill-port 8080");
        assertThat(better.<String>path("provenance.solvedBy")).isEqualTo("Bo");
        assertThat(better.<String>path("fixId")).isNotEqualTo(bad);
    }

    @Test
    void workedFeedbackCountsAsASuccessAndPrefersTheFix() {
        Member ada = registerTeam("Ada");
        failThenFix(ada, "npm run dev", T3KnownErrorIT.ERROR_ON_3000, "npx kill-port 3000");
        failThenFix(ada, "npm run dev", T3KnownErrorIT.ERROR_ON_3000, "kill -9 4242");
        String killPort = fixRows(ada.teamId()).get(0).get("id").toString();

        feedback(ada, killPort, true);

        var r = resolve(ada, "npm run dev", T3KnownErrorIT.ERROR_ON_8080);
        assertThat(r.<String>path("fixId")).isEqualTo(killPort);
        assertThat(r.<Integer>path("provenance.timesUsed")).isEqualTo(2);
    }

    private void feedback(Member m, String fixId, boolean worked) {
        m.auth().body(Map.of("worked", worked)).post("/fixes/{id}/feedback", fixId).then().statusCode(204);
    }
}
