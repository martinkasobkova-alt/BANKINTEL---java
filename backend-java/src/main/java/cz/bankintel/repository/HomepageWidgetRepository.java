package cz.bankintel.repository;

import cz.bankintel.domain.entity.HomepageWidgetEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HomepageWidgetRepository extends JpaRepository<HomepageWidgetEntity, String> {

    List<HomepageWidgetEntity> findByConfigIdOrderBySortOrderAsc(String configId);

    void deleteByConfigId(String configId);
}
