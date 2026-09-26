package dev.recall.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import dev.recall.IntegrationTestBase;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RateLimitAndProblemsIT extends IntegrationTestBase {

    /**
     * The bucket holds 100 and refills continuously (100 per minute), so while the burst is being sent a token or
     * two trickles back in: the first 429 must come right after the 100th request, give or take that refill.
     */
    @Test
    void aBurstIsCutOffAtAHundredBatchesAMinuteWith429AndRetryAfter() {
        Member busy = registerTeam("Busy");
        Member calm = joinTeam(busy, "Calm");
        Map<String, Object> body = batch(List.of(event("s", 1, "ls", 0)));

        long start = System.nanoTime();
        Response limited = null;
        int accepted = 0;
        while (limited == null && accepted < 150) {
            Response r = busy.auth().body(body).post("/events").then().extract().response();
            if (r.statusCode() == 202) {
                accepted++;
            } else {
                limited = r;
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;

        assertThat(limited).as("a burst must be limited").isNotNull();
        assertThat(accepted).isBetween(100, 100 + (int) Math.ceil(seconds * 100 / 60) + 1);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.contentType()).startsWith("application/problem+json");
        assertThat(Integer.parseInt(limited.header("Retry-After"))).isBetween(1, 60);
        assertThat(limited.<String>path("title")).isEqualTo("Too many requests");
        calm.auth().body(body).post("/events").then().statusCode(202);
    }

    @Test
    void unknownRoutesAndWrongMethodsAreProblems() {
        Member dev = registerTeam("Prob");

        dev.auth().get("/no-such-thing").then().statusCode(404).contentType("application/problem+json");
        dev.auth().get("/search").then().statusCode(405).contentType("application/problem+json")
                .body("status", equalTo(405));
    }
}
