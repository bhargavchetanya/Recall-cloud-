package dev.recall.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;

import dev.recall.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ErrorsIT extends IntegrationTestBase {

    @Test
    void resolveValidatesItsInput() {
        Member dev = registerTeam("Val");

        dev.auth().body(Map.of("commandText", "npm test", "errorText", " ", "exitCode", 1)).post("/errors/resolve")
                .then().statusCode(400).contentType("application/problem+json").body("errors.field", hasItem("errorText"));
        dev.auth().body(Map.of("commandText", "npm test", "errorText", "boom")).post("/errors/resolve")
                .then().statusCode(400).body("errors.field", hasItem("exitCode"));
    }

    @Test
    void feedbackValidatesItsInput() {
        Member dev = registerTeam("Val");

        dev.auth().body(Map.of()).post("/fixes/{id}/feedback", UUID.randomUUID())
                .then().statusCode(400).body("errors.field", hasItem("worked"));
        dev.auth().body(Map.of("worked", true)).post("/fixes/not-a-uuid/feedback")
                .then().statusCode(400).contentType("application/problem+json");
    }

    @Test
    void errorEventsGetFingerprintAndVectorAndTheUsernameIsNormalizedAway() {
        Member dev = registerTeam("Uma");
        String session = UUID.randomUUID().toString();
        runSession(dev, List.of(with(failed(session, 1, "sudo apt install x",
                "E: user umadev is not allowed to run sudo on build-01"), "osUser", "umadev")));

        Map<String, Object> row = jdbc.queryForMap("select normalized_error, fingerprint, vector_dims(error_vector) as dims "
                + "from error_events where team_id = ?", dev.teamId());
        assertThat(row.get("normalized_error")).isEqualTo("e: user <user> is not allowed to run sudo on build-<num>");
        assertThat((String) row.get("fingerprint")).hasSize(64);
        assertThat(row.get("dims")).isEqualTo(384);
    }
}
