package cz.bankintel.repository;

import cz.bankintel.domain.entity.SyncLogEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncLogRepository extends JpaRepository<SyncLogEntity, String> {

    List<SyncLogEntity> findAllByOrderByStartedAtDesc(Pageable pageable);

    List<SyncLogEntity> findBySourceIdOrderByStartedAtDesc(String sourceId, Pageable pageable);

    List<SyncLogEntity> findBySourceIdAndStatus(String sourceId, String status);

    Optional<SyncLogEntity> findFirstByOrderByStartedAtDesc();
}
