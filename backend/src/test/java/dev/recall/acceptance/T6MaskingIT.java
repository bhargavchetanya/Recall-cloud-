package dev.recall.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.recall.IntegrationTestBase;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T6 — secrets never reach the database, even from a client that did not mask them (this test sends raw text,
 * so it exercises the server-side pass). Asserted by reading the rows directly.
 */
class T6MaskingIT extends IntegrationTestBase {

    @Test
    void mysqlPasswordIsStoredMasked() {
        Member dev = registerTeam("Maya");
        String session = UUID.randomUUID().toString();

        dev.auth().body(batch(List.of(event(session, 1, "mysql -u root -pHunter2", 0))))
                .post("/events").then().statusCode(202);

        await().atMost(Duration.ofSeconds(10)).until(() -> commandCount(dev.userId()) == 1);
        Map<String, Object> row = jdbc.queryForMap(
                "select command_text, redacted from command_events where session_id = ?", session);
        assertThat(row.get("command_text")).isEqualTo("mysql -u root -p***");
        assertThat(row.get("redacted")).isEqualTo(true);
        assertThat(jdbc.queryForObject("select count(*) from command_events where command_text like '%Hunter2%'",
                Integer.class)).isZero();
    }

    @Test
    void secretsInErrorOutputAreMaskedToo() {
        Member dev = registerTeam("Mateo");
        String session = UUID.randomUUID().toString();
        Map<String, Object> failing = with(event(session, 1, "npm run migrate", 1), "errorText",
                "Error: password authentication failed for postgres://app:TopSecret1@db:5432/app");

        dev.auth().body(batch(List.of(failing))).post("/events").then().statusCode(202);

        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "select count(*) from error_events e join command_events c on c.id = e.command_event_id "
                        + "where c.session_id = ?", Integer.class, session) == 1);
        String raw = jdbc.queryForObject("select e.raw_error from error_events e join command_events c "
                + "on c.id = e.command_event_id where c.session_id = ?", String.class, session);
        assertThat(raw).isEqualTo("Error: password authentication failed for postgres://app:***@db:5432/app");
        assertThat(jdbc.queryForObject("select redacted from command_events where session_id = ?", Boolean.class,
                session)).isTrue();
    }

    @Test
    void cleanCommandIsStoredVerbatimAndNotRedacted() {
        Member dev = registerTeam("Milo");
        String session = UUID.randomUUID().toString();

        dev.auth().body(batch(List.of(event(session, 1, "docker run -p 3000:3000 node:20", 0))))
                .post("/events").then().statusCode(202);

        await().atMost(Duration.ofSeconds(10)).until(() -> commandCount(dev.userId()) == 1);
        Map<String, Object> row = jdbc.queryForMap(
                "select command_text, redacted from command_events where session_id = ?", session);
        assertThat(row.get("command_text")).isEqualTo("docker run -p 3000:3000 node:20");
        assertThat(row.get("redacted")).isEqualTo(false);
    }
}
