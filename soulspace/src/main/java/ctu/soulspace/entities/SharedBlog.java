package ctu.soulspace.entities;

import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class SharedBlog {
    private String blogId;

    private String originalUserId;

    private UserInfo author;

    private String content;

    @Builder.Default
    private List<String> imageContentUrls = new ArrayList<>();

    private boolean published;

    private Instant originalCreatedAt;

    private Instant sharedAt;
}
