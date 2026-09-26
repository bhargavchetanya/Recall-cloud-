package dev.recall.acceptance;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import dev.recall.IntegrationTestBase;
import dev.recall.StubFixSuggester;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/** An error nobody on the team has fixed falls back to the (stub) AI, labelled as such. */
@Import(StubFixSuggester.Config.class)
class ErrorResolveWithAiIT extends IntegrationTestBase {

    @Test
    void unknownErrorGetsAnAiSuggestion() {
        registerTeam("Ari").auth()
                .body(Map.of("commandText", "cargo build", "errorText", "error[E0425]: cannot find value", "exitCode", 101))
                .post("/errors/resolve")
                .then().statusCode(200)
                .body("source", equalTo("AI"))
                .body("command", equalTo(StubFixSuggester.COMMAND))
                .body("fixId", nullValue())
                .body("provenance", nullValue());
    }
}
