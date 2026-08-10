package cz.bankintel.repository;

import cz.bankintel.domain.entity.BugReportEntity;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BugReportRepository extends JpaRepository<BugReportEntity, String> {

    List<BugReportEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<BugReportEntity> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
}
