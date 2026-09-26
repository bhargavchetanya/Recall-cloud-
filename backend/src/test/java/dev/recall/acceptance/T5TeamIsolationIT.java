package dev.recall.acceptance;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import dev.recall.IntegrationTestBase;
import dev.recall.service.TokenService;
import io.restassured.http.ContentType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * T5 — a user of team X can never write into or read from team Y. The team always comes from the JWT.
 * This class grows as endpoints that read team data (search, errors, fixes) are added.
 */
class T5TeamIsolationIT extends IntegrationTestBase {

    @Value("${recall.jwt.secret}")
    String jwtSecret;

    @Test
    void eventsWithoutTokenAre401ProblemJson() {
        given().contentType(ContentType.JSON)
                .body(batch(List.of(event("s", 1, "ls", 0))))
                .post("/events")
                .then()
                .statusCode(401)
                .contentType("application/problem+json")
                .header("WWW-Authenticate", "Bearer")
                .body("status", equalTo(401));
    }

    @Test
    void garbageForgedAndExpiredTokensAre401() {
        Member x = registerTeam("Xavier");
        String forged = sign("a-completely-different-secret-of-32-bytes!", x, Instant.now().plus(Duration.ofHours(1)));
        String expired = sign(jwtSecret, x, Instant.now().minus(Duration.ofMinutes(5)));

        for (String token : List.of("not-a-jwt", forged, expired)) {
            given().contentType(ContentType.JSON).header("Authorization", "Bearer " + token)
                    .body(batch(List.of(event("s", 1, "ls", 0))))
                    .post("/events")
                    .then().statusCode(401).contentType("application/problem+json");
        }
    }

    @Test
    void teamIdInBodyIsIgnoredAndEventsLandInCallersTeam() {
        Member x = registerTeam("Xena");
        Member y = registerTeam("Yusuf");
        String session = UUID.randomUUID().toString();

        Map<String, Object> smuggled = with(with(event(session, 1, "git push origin main", 0),
                "teamId", y.teamId().toString()), "userId", y.userId().toString());
        x.auth()
                .body(Map.of("teamId", y.teamId().toString(), "userId", y.userId().toString(),
                        "events", List.of(smuggled)))
                .post("/events")
                .then().statusCode(202).body("accepted", equalTo(1));

        await().atMost(Duration.ofSeconds(10)).until(() -> commandCount(x.userId()) == 1);
        Map<String, Object> row = jdbc.queryForMap(
                "select team_id, user_id from command_events where session_id = ?", session);
        assertThat(row.get("team_id")).isEqualTo(x.teamId());
        assertThat(row.get("user_id")).isEqualTo(x.userId());
        assertThat(jdbc.queryForObject("select count(*) from command_events where team_id = ?", Integer.class,
                y.teamId())).isZero();
    }

    @Test
    void searchWithoutTokenIs401() {
        given().contentType(ContentType.JSON).body(Map.of("query", "list pods"))
                .post("/search")
                .then().statusCode(401).contentType("application/problem+json");
    }

    @Test
    void searchNeverReturnsAnotherTeamsCommandEvenWithTheirTeamIdInTheBody() {
        Member x = registerTeam("Xiu");
        Member y = registerTeam("Yara");
        String command = "kubectl rollout restart deployment/payments -n prod";
        y.auth().body(batch(List.of(event(UUID.randomUUID().toString(), 1, command, 0)))).post("/events")
                .then().statusCode(202);
        await().atMost(Duration.ofSeconds(10)).until(() -> commandCount(y.userId()) == 1);

        y.auth().body(Map.of("query", command)).post("/search")
                .then().statusCode(200).body("command", equalTo(command)).body("source", equalTo("TEAM"));
        x.auth().body(Map.of("query", command, "teamId", y.teamId().toString())).post("/search")
                .then().statusCode(200).body("source", equalTo("NONE")).body("command", nullValue());
    }

    @Test
    void errorEndpointsWithoutTokenAre401() {
        given().contentType(ContentType.JSON)
                .body(Map.of("commandText", "npm run dev", "errorText", "boom", "exitCode", 1))
                .post("/errors/resolve").then().statusCode(401).contentType("application/problem+json");
        given().contentType(ContentType.JSON).body(Map.of("worked", false))
                .post("/fixes/{id}/feedback", UUID.randomUUID()).then().statusCode(401);
    }

    @Test
    void anotherTeamsFixIsNeverReturnedAndFeedbackOnItIs403() {
        Member x = registerTeam("Xan");
        Member y = registerTeam("Yoko");
        failThenFix(y, "npm run dev", T3KnownErrorIT.ERROR_ON_3000, "npx kill-port 3000");
        String yFix = fixRows(y.teamId()).get(0).get("id").toString();

        x.auth().body(Map.of("commandText", "npm run dev", "errorText", T3KnownErrorIT.ERROR_ON_3000, "exitCode", 1,
                        "teamId", y.teamId().toString()))
                .post("/errors/resolve")
                .then().statusCode(200).body("source", equalTo("NONE")).body("fixId", nullValue());

        x.auth().body(Map.of("worked", false)).post("/fixes/{id}/feedback", yFix)
                .then().statusCode(403).contentType("application/problem+json");
        assertThat(fixRows(y.teamId()).get(0)).containsEntry("failed_count", 0);

        x.auth().body(Map.of("worked", false)).post("/fixes/{id}/feedback", UUID.randomUUID())
                .then().statusCode(404).contentType("application/problem+json");
    }

    @Test
    void membersOfTheSameTeamShareATeamButNotAUser() {
        Member owner = registerTeam("Olga");
        Member member = joinTeam(owner, "Mehmet");

        assertThat(member.teamId()).isEqualTo(owner.teamId());
        assertThat(member.userId()).isNotEqualTo(owner.userId());
    }

    private static String sign(String secret, Member who, Instant expiresAt) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(TokenService.ISSUER)
                .subject(who.userId().toString())
                .issuedAt(expiresAt.minus(Duration.ofHours(2)))
                .expiresAt(expiresAt)
                .claim(TokenService.TEAM_CLAIM, who.teamId().toString())
                .build();
        return new NimbusJwtEncoder(new ImmutableSecret<>(key))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
