package dev.recall.acceptance;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import dev.recall.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** T2 — a question nothing in the team's history answers gives NONE. */
class T2SearchMissIT extends IntegrationTestBase {

    static final String NONSENSE = "purple elephants sing sonatas under the ocean";

    @Test
    void nonsenseQueryIsNone() {
        Member dev = registerTeam("Nora");
        seedAndWait(this, dev);

        dev.auth().body(Map.of("query", NONSENSE))
                .post("/search")
                .then()
                .statusCode(200)
                .body("source", equalTo("NONE"))
                .body("command", nullValue())
                .body("confidence", nullValue())
                .body("provenance", nullValue());
    }

    @Test
    void emptyHistoryIsNone() {
        registerTeam("Empty").auth().body(Map.of("query", "list running containers"))
                .post("/search").then().statusCode(200).body("source", equalTo("NONE"));
    }

    /** Two successful commands, so the team has history but nothing close to the question. */
    static void seedAndWait(IntegrationTestBase test, Member dev) {
        dev.auth().body(batch(List.of(event("s-" + dev.userId(), 1, "git status", 0),
                        event("s-" + dev.userId(), 2, "npm test", 0))))
                .post("/events").then().statusCode(202);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                .until(() -> test.commandCount(dev.userId()) == 2);
    }
}
