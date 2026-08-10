package cz.bankintel.repository;

import cz.bankintel.domain.entity.MagazinePdfLinkEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MagazinePdfLinkRepository extends JpaRepository<MagazinePdfLinkEntity, String> {

    @Query(
            """
            SELECT l FROM MagazinePdfLinkEntity l
            WHERE l.issueId = :issueId
              AND (:page IS NULL OR l.page = :page)
            ORDER BY l.page ASC, l.createdAt ASC
            """)
    List<MagazinePdfLinkEntity> findByIssue(@Param("issueId") String issueId, @Param("page") Integer page);

    Optional<MagazinePdfLinkEntity> findByIdAndIssueId(String id, String issueId);
}
