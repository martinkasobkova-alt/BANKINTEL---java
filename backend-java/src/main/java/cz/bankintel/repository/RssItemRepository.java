package cz.bankintel.repository;

import cz.bankintel.domain.entity.RssItemEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RssItemRepository extends JpaRepository<RssItemEntity, String> {

    void deleteByFeedId(String feedId);

    java.util.Optional<RssItemEntity> findFirstByFeedIdAndLink(String feedId, String link);

    @Query(
            """
            SELECT i FROM RssItemEntity i
            WHERE i.feedId IN :feedIds
              AND (:category IS NULL OR :category = '' OR i.category = :category)
              AND (:cutoff IS NULL OR i.publishedAt >= :cutoff)
              AND (
                :search IS NULL OR :search = ''
                OR LOWER(i.title) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(i.summary) LIKE LOWER(CONCAT('%', :search, '%'))
              )
            ORDER BY i.publishedAt DESC NULLS LAST
            """)
    List<RssItemEntity> findFiltered(
            @Param("feedIds") List<String> feedIds,
            @Param("category") String category,
            @Param("cutoff") Instant cutoff,
            @Param("search") String search,
            Pageable pageable);
}
