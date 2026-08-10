package cz.bankintel.repository;

import cz.bankintel.domain.entity.DatasetEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DatasetRepository extends JpaRepository<DatasetEntity, String> {

    List<DatasetEntity> findAllByOrderByNameAsc();

    Optional<DatasetEntity> findByName(String name);
}
