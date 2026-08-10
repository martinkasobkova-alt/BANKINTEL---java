package cz.bankintel.repository;

import cz.bankintel.domain.entity.MagazineTextChunkEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MagazineTextChunkRepository extends JpaRepository<MagazineTextChunkEntity, String> {

    @Modifying
    @Query("DELETE FROM MagazineTextChunkEntity c WHERE c.issueId = :issueId")
    void deleteByIssueId(@Param("issueId") String issueId);

    @Query(
            """
            SELECT c FROM MagazineTextChunkEntity c
            WHERE (:magazineId IS NULL OR c.magazineId = :magazineId)
              AND (:issueId IS NULL OR c.issueId = :issueId)
            ORDER BY c.issueId ASC, c.page ASC, c.chunkOrder ASC
            """)
    List<MagazineTextChunkEntity> findForScope(
            @Param("magazineId") String magazineId, @Param("issueId") String issueId);

    @Query(
            value =
                    """
                    SELECT * FROM magazine_text_chunks c
                    WHERE (:magazineId IS NULL OR c.magazine_id = :magazineId)
                      AND (:issueId IS NULL OR c.issue_id = :issueId)
                      AND LOWER(c.text) LIKE LOWER(CONCAT('%', :term, '%'))
                    LIMIT :limit
                    """,
            nativeQuery = true)
    List<MagazineTextChunkEntity> searchByTerm(
            @Param("magazineId") String magazineId,
            @Param("issueId") String issueId,
            @Param("term") String term,
            @Param("limit") int limit);
}
