// Nhận câu tìm kiếm qua API, kiểm tra đầu vào, gọi Jina và trả danh sách bài gần nghĩa từ Neon.
package ctu.soulspace.controllers;

import ctu.soulspace.repositories.SearchRepository;
import ctu.soulspace.services.SearchEmbedding;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SearchController {
    private final SearchEmbedding embeddings;
    private final SearchRepository blogs;

    public SearchController(SearchEmbedding embeddings, SearchRepository blogs) {
        this.embeddings = embeddings;
        this.blogs = blogs;
    }

    @GetMapping("/api/v1/search")
    public List<SearchRepository.SearchResult> search(@RequestParam("q") String query) {
        String text = query.strip();
        if (text.isEmpty() || text.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Câu tìm kiếm cần có từ 1 đến 2000 ký tự.");
        }
        return blogs.search(embeddings.embedQuery(text));
    }
}
