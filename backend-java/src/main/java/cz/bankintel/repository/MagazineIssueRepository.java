package cz.bankintel.repository;

import cz.bankintel.domain.entity.MagazineIssueEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MagazineIssueRepository extends JpaRepository<MagazineIssueEntity, String> {

    @Query(
            """
            SELECT i FROM MagazineIssueEntity i
            WHERE i.magazineId = :magazineId
            ORDER BY i.publishedAt DESC, i.createdAt DESC
            """)
    List<MagazineIssueEntity> findByMagazineIdOrdered(@Param("magazineId") String magazineId);

    long countByMagazineId(String magazineId);

    @Query("SELECT COUNT(i) FROM MagazineIssueEntity i WHERE i.magazineId = :magazineId AND i.ingestStatus = 'ready'")
    long countReadyByMagazineId(@Param("magazineId") String magazineId);
}
