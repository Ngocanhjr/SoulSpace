package ctu.edu.blogAPI.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlogCreateResponse {
    private String blogId; //Response trả về sẽ là một chuỗi

    private List<String> successUrls;
    private List<String> failedFiles;
}
