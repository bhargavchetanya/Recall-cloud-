package dev.recall.web.dto;

import dev.recall.service.errors.FixResolver;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ErrorDtos {

    private ErrorDtos() {}

    /** A command that just failed, with its output. {@code osUser} only helps normalize the error text. */
    public record ResolveRequest(
            @NotBlank @Size(max = 8192) String commandText,
            @NotBlank @Size(max = 65536) String errorText,
            @NotNull Integer exitCode,
            @Size(max = 1024) String cwd,
            @Size(max = 200) String repoName,
            @Size(max = 100) String shell,
            @Size(max = 50) String os,
            @Size(max = 100) String osUser) {

        public FixResolver.ErrorQuery toQuery() {
            return new FixResolver.ErrorQuery(commandText, errorText, exitCode, cwd, repoName, shell, os, osUser);
        }
    }

    public record FeedbackRequest(@NotNull Boolean worked) {}
}
