package cz.bankintel.repository;

import cz.bankintel.domain.entity.ArticleCategoryEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ArticleCategoryRepository extends JpaRepository<ArticleCategoryEntity, String> {

    Optional<ArticleCategoryEntity> findBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, String id);

    @Query("SELECT c FROM ArticleCategoryEntity c ORDER BY c.sortOrder ASC, c.name ASC")
    List<ArticleCategoryEntity> findAllOrdered();

    @Query("SELECT COALESCE(MAX(c.sortOrder), 0) FROM ArticleCategoryEntity c")
    int maxSortOrder();
}
