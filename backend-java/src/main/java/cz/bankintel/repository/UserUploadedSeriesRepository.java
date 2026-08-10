package cz.bankintel.repository;

import cz.bankintel.domain.entity.UserUploadedSeriesEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserUploadedSeriesRepository extends JpaRepository<UserUploadedSeriesEntity, String> {

    List<UserUploadedSeriesEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    List<UserUploadedSeriesEntity> findByUserIdAndCompanyIdOrderByCreatedAtDesc(String userId, String companyId);

    List<UserUploadedSeriesEntity> findByUserIdAndUploadIdInOrderByCreatedAtDesc(String userId, List<String> uploadIds);

    List<UserUploadedSeriesEntity> findByUserIdAndUploadIdOrderByCreatedAtDesc(String userId, String uploadId);

    Optional<UserUploadedSeriesEntity> findByIdAndUserId(String id, String userId);

    void deleteByUserIdAndUploadId(String userId, String uploadId);
}
