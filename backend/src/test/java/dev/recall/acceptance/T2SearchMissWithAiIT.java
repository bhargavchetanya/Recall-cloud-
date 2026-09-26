package dev.recall.acceptance;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import dev.recall.IntegrationTestBase;
import dev.recall.StubFixSuggester;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/** T2 with the stub suggester standing in for the local AI model: the same miss comes back as source AI. */
@Import(StubFixSuggester.Config.class)
class T2SearchMissWithAiIT extends IntegrationTestBase {

    @Test
    void nonsenseQueryFallsBackToAi() {
        Member dev = registerTeam("Nadia");
        T2SearchMissIT.seedAndWait(this, dev);

        dev.auth().body(Map.of("query", T2SearchMissIT.NONSENSE))
                .post("/search")
                .then()
                .statusCode(200)
                .body("source", equalTo("AI"))
                .body("command", equalTo(StubFixSuggester.COMMAND))
                .body("explanation", equalTo(StubFixSuggester.EXPLANATION))
                .body("confidence", nullValue())
                .body("provenance", nullValue());
    }
}
