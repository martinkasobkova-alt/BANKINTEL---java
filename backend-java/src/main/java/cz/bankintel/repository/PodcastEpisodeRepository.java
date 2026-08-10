package cz.bankintel.repository;

import cz.bankintel.domain.entity.PodcastEpisodeEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PodcastEpisodeRepository extends JpaRepository<PodcastEpisodeEntity, String> {

    @Query(
            """
            SELECT COUNT(e) FROM PodcastEpisodeEntity e
            WHERE e.showId = :showId AND e.published = true
            """)
    long countPublishedByShowId(@Param("showId") String showId);

    @Query(
            """
            SELECT e FROM PodcastEpisodeEntity e
            WHERE e.published = true
              AND (:showId IS NULL OR e.showId = :showId)
            ORDER BY e.publishedAt DESC NULLS LAST
            """)
    List<PodcastEpisodeEntity> findPublished(@Param("showId") String showId, Pageable pageable);

    List<PodcastEpisodeEntity> findByShowId(String showId);
}
