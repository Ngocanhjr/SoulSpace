// Nhận câu tìm kiếm qua API, kiểm tra đầu vào, gọi Jina và trả danh sách bài gần nghĩa từ Neon.
package ctu.soulspace.controller;

import ctu.soulspace.repositories.SearchRepository;
import ctu.soulspace.service.SearchEmbeddingService;
import ctu.soulspace.constants.ErrorCode;
import ctu.soulspace.exception.AppException;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SearchController {
    private final SearchEmbeddingService embeddings;
    private final SearchRepository blogs;

    public SearchController(SearchEmbeddingService embeddings, SearchRepository blogs) {
        this.embeddings = embeddings;
        this.blogs = blogs;
    }

    @GetMapping("/api/v1/search")
    public List<SearchRepository.SearchResult> search(
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 50) {
            throw new AppException(ErrorCode.VALIDATION_FAILED,
                    Map.of("limit", "Use 1 to 50 results"));
        }
        String text = query.strip();
        if (text.isEmpty() || text.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Câu tìm kiếm cần có từ 1 đến 2000 ký tự.");
        }
        return blogs.search(embeddings.embedQuery(text), limit);
    }
}
