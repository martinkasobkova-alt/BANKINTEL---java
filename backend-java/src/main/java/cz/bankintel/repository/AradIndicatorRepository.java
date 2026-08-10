package cz.bankintel.repository;

import cz.bankintel.domain.entity.AradIndicatorEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AradIndicatorRepository extends JpaRepository<AradIndicatorEntity, String> {

    List<AradIndicatorEntity> findBySourceIdOrderByIndicatorIdAsc(String sourceId);

    void deleteBySourceId(String sourceId);

    long countBySourceId(String sourceId);
}
