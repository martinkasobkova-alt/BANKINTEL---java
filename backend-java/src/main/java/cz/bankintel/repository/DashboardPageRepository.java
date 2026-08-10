package cz.bankintel.repository;

import cz.bankintel.domain.entity.DashboardPageEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DashboardPageRepository extends JpaRepository<DashboardPageEntity, String> {

    List<DashboardPageEntity> findByUserIdOrderBySortOrderAsc(String userId);

    Optional<DashboardPageEntity> findByIdAndUserId(String id, String userId);

    Optional<DashboardPageEntity> findFirstByUserIdAndDefaultPageTrue(String userId);

    long countByUserId(String userId);

    List<DashboardPageEntity> findByUserIdAndIdNot(String userId, String id);

    List<DashboardPageEntity> findByAccessModeOrderByUpdatedAtDesc(String accessMode, Pageable pageable);

    @Query(
            """
            SELECT p FROM DashboardPageEntity p
            WHERE p.accessMode = 'public'
            AND LOWER(p.title) LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY p.updatedAt DESC
            """)
    List<DashboardPageEntity> findPublicByTitleContainingOrderByUpdatedAtDesc(
            @Param("q") String q, Pageable pageable);

    Optional<DashboardPageEntity> findByShareTokenAndShareEnabledTrue(String shareToken);

    Optional<DashboardPageEntity> findByShareToken(String shareToken);

    @Query(
            """
            SELECT p FROM DashboardPageEntity p
            WHERE p.userId <> :userId
            AND p.accessMode IN ('invite_only', 'public')
            ORDER BY p.updatedAt DESC
            """)
    List<DashboardPageEntity> findSharedCandidatePages(@Param("userId") String userId, Pageable pageable);
}
