package dev.recall.service.ai;

import java.util.Optional;

/** The default: no AI, so unanswered questions come back as {@code source: NONE}. */
public final class NoopFixSuggester implements FixSuggester {

    @Override
    public Optional<Suggestion> suggest(SuggestionRequest request) {
        return Optional.empty();
    }
}
