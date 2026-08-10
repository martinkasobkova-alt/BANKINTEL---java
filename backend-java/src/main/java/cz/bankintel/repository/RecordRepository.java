package cz.bankintel.repository;

import cz.bankintel.domain.entity.RecordEntity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RecordRepository extends JpaRepository<RecordEntity, String>, JpaSpecificationExecutor<RecordEntity> {

    Optional<RecordEntity> findByDatasetIdAndDedupeKey(String datasetId, String dedupeKey);

    long countByDatasetId(String datasetId);

    Page<RecordEntity> findByDatasetIdOrderByCreatedAtDesc(String datasetId, Pageable pageable);

    Page<RecordEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
