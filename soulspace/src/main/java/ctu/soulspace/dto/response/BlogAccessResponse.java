package ctu.edu.blogAPI.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
public class BlogAccessResponse {
    private String blogId;

    private boolean published;

    private Instant updatedAt;
}
