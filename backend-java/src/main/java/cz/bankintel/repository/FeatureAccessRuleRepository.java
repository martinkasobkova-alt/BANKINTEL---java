package cz.bankintel.repository;

import cz.bankintel.domain.entity.FeatureAccessRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureAccessRuleRepository extends JpaRepository<FeatureAccessRuleEntity, String> {}
