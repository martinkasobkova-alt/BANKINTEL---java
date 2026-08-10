package cz.bankintel.repository;

import cz.bankintel.domain.entity.SectionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SectionRepository extends JpaRepository<SectionEntity, String> {

    List<SectionEntity> findAllByOrderBySortOrderAsc();

    Optional<SectionEntity> findBySlug(String slug);
}
