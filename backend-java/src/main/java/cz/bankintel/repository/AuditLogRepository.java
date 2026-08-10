package cz.bankintel.repository;

import cz.bankintel.domain.entity.AuditLogEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditLogRepository
        extends JpaRepository<AuditLogEntity, String>, JpaSpecificationExecutor<AuditLogEntity> {

    List<AuditLogEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
