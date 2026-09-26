package dev.recall;

import static io.restassured.RestAssured.given;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real HTTP against a real app, backed by throwaway pgvector + RabbitMQ containers.
 * Containers are started once per JVM and shared by every integration test class, so each test
 * creates its own team and users with unique emails instead of relying on an empty database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @ServiceConnection
    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3-management"));

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
        RestAssured.basePath = "/api/v1";
    }

    /** A signed-in user of a team created or joined for this test. */
    protected record Member(String token, UUID teamId, UUID userId, String email, String inviteCode) {

        public RequestSpecification auth() {
            return given().contentType(ContentType.JSON).header("Authorization", "Bearer " + token);
        }
    }

    protected Member registerTeam(String displayName) {
        String email = uniqueEmail(displayName);
        Response r = given().contentType(ContentType.JSON)
                .body(Map.of("teamName", displayName + "'s team", "email", email, "password", "correct-horse",
                        "displayName", displayName))
                .post("/auth/register")
                .then().statusCode(201).extract().response();
        return member(r, email, r.path("inviteCode"));
    }

    protected Member joinTeam(Member owner, String displayName) {
        String email = uniqueEmail(displayName);
        Response r = given().contentType(ContentType.JSON)
                .body(Map.of("inviteCode", owner.inviteCode(), "email", email, "password", "correct-horse",
                        "displayName", displayName))
                .post("/auth/join")
                .then().statusCode(201).extract().response();
        return member(r, email, owner.inviteCode());
    }

    private Member member(Response r, String email, String inviteCode) {
        UUID teamId = UUID.fromString(r.path("teamId"));
        UUID userId = jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
        return new Member(r.path("token"), teamId, userId, email, inviteCode);
    }

    protected static String uniqueEmail(String name) {
        return name.toLowerCase().replaceAll("[^a-z]", "") + "-" + UUID.randomUUID() + "@example.com";
    }

    /** An event as the extension would send it; override fields with {@link #with}. */
    protected static Map<String, Object> event(String sessionId, long seq, String command, int exitCode) {
        Map<String, Object> e = new HashMap<>();
        e.put("sessionId", sessionId);
        e.put("seq", seq);
        e.put("commandText", command);
        e.put("cwd", "/home/dev/app");
        e.put("repoName", "app");
        e.put("shell", "bash");
        e.put("os", "linux");
        e.put("exitCode", exitCode);
        e.put("startedAt", Instant.now().toString());
        e.put("durationMs", 42);
        return e;
    }

    protected static Map<String, Object> with(Map<String, Object> event, String key, Object value) {
        Map<String, Object> copy = new HashMap<>(event);
        copy.put(key, value);
        return copy;
    }

    protected static Map<String, Object> batch(List<Map<String, Object>> events) {
        return Map.of("events", events);
    }

    public int commandCount(UUID userId) {
        return jdbc.queryForObject("select count(*) from command_events where user_id = ?", Integer.class, userId);
    }

    /** A failed command with its output, as the extension sends it. */
    protected static Map<String, Object> failed(String sessionId, long seq, String command, String errorText) {
        return with(event(sessionId, seq, command, 1), "errorText", errorText);
    }

    /** Posts one terminal session's events for {@code m} and waits until the consumer has stored all of them. */
    protected void runSession(Member m, List<Map<String, Object>> events) {
        int before = commandCount(m.userId());
        m.auth().body(batch(events)).post("/events").then().statusCode(202);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15))
                .until(() -> commandCount(m.userId()) == before + events.size());
    }

    /** A failure followed by the command that fixed it, in a new terminal session. */
    protected void failThenFix(Member m, String failedCommand, String errorText, String fixCommand) {
        String session = UUID.randomUUID().toString();
        runSession(m, List.of(failed(session, 1, failedCommand, errorText), event(session, 2, fixCommand, 0)));
    }

    protected List<Map<String, Object>> fixRows(UUID teamId) {
        return jdbc.queryForList("select f.id, f.fix_command, f.fingerprint, f.source, f.worked_count, f.failed_count, "
                + "u.display_name as solved_by from fixes f left join users u on u.id = f.solved_by_user_id "
                + "where f.team_id = ? order by f.created_at", teamId);
    }

    protected io.restassured.response.Response resolve(Member m, String command, String errorText) {
        return m.auth().body(Map.of("commandText", command, "errorText", errorText, "exitCode", 1))
                .post("/errors/resolve").then().statusCode(200).extract().response();
    }
}
