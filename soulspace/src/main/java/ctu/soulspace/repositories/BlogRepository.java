package ctu.soulspace.repositories;

import ctu.soulspace.entities.Blog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlogRepository extends JpaRepository<Blog, UUID> {
    List<Blog> findByPublishedTrueOrderByCreatedAtDescIdDesc();

    List<Blog> findByAuthorIdOrderByCreatedAtDescIdDesc(UUID authorId);

    List<Blog> findByAuthorIdAndPublishedTrueOrderByCreatedAtDescIdDesc(UUID authorId);
}
