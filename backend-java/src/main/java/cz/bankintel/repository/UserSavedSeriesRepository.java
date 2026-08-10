package cz.bankintel.repository;

import cz.bankintel.domain.entity.UserSavedSeriesEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSavedSeriesRepository extends JpaRepository<UserSavedSeriesEntity, String> {

    List<UserSavedSeriesEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    Optional<UserSavedSeriesEntity> findByIdAndUserId(String id, String userId);
}
