package ctu.soulspace.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ctu.soulspace.entities.User;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    @Query("""
            select u from User u
            where locate(lower(:query), lower(u.username)) > 0
               or locate(lower(:query), lower(u.fullname)) > 0
            order by u.username, u.id
            """)
    List<User> searchByName(@Param("query") String query, Pageable pageable);
}
