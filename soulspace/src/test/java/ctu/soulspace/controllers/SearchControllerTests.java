package ctu.soulspace.controllers;

import ctu.soulspace.repositories.SearchRepository;
import ctu.soulspace.services.SearchEmbedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class SearchControllerTests {
    private final SearchEmbedding embeddings = mock(SearchEmbedding.class);
    private final SearchRepository blogs = mock(SearchRepository.class);
    private final SearchController controller = new SearchController(embeddings, blogs);

    @Test
    void rejectsInvalidQueriesBeforeCallingJina() {
        for (String query : List.of("  ", "a".repeat(2001))) {
            var error = assertThrows(ResponseStatusException.class, () -> controller.search(query));
            assertEquals(400, error.getStatusCode().value());
        }
        verifyNoInteractions(embeddings, blogs);
    }

    @Test
    void trimsQueryAndReturnsRepositoryResults() {
        var results = List.of(new SearchRepository.SearchResult(UUID.randomUUID(), "Bài viết", "demo01", 0.8));
        when(embeddings.embedQuery("Áp lực ôn thi")).thenReturn("[1]");
        when(blogs.search("[1]")).thenReturn(results);
        assertEquals(results, controller.search("  Áp lực ôn thi  "));
    }

    @Test
    void missingApiKeyReturnsUnavailable() {
        var error = assertThrows(ResponseStatusException.class,
                () -> new SearchEmbedding("").embedQuery("test"));
        assertEquals(503, error.getStatusCode().value());
    }
}
