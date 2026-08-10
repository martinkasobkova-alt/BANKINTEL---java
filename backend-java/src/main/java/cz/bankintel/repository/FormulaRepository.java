package cz.bankintel.repository;

import cz.bankintel.domain.entity.FormulaEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FormulaRepository extends JpaRepository<FormulaEntity, String> {

    List<FormulaEntity> findAllByOrderByCreatedAtDesc();

    Optional<FormulaEntity> findByName(String name);

    boolean existsByName(String name);
}
