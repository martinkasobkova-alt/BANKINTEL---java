package cz.bankintel.repository;

import cz.bankintel.domain.entity.StoredFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredFileRepository extends JpaRepository<StoredFileEntity, String> {}
