package dev.recall.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import dev.recall.IntegrationTestBase;
import dev.recall.messaging.EventRouting;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

class EventsIT extends IntegrationTestBase {

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    AmqpAdmin admin;

    @Autowired
    EventRouting routing;

    @Test
    void failedCommandWithOutputCreatesErrorEventKeepingTheTail() {
        Member dev = registerTeam("Eli");
        String session = UUID.randomUUID().toString();
        String noise = "x".repeat(20_000);
        Map<String, Object> failing = with(event(session, 1, "npm run dev", 1), "errorText",
                noise + "\nError: listen EADDRINUSE: address already in use :::3000");
        Map<String, Object> passing = with(event(session, 2, "npx kill-port 3000", 0), "errorText", "killed");

        dev.auth().body(batch(List.of(failing, passing))).post("/events")
                .then().statusCode(202).body("accepted", equalTo(2));

        await().atMost(Duration.ofSeconds(10)).until(() -> commandCount(dev.userId()) == 2);
        List<String> errors = jdbc.queryForList("select e.raw_error from error_events e join command_events c "
                + "on c.id = e.command_event_id where c.session_id = ?", String.class, session);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).hasSize(8192).endsWith("address already in use :::3000");
        Map<String, Object> row = jdbc.queryForMap(
                "select cwd, repo_name, shell, os, exit_code, duration_ms from command_events "
                        + "where session_id = ? and seq = 1", session);
        assertThat(row).containsEntry("cwd", "/home/dev/app").containsEntry("repo_name", "app")
                .containsEntry("shell", "bash").containsEntry("os", "linux").containsEntry("exit_code", 1)
                .containsEntry("duration_ms", 42L);
    }

    @Test
    void onlySuccessfulCommandsGetAnIntentVector() {
        Member dev = registerTeam("Vera");
        String session = UUID.randomUUID().toString();

        dev.auth().body(batch(List.of(event(session, 1, "git status", 0), event(session, 2, "git pusj", 1))))
                .post("/events").then().statusCode(202);

        await().atMost(Duration.ofSeconds(10)).until(() -> commandCount(dev.userId()) == 2);
        assertThat(jdbc.queryForList("select exit_code from command_events where session_id = ? "
                + "and intent_vector is not null", Integer.class, session)).containsExactly(0);
        assertThat(jdbc.queryForObject("select vector_dims(intent_vector) from command_events where session_id = ? "
                + "and seq = 1", Integer.class, session)).isEqualTo(384);
    }

    @Test
    void unknownExitCodeIsStoredAsNull() {
        Member dev = registerTeam("Nia");
        String session = UUID.randomUUID().toString();

        dev.auth().body(batch(List.of(with(event(session, 1, "make", 0), "exitCode", null))))
                .post("/events").then().statusCode(202);

        await().atMost(Duration.ofSeconds(10)).until(() -> commandCount(dev.userId()) == 1);
        assertThat(jdbc.queryForObject("select exit_code from command_events where session_id = ?", Integer.class,
                session)).isNull();
    }

    @Test
    void invalidBatchesAreRejectedWith400() {
        Member dev = registerTeam("Ivan");
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            tooMany.add(event("s", i, "ls", 0));
        }

        dev.auth().body(batch(tooMany)).post("/events")
                .then().statusCode(400).contentType("application/problem+json")
                .body("errors.field", hasItem("events"));
        dev.auth().body(batch(List.of())).post("/events").then().statusCode(400);
        dev.auth().body(batch(List.of(with(event("s", 1, "ls", 0), "startedAt", null)))).post("/events")
                .then().statusCode(400).body("errors.field", hasItem("events[0].startedAt"));
        dev.auth().body(batch(List.of(with(event("s", 1, "ls", 0), "commandText", " ")))).post("/events")
                .then().statusCode(400).body("errors.field", hasItem("events[0].commandText"));
    }

    @Test
    void unreadableMessageIsDeadLetteredNotLost() {
        int before = deadLetterCount();

        rabbit.send(routing.exchange(), routing.routingKey(),
                new Message("{not json".getBytes(), new MessageProperties()));

        await().atMost(Duration.ofSeconds(15)).until(() -> deadLetterCount() == before + 1);
    }

    private int deadLetterCount() {
        return Objects.requireNonNull(admin.getQueueInfo(routing.deadLetterQueue())).getMessageCount();
    }
}
