package dev.recall.acceptance;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import com.sun.net.httpserver.HttpServer;
import dev.recall.IntegrationTestBase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The {@code ai} path end to end with the real Ollama client against a fake Ollama: answers come back labelled AI,
 * destructive suggestions never reach the user, and a broken Ollama degrades to NONE. The broken case runs last
 * because it starts the client's one-minute cool-down.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiFallbackIT extends IntegrationTestBase {

    static final AtomicReference<String> COMMAND = new AtomicReference<>("du -sh * | sort -h");
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final HttpServer OLLAMA = fakeOllama();

    @DynamicPropertySource
    static void useFakeOllama(DynamicPropertyRegistry registry) {
        registry.add("recall.ai.provider", () -> "ollama");
        registry.add("recall.ai.ollama-url", () -> "http://127.0.0.1:" + OLLAMA.getAddress().getPort());
    }

    @Test
    @Order(1)
    void unansweredSearchGetsTheModelsCommandLabelledAi() {
        COMMAND.set("du -sh * | sort -h");
        registerTeam("Ai").auth().body(Map.of("query", "which folders use the most space", "shell", "bash"))
                .post("/search")
                .then().statusCode(200)
                .body("source", equalTo("AI"))
                .body("command", equalTo("du -sh * | sort -h"))
                .body("provenance", nullValue())
                .body("confidence", nullValue())
                .body("explanation", equalTo("Suggested by the local AI model, not by your team. Check it before running."));
    }

    @Test
    @Order(2)
    void unknownErrorGetsTheModelsFix() {
        COMMAND.set("rustup update");
        resolveUnknown()
                .then().statusCode(200)
                .body("source", equalTo("AI"))
                .body("command", equalTo("rustup update"))
                .body("fixId", nullValue());
    }

    @Test
    @Order(3)
    void destructiveSuggestionsAreNeverShown() {
        for (String dangerous : new String[] {"sudo rm -rf /", "git push --force origin main", ":(){ :|:& };:"}) {
            COMMAND.set(dangerous);
            registerTeam("Safe").auth().body(Map.of("query", "clean everything up"))
                    .post("/search").then().statusCode(200).body("source", equalTo("NONE"));
        }
    }

    @Test
    @Order(4)
    void brokenOllamaDegradesToNone() {
        STATUS.set(503);
        registerTeam("Down").auth().body(Map.of("query", "which folders use the most space"))
                .post("/search").then().statusCode(200).body("source", equalTo("NONE"));
        resolveUnknown().then().statusCode(200).body("source", equalTo("NONE"));
    }

    private io.restassured.response.Response resolveUnknown() {
        return registerTeam("Rust").auth()
                .body(Map.of("commandText", "cargo build", "errorText", "error: package requires rustc 1.80", "exitCode", 101))
                .post("/errors/resolve");
    }

    private static HttpServer fakeOllama() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/generate", exchange -> {
                exchange.getRequestBody().readAllBytes();
                String escaped = COMMAND.get().replace("\\", "\\\\").replace("\"", "\\\"");
                byte[] body = ("{\"response\":\"" + escaped + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(STATUS.get(), body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
