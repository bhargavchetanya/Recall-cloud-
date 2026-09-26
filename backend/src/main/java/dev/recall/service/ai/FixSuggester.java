package dev.recall.service.ai;

import java.util.Optional;

/**
 * Last resort when the team's history has no answer. Implementations must never throw and never block for long;
 * an empty result simply means "no suggestion".
 */
public interface FixSuggester {

    Optional<Suggestion> suggest(SuggestionRequest request);

    /** One command line and a short reason. */
    record Suggestion(String command, String explanation) {}

    /**
     * What to suggest for: a task in plain words (search), or a failed command with its error output (error
     * resolution). {@code errorText} is the masked output, not the normalized form: normalization replaces exactly
     * the details a model needs (the missing module's name, the port). Context fields may be null.
     */
    record SuggestionRequest(String task, String errorText, String commandText, String shell, String os,
                             String cwd, String repoName) {

        public static SuggestionRequest forTask(String task, String shell, String os, String cwd, String repoName) {
            return new SuggestionRequest(task, null, null, shell, os, cwd, repoName);
        }
    }
}
