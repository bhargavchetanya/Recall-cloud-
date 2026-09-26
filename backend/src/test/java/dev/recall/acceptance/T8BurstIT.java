package dev.recall.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.recall.IntegrationTestBase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T8 — 1 000 events posted within 10 s are all persisted within 30 s. Sent as 20 requests of 50 (the extension's
 * batch size) over 4 terminal sessions; one batch is then re-sent to prove retries do not create duplicates.
 */
class T8BurstIT extends IntegrationTestBase {

    private static final int EVENTS = 1_000;
    private static final int BATCH = 50;
    private static final int SESSIONS = 4;

    @Test
    void burstOfOneThousandEventsIsFullyPersisted() {
        Member dev = registerTeam("Bruno");
        List<List<Map<String, Object>>> batches = buildBatches();

        long start = System.nanoTime();
        batches.parallelStream().forEach(b ->
                dev.auth().body(batch(b)).post("/events").then().statusCode(202));
        Duration posting = Duration.ofNanos(System.nanoTime() - start);

        assertThat(posting).as("time to post all batches").isLessThan(Duration.ofSeconds(10));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .until(() -> commandCount(dev.userId()) == EVENTS);

        dev.auth().body(batch(batches.get(0))).post("/events").then().statusCode(202);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> commandCount(dev.userId()) == EVENTS);
        assertThat(jdbc.queryForObject("select count(distinct (session_id, seq)) from command_events where user_id = ?",
                Integer.class, dev.userId())).isEqualTo(EVENTS);
    }

    private static List<List<Map<String, Object>>> buildBatches() {
        List<String> sessions = new ArrayList<>();
        for (int s = 0; s < SESSIONS; s++) {
            sessions.add(UUID.randomUUID().toString());
        }
        List<List<Map<String, Object>>> batches = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>();
        for (int i = 0; i < EVENTS; i++) {
            String session = sessions.get(i % SESSIONS);
            current.add(event(session, i / SESSIONS, "npm test -- --shard=" + i, i % 7 == 0 ? 1 : 0));
            if (current.size() == BATCH) {
                batches.add(current);
                current = new ArrayList<>();
            }
        }
        return batches;
    }
}
