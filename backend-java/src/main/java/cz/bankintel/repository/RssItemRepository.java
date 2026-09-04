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

    /**
     * Prázdný řetězec a {@link java.time.Instant#EPOCH} zastupují „bez filtru" — volající je posílá
     * místo null. Podmínky typu {@code :param IS NULL} totiž na PostgreSQL shodí celý dotaz,
     * protože databáze neumí odvodit typ nenaplněného parametru.
     */
    @Query(
            """
            SELECT i FROM RssItemEntity i
            WHERE i.feedId IN :feedIds
              AND (:category = '' OR i.category = :category)
              AND (i.publishedAt IS NULL OR i.publishedAt >= :cutoff)
              AND (
                :search = ''
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
