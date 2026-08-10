package cz.bankintel.repository;

import cz.bankintel.domain.entity.SectionWidgetEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SectionWidgetRepository extends JpaRepository<SectionWidgetEntity, String> {

    List<SectionWidgetEntity> findBySectionIdOrderBySortOrderAsc(String sectionId);

    void deleteBySectionId(String sectionId);
}
