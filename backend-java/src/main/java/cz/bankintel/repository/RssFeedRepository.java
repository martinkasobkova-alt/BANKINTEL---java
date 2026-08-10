package cz.bankintel.repository;

import cz.bankintel.domain.entity.RssFeedEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RssFeedRepository extends JpaRepository<RssFeedEntity, String> {

    @Query("SELECT f FROM RssFeedEntity f ORDER BY f.createdAt DESC")
    List<RssFeedEntity> findAllForAdmin();

    @Query(
            """
            SELECT f FROM RssFeedEntity f
            WHERE (f.scope = 'global' AND f.enabled = true)
               OR (f.scope = 'user' AND f.ownerUserId = :userId)
            ORDER BY f.createdAt DESC
            """)
    List<RssFeedEntity> findVisibleForUser(@Param("userId") String userId);

    @Query(
            """
            SELECT f.id FROM RssFeedEntity f
            WHERE (f.scope = 'global' AND f.enabled = true)
               OR (f.scope = 'user' AND f.ownerUserId = :userId AND f.enabled = true)
            """)
    List<String> findEnabledReadableIds(@Param("userId") String userId);

    @Query("SELECT f FROM RssFeedEntity f WHERE f.enabled = true ORDER BY f.createdAt ASC")
    List<RssFeedEntity> findAllEnabled();
}
