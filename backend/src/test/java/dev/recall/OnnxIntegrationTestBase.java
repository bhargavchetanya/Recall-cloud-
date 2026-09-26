package dev.recall;

import org.springframework.test.context.TestPropertySource;

/**
 * Integration tests that need real meaning search run with the all-MiniLM-L6-v2 ONNX model instead of the hashing
 * embedder. The model must be in backend/models (scripts/fetch-model.sh); without it the context fails to start with
 * a message saying so. All subclasses share one Spring context.
 */
@TestPropertySource(properties = "recall.embedding.provider=onnx")
public abstract class OnnxIntegrationTestBase extends IntegrationTestBase {

    /** Posts successful commands for {@code member} in one terminal session and waits until all are stored. */
    protected void seedHistory(Member member, java.util.List<String> commands) {
        String session = java.util.UUID.randomUUID().toString();
        java.util.List<java.util.Map<String, Object>> events = new java.util.ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            events.add(event(session, i + 1, commands.get(i), 0));
        }
        int before = commandCount(member.userId());
        member.auth().body(batch(events)).post("/events").then().statusCode(202);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(30))
                .until(() -> commandCount(member.userId()) == before + commands.size());
    }
}
