package dev.recall.service;

import dev.recall.config.RecallProperties;
import dev.recall.domain.CapturedEvent;
import dev.recall.domain.EventBatch;
import dev.recall.repository.CommandEventRepository;
import dev.recall.service.embedding.CommandGlossary;
import dev.recall.service.embedding.EmbeddingService;
import dev.recall.service.errors.ErrorFingerprinter;
import dev.recall.service.errors.ErrorFingerprinter.Fingerprint;
import dev.recall.service.learning.LearningService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumer side of ingestion. For each new event: store it, with an intent embedding when it is the team's first
 * successful use of that command (repeats would only crowd the vector index; they are counted separately); for a
 * failure with output, store the error with its fingerprint and embedding; then let the learning loop see it.
 * Re-delivered events are skipped entirely, so the learning loop never counts one command twice.
 */
@Service
public class EventStore {

    public record StoreResult(int inserted, int duplicates, int errors) {}

    private final CommandEventRepository commands;
    private final EmbeddingService embeddings;
    private final CommandGlossary glossary;
    private final ErrorFingerprinter fingerprinter;
    private final LearningService learning;
    private final int rawErrorMaxChars;

    public EventStore(CommandEventRepository commands, EmbeddingService embeddings, CommandGlossary glossary,
                      ErrorFingerprinter fingerprinter, LearningService learning, RecallProperties properties) {
        this.commands = commands;
        this.embeddings = embeddings;
        this.glossary = glossary;
        this.fingerprinter = fingerprinter;
        this.learning = learning;
        this.rawErrorMaxChars = properties.events().rawErrorMaxChars();
    }

    @Transactional
    public StoreResult persist(EventBatch batch) {
        int inserted = 0;
        int duplicates = 0;
        int errors = 0;
        for (CapturedEvent event : batch.events()) {
            boolean firstUse = event.isSuccess() && !commands.hasIntentVector(batch.teamId(), event.commandText());
            float[] intent = firstUse ? embeddings.embed(glossary.intentText(event.commandText())) : null;
            Optional<UUID> id = commands.insertIfAbsent(batch.teamId(), batch.userId(), event, intent);
            if (id.isEmpty()) {
                duplicates++;
                continue;
            }
            inserted++;
            Fingerprint fingerprint = null;
            float[] errorVector = null;
            if (event.isFailureWithOutput()) {
                String raw = keepTail(event.errorText(), rawErrorMaxChars);
                fingerprint = fingerprinter.fingerprint(raw, event.osUser());
                errorVector = embeddings.embed(fingerprint.normalized());
                commands.insertError(id.get(), batch.teamId(), raw, fingerprint.normalized(), fingerprint.sha256(),
                        errorVector);
                errors++;
            }
            learning.observe(batch.teamId(), batch.userId(), event, fingerprint, errorVector);
        }
        return new StoreResult(inserted, duplicates, errors);
    }

    /** Tools print their error summary last, so the tail is the part worth keeping. */
    static String keepTail(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
    }
}
