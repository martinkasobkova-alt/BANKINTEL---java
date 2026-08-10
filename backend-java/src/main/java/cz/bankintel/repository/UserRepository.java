package cz.bankintel.repository;

import cz.bankintel.domain.entity.UserEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<UserEntity, String> {

    List<UserEntity> findAllByOrderByCreatedAtDesc();

    List<UserEntity> findAllByRoleIgnoreCase(String role);

    @Query("SELECT u FROM UserEntity u WHERE LOWER(u.email) = LOWER(:email)")
    Optional<UserEntity> findByEmailIgnoreCase(@Param("email") String email);

    Optional<UserEntity> findByEmailVerificationTokenHash(String hash);

    Optional<UserEntity> findByPasswordResetTokenHash(String hash);

    @Query(
            """
            SELECT u FROM UserEntity u
            WHERE u.id <> :excludeUserId
            AND (
                LOWER(u.name) LIKE LOWER(CONCAT('%', :needle, '%'))
                OR LOWER(u.email) LIKE LOWER(CONCAT('%', :needle, '%'))
            )
            ORDER BY u.name ASC
            """)
    List<UserEntity> searchForChat(
            @Param("excludeUserId") String excludeUserId,
            @Param("needle") String needle,
            org.springframework.data.domain.Pageable pageable);
}
