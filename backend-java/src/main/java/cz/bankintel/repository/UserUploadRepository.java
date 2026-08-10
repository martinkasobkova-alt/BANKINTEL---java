package cz.bankintel.repository;

import cz.bankintel.domain.entity.UserUploadEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserUploadRepository extends JpaRepository<UserUploadEntity, String> {

    List<UserUploadEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<UserUploadEntity> findByUserIdAndCompanyIdOrderByCreatedAtDesc(String userId, String companyId);

    Optional<UserUploadEntity> findByIdAndUserId(String id, String userId);
}
