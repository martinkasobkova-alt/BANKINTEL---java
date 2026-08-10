package cz.bankintel.repository;

import cz.bankintel.domain.entity.ComputedIndicatorEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComputedIndicatorRepository extends JpaRepository<ComputedIndicatorEntity, String> {

    List<ComputedIndicatorEntity> findAllByOrderByCreatedAtDesc();

    Optional<ComputedIndicatorEntity> findByName(String name);

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, String id);
}
