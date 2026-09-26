package dev.recall.web;

import dev.recall.domain.Answer;
import dev.recall.security.AuthUser;
import dev.recall.service.search.SearchService;
import dev.recall.web.dto.SearchDtos.SearchRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    /** One command for a task described in plain words, searched only within the caller's team. */
    @PostMapping("/search")
    public Answer search(AuthUser user, @Valid @RequestBody SearchRequest request) {
        return search.search(user.teamId(), request.toQuery());
    }
}
