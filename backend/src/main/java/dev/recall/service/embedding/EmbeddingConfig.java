package dev.recall.service.embedding;

import dev.recall.config.RecallProperties;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the embedder from {@code recall.embedding.provider}: {@code onnx} by default, {@code hashing} under the
 * {@code test} and {@code offline} profiles (see application-*.yml). Either way it is wrapped in an LRU cache.
 */
@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    @Bean
    @ConditionalOnProperty(name = "recall.embedding.provider", havingValue = "onnx", matchIfMissing = true)
    EmbeddingService onnxEmbeddingService(RecallProperties properties) {
        OnnxEmbeddingService onnx = OnnxEmbeddingService.load(Path.of(properties.embedding().modelDir()));
        log.info("Embeddings: {} from {}", onnx.name(), Path.of(properties.embedding().modelDir()).toAbsolutePath());
        return new CachingEmbeddingService(onnx, properties.embedding().cacheSize());
    }

    @Bean
    @ConditionalOnProperty(name = "recall.embedding.provider", havingValue = "hashing")
    EmbeddingService hashingEmbeddingService(RecallProperties properties) {
        log.warn("Embeddings: hashing (no model). Search matches shared words only.");
        return new CachingEmbeddingService(new HashingEmbeddingService(), properties.embedding().cacheSize());
    }
}
