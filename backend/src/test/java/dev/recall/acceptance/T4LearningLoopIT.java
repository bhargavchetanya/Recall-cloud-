package dev.recall.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import dev.recall.IntegrationTestBase;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T4 — the whole learning path through real HTTP, RabbitMQ and Postgres: user A's dev server fails because the port
 * is taken, A frees the port, and user B, hitting the same error on another port, is offered A's command.
 */
class T4LearningLoopIT extends IntegrationTestBase {

    @Test
    void aFixLearnedFromOneTeammateIsOfferedToAnother() {
        Member ada = registerTeam("Ada");
        Member bo = joinTeam(ada, "Bo");

        failThenFix(ada, "npm run dev", T3KnownErrorIT.ERROR_ON_3000, "npx kill-port 3000");

        List<Map<String, Object>> rows = fixRows(ada.teamId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("fix_command", "npx kill-port 3000").containsEntry("source", "LEARNED")
                .containsEntry("worked_count", 1).containsEntry("failed_count", 0).containsEntry("solved_by", "Ada");

        Response r = resolve(bo, "npm run dev", T3KnownErrorIT.ERROR_ON_8080);
        assertThat(r.<String>path("command")).isEqualTo("npx kill-port 3000");
        assertThat(r.<String>path("source")).isEqualTo("TEAM");
        assertThat(r.<String>path("provenance.solvedBy")).isEqualTo("Ada");
        assertThat(r.<String>path("fixId")).isEqualTo(rows.get(0).get("id").toString());
    }

    @Test
    void usingTheFixAgainCountsAsAnotherSuccessAndKeepsTheOriginalSolver() {
        Member ada = registerTeam("Ada");
        Member bo = joinTeam(ada, "Bo");
        failThenFix(ada, "npm run dev", T3KnownErrorIT.ERROR_ON_3000, "npx kill-port 3000");

        failThenFix(bo, "npm start", T3KnownErrorIT.ERROR_ON_8080, "npx kill-port 3000");

        List<Map<String, Object>> rows = fixRows(ada.teamId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("worked_count", 2).containsEntry("solved_by", "Ada");
        assertThat(resolve(bo, "npm run dev", T3KnownErrorIT.ERROR_ON_3000).<Integer>path("provenance.timesUsed"))
                .isEqualTo(2);
    }

    @Test
    void lookingAroundAndUnrelatedCommandsAreNotLearnedButAFixAFewCommandsLaterIs() {
        Member ada = registerTeam("Ada");
        String s = UUID.randomUUID().toString();

        runSession(ada, List.of(
                failed(s, 1, "npm run dev", T3KnownErrorIT.ERROR_ON_3000),
                event(s, 2, "git status", 0),
                event(s, 3, "ls -la", 0),
                event(s, 4, "docker ps", 0),
                event(s, 5, "npm run dev", 0),
                event(s, 6, "lsof -i :3000", 0)));
        assertThat(fixRows(ada.teamId())).as("nothing qualifies yet").isEmpty();

        runSession(ada, List.of(event(s, 7, "kill -9 4242", 0)));
        assertThat(fixRows(ada.teamId())).as("seq 7 is more than 5 commands after the failure").isEmpty();

        String s2 = UUID.randomUUID().toString();
        runSession(ada, List.of(
                failed(s2, 1, "npm run dev", T3KnownErrorIT.ERROR_ON_3000),
                event(s2, 2, "lsof -i :3000", 0),
                event(s2, 3, "kill -9 4242", 0)));
        assertThat(fixRows(ada.teamId())).extracting(row -> row.get("fix_command")).containsExactly("kill -9 4242");
    }

    @Test
    void aFailureInAnotherTerminalDoesNotGetFixedByThisOne() {
        Member ada = registerTeam("Ada");

        runSession(ada, List.of(failed("terminal-1", 1, "npm run dev", T3KnownErrorIT.ERROR_ON_3000)));
        runSession(ada, List.of(event("terminal-2", 1, "npx kill-port 3000", 0)));

        assertThat(fixRows(ada.teamId())).isEmpty();
    }
}
