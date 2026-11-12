package ctu.edu.blogAPI.entities;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

@Document(collection = "user")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class User {
    @Id

    String id; // dùng String để MongoDB tự sinh ObjectId
    String username;
    String password;
    String fullname;
    LocalDate dob;
    String userAvatarUrl;

    @Builder.Default
    List<SharedBlog> sharedBlogs = new ArrayList<>();

}
