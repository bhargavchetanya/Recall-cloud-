package dev.recall.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.recall.config.RecallProperties;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the fix suggester from {@code recall.ai.provider}: {@code noop} by default, {@code ollama} under the
 * {@code ai} profile (application-ai.yml). Whichever it is, every suggestion still passes {@link CommandSafety}.
 */
@Configuration
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    @ConditionalOnProperty(name = "recall.ai.provider", havingValue = "noop", matchIfMissing = true)
    FixSuggester noopFixSuggester() {
        return new NoopFixSuggester();
    }

    @Bean
    @ConditionalOnProperty(name = "recall.ai.provider", havingValue = "ollama")
    FixSuggester ollamaFixSuggester(RecallProperties properties, ObjectMapper json, Clock clock) {
        RecallProperties.Ai ai = properties.ai();
        log.info("AI fallback: Ollama model {} at {}", ai.model(), ai.ollamaUrl());
        return new OllamaFixSuggester(ai.ollamaUrl(), ai.model(), ai.timeout(), json, clock);
    }
}
