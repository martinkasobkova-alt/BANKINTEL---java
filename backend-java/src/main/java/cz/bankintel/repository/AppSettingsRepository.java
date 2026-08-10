package cz.bankintel.repository;

import cz.bankintel.domain.entity.AppSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingsRepository extends JpaRepository<AppSettingsEntity, String> {}
