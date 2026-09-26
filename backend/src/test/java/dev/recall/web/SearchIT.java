package dev.recall.web;

import static org.hamcrest.Matchers.hasItem;

import dev.recall.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchIT extends IntegrationTestBase {

    @Test
    void blankOrOverlongQueryIs400Problem() {
        Member dev = registerTeam("Quinn");

        dev.auth().body(Map.of("query", "  ")).post("/search")
                .then().statusCode(400).contentType("application/problem+json")
                .body("errors.field", hasItem("query"));
        dev.auth().body(Map.of("query", "x".repeat(501))).post("/search")
                .then().statusCode(400).body("errors.field", hasItem("query"));
        dev.auth().body(Map.of()).post("/search").then().statusCode(400);
    }
}
