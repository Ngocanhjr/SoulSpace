package ctu.soulspace.controllers;

import ctu.soulspace.controller.SearchController;
import ctu.soulspace.exception.GlobalExceptionHandler;
import ctu.soulspace.repositories.SearchRepository;
import ctu.soulspace.service.SearchEmbeddingService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class SearchControllerTests {
    private final SearchEmbeddingService embeddings = mock(SearchEmbeddingService.class);
    private final SearchRepository blogs = mock(SearchRepository.class);
    private final SearchController controller = new SearchController(embeddings, blogs);

    @Test
    void rejectsInvalidQueriesBeforeCallingJina() {
        for (String query : List.of("  ", "a".repeat(2001))) {
            var error = assertThrows(ResponseStatusException.class, () -> controller.search(query, 50));
            assertEquals(400, error.getStatusCode().value());
        }
        verifyNoInteractions(embeddings, blogs);
    }

    @Test
    void trimsQueryAndReturnsRepositoryResults() {
        var results = List.of(new SearchRepository.SearchResult(UUID.randomUUID(), "Bài viết", "demo01", 0.8));
        when(embeddings.embedQuery("Áp lực ôn thi")).thenReturn("[1]");
        when(blogs.search("[1]", 50)).thenReturn(results);
        assertEquals(results, controller.search("  Áp lực ôn thi  ", 50));
    }

    @Test
    void rejectsInvalidResultLimitsBeforeCallingJina() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String limit : List.of("0", "51", "not-a-number")) {
            mvc.perform(get("/api/v1/search").param("q", "test").param("limit", limit))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(embeddings, blogs);
    }

    @Test
    void bindsSuggestionLimitAndDefaultsSubmittedSearchToFifty() throws Exception {
        var result = new SearchRepository.SearchResult(UUID.randomUUID(), "Bài viết", "demo01", 0.8);
        when(embeddings.embedQuery("test")).thenReturn("[1]");
        when(blogs.search("[1]", 8)).thenReturn(List.of(result));
        when(blogs.search("[1]", 50)).thenReturn(List.of(result));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/v1/search").param("q", "test").param("limit", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(result.id().toString()));
        mvc.perform(get("/api/v1/search").param("q", "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(result.id().toString()));
    }

    @Test
    void missingApiKeyReturnsUnavailable() {
        var error = assertThrows(ResponseStatusException.class,
                () -> new SearchEmbeddingService("").embedQuery("test"));
        assertEquals(503, error.getStatusCode().value());
    }
}
