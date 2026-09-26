package dev.recall.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;

import dev.recall.IntegrationTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

class HealthIT extends IntegrationTestBase {

    @Test
    void healthReportsUpWithoutAuthentication() {
        given()
                .when().get("/health")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("db", equalTo("UP"))
                .body("queue", equalTo("UP"))
                .body("embedder", equalTo("UP"));
    }

    @Test
    void flywayCreatedTeamsAndUsers() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public' order by table_name",
                String.class);

        assertThat(tables).contains("teams", "users", "flyway_schema_history");
    }

    @Test
    void usersRejectUnknownRoleAndDuplicateEmailIgnoringCase() {
        jdbc.update("insert into teams (name, invite_code) values ('t', 'health-it-code')");
        String teamId = jdbc.queryForObject(
                "select id::text from teams where invite_code = 'health-it-code'", String.class);
        String insert = "insert into users (team_id, email, display_name, password_hash, role) values (?::uuid, ?, 'n', 'h', ?)";

        jdbc.update(insert, teamId, "Ada@Example.com", "OWNER");

        assertThatThrownBy(() -> jdbc.update(insert, teamId, "ada@example.com", "MEMBER"))
                .as("duplicate email differing only by case")
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update(insert, teamId, "bob@example.com", "ADMIN"))
                .as("role outside OWNER|MEMBER")
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
