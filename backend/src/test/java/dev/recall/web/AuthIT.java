package dev.recall.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import dev.recall.IntegrationTestBase;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthIT extends IntegrationTestBase {

    @Test
    void registerReturnsTokenTeamAndInviteCode() {
        given().contentType(ContentType.JSON)
                .body(Map.of("teamName", "Core", "email", uniqueEmail("ada"), "password", "correct-horse",
                        "displayName", "Ada"))
                .post("/auth/register")
                .then()
                .statusCode(201)
                .body("token", notNullValue())
                .body("expiresAt", notNullValue())
                .body("teamId", notNullValue())
                .body("inviteCode", matchesPattern("[A-HJ-NP-Z2-9]{10}"));
    }

    @Test
    void joinWithInviteCodeAndLoginAfterwards() {
        Member owner = registerTeam("Ada");
        String email = uniqueEmail("bob");

        given().contentType(ContentType.JSON)
                .body(Map.of("inviteCode", owner.inviteCode().toLowerCase(), "email", email,
                        "password", "correct-horse", "displayName", "Bob"))
                .post("/auth/join")
                .then().statusCode(201)
                .body("teamId", equalTo(owner.teamId().toString()))
                .body("$", not(hasKey("inviteCode")));

        given().contentType(ContentType.JSON)
                .body(Map.of("email", email.toUpperCase(), "password", "correct-horse"))
                .post("/auth/login")
                .then().statusCode(200)
                .body("token", notNullValue())
                .body("expiresAt", notNullValue());

        String hash = jdbc.queryForObject("select password_hash from users where email = ?", String.class, email);
        assertThat(hash).startsWith("$2").doesNotContain("correct-horse");
    }

    @Test
    void duplicateEmailIs409Problem() {
        Member owner = registerTeam("Ada");

        given().contentType(ContentType.JSON)
                .body(Map.of("teamName", "Other", "email", owner.email(), "password", "correct-horse",
                        "displayName", "Ada again"))
                .post("/auth/register")
                .then().statusCode(409).contentType("application/problem+json")
                .body("title", equalTo("Email already registered"));
    }

    @Test
    void unknownInviteCodeIs404Problem() {
        given().contentType(ContentType.JSON)
                .body(Map.of("inviteCode", "ZZZZZZZZZZ", "email", uniqueEmail("eve"), "password", "correct-horse",
                        "displayName", "Eve"))
                .post("/auth/join")
                .then().statusCode(404).contentType("application/problem+json");
    }

    @Test
    void wrongPasswordIs401Problem() {
        Member owner = registerTeam("Ada");

        given().contentType(ContentType.JSON)
                .body(Map.of("email", owner.email(), "password", "wrong-horse"))
                .post("/auth/login")
                .then().statusCode(401).contentType("application/problem+json")
                .body("detail", equalTo("Email or password is incorrect."));
    }

    @Test
    void invalidFieldsAre400ProblemWithFieldErrors() {
        given().contentType(ContentType.JSON)
                .body(Map.of("teamName", "", "email", "not-an-email", "password", "short", "displayName", "X"))
                .post("/auth/register")
                .then().statusCode(400).contentType("application/problem+json")
                .body("errors.field", hasItem("email"))
                .body("errors.field", hasItem("password"))
                .body("errors.field", hasItem("teamName"));
    }

    @Test
    void malformedJsonIs400Problem() {
        given().contentType(ContentType.JSON).body("{not json")
                .post("/auth/login")
                .then().statusCode(400).contentType("application/problem+json");
    }
}
