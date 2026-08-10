package cz.bankintel.repository;

import cz.bankintel.domain.entity.ArticleEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArticleRepository extends JpaRepository<ArticleEntity, String> {

    Optional<ArticleEntity> findBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, String id);

    @Query(
            """
            SELECT a FROM ArticleEntity a
            WHERE (:publishedOnly = false OR a.published = true)
              AND (:categoryId IS NULL OR a.categoryId = :categoryId)
              AND (
                :search IS NULL OR :search = ''
                OR LOWER(a.title) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(a.summary) LIKE LOWER(CONCAT('%', :search, '%'))
              )
            ORDER BY a.publishedAt DESC NULLS LAST, a.createdAt DESC
            """)
    List<ArticleEntity> findFiltered(
            @Param("publishedOnly") boolean publishedOnly,
            @Param("categoryId") String categoryId,
            @Param("search") String search,
            Pageable pageable);
}
