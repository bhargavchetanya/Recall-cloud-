package dev.recall;

import dev.recall.service.ai.FixSuggester;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A canned AI so the AI path is testable without Ollama. Import {@link Config} into a test to activate it. */
public final class StubFixSuggester implements FixSuggester {

    public static final String COMMAND = "echo stub-ai-suggestion";
    public static final String EXPLANATION = "Canned suggestion from the test stub.";

    @Override
    public Optional<Suggestion> suggest(SuggestionRequest request) {
        return Optional.of(new Suggestion(COMMAND, EXPLANATION));
    }

    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        FixSuggester stubFixSuggester() {
            return new StubFixSuggester();
        }
    }
}
