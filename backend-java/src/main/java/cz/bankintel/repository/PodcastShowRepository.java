package cz.bankintel.repository;

import cz.bankintel.domain.entity.PodcastShowEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PodcastShowRepository extends JpaRepository<PodcastShowEntity, String> {

    @Query("SELECT s FROM PodcastShowEntity s ORDER BY s.sortOrder ASC, s.title ASC")
    List<PodcastShowEntity> findAllOrdered();
}
