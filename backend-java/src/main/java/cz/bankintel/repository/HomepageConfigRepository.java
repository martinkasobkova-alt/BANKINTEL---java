package cz.bankintel.repository;

import cz.bankintel.domain.entity.HomepageConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HomepageConfigRepository extends JpaRepository<HomepageConfigEntity, String> {}
