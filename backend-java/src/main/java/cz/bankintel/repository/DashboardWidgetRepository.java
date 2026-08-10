package cz.bankintel.repository;

import cz.bankintel.domain.entity.DashboardWidgetEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DashboardWidgetRepository extends JpaRepository<DashboardWidgetEntity, String> {

    List<DashboardWidgetEntity> findByUserIdAndPageIdOrderBySortOrderAsc(String userId, String pageId);

    List<DashboardWidgetEntity> findByUserIdAndWidgetTypeInOrderByUpdatedAtDesc(
            String userId, java.util.Collection<String> widgetTypes);

    java.util.Optional<DashboardWidgetEntity> findByIdAndUserId(String id, String userId);

    java.util.Optional<DashboardWidgetEntity> findByIdAndUserIdAndPageId(String id, String userId, String pageId);

    long countByUserIdAndPageId(String userId, String pageId);

    void deleteByUserIdAndPageId(String userId, String pageId);
}
