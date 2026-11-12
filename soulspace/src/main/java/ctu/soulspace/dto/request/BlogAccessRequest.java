package ctu.edu.blogAPI.dto.request;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class BlogAccessRequest {
    private String blogId;

    private boolean published;
}
