package cz.bankintel.repository;

import cz.bankintel.domain.entity.MagazineEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MagazineRepository extends JpaRepository<MagazineEntity, String> {

    Optional<MagazineEntity> findBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, String id);

    @Query("SELECT m FROM MagazineEntity m ORDER BY m.title ASC")
    List<MagazineEntity> findAllOrdered();
}
