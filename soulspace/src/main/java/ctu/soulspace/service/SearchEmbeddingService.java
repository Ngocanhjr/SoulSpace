// Gọi Jina v3 để tạo vector 1024 chiều cho câu tìm kiếm hoặc nội dung bài viết.
package ctu.soulspace.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SearchEmbeddingService {
    private final RestClient client;
    private final String apiKey;

    public SearchEmbeddingService(@Value("${JINA_API_KEY:}") String apiKey) {
        this.apiKey = apiKey.trim();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        client = RestClient.builder().requestFactory(factory)
                .baseUrl("https://api.jina.ai").build();
    }

    // Câu tìm kiếm dùng adapter retrieval.query để so với vector bài viết.
    public String embedQuery(String query) {
        return embed(query, "retrieval.query");
    }

    // Worker dùng adapter giống script Python đã nạp vector ban đầu.
    public String embedPost(String content) {
        return embed(content, "retrieval.passage");
    }

    // Gọi Jina ngoài transaction database và kiểm tra vector trước khi dùng.
    private String embed(String text, String task) {
        if (apiKey.isBlank()) {
            throw unavailable();
        }
        try {
            var response = client.post().uri("/v1/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(Map.of("model", "jina-embeddings-v3", "task", task,
                            "dimensions", 1024, "input", List.of(text)))
                    .retrieve().body(EmbeddingResponse.class);
            if (response == null || response.data() == null || response.data().size() != 1) {
                throw unavailable();
            }
            var item = response.data().getFirst();
            if (item == null || item.index() == null || item.index() != 0
                    || item.embedding() == null || item.embedding().length != 1024) {
                throw unavailable();
            }
            double[] vector = item.embedding();
            double norm = 0;
            for (double value : vector) {
                if (!Double.isFinite(value)) {
                    throw unavailable();
                }
                norm += value * value;
            }
            if (!Double.isFinite(norm) || norm == 0) {
                throw unavailable();
            }
            return Arrays.toString(vector);
        } catch (RestClientException exception) {
            // Jina error bodies can contain request data; return a generic message.
            throw unavailable();
        }
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Dịch vụ embedding tạm thời không khả dụng.");
    }

    public record EmbeddingResponse(List<EmbeddingItem> data) {}

    public record EmbeddingItem(Integer index, double[] embedding) {}
}
