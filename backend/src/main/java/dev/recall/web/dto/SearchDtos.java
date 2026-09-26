package dev.recall.web.dto;

import dev.recall.service.search.SearchService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class SearchDtos {

    private SearchDtos() {}

    /** The task in plain words plus optional context; a {@code teamId} in the body is ignored. */
    public record SearchRequest(
            @NotBlank @Size(max = 500) String query,
            @Size(max = 1024) String cwd,
            @Size(max = 200) String repoName,
            @Size(max = 100) String shell,
            @Size(max = 50) String os) {

        public SearchService.Query toQuery() {
            return new SearchService.Query(query.strip(), cwd, repoName, shell, os);
        }
    }
}
