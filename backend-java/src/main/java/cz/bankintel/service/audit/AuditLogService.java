package cz.bankintel.service.audit;

import cz.bankintel.domain.entity.AuditLogEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.AuditLogRepository;
import cz.bankintel.util.IdGenerator;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listLogs(int limit, String action, String actorUserId, String targetType) {
        Specification<AuditLogEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (action != null && !action.isBlank()) {
                predicates.add(cb.equal(root.get("action"), action.strip()));
            }
            if (actorUserId != null && !actorUserId.isBlank()) {
                predicates.add(cb.equal(root.get("actorUserId"), actorUserId.strip()));
            }
            if (targetType != null && !targetType.isBlank()) {
                predicates.add(cb.equal(root.get("targetType"), targetType.strip()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return auditLogRepository
                .findAll(spec, PageRequest.of(0, limit, org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "createdAt")))
                .stream()
                .map(this::toPublic)
                .toList();
    }

    @Transactional
    public void logEvent(
            String action,
            UserEntity actor,
            String targetType,
            String targetId,
            Map<String, Object> metadata,
            String requestIp,
            String userAgent) {
        try {
            AuditLogEntity entry = new AuditLogEntity();
            entry.setId(IdGenerator.newId());
            entry.setAction(action);
            if (actor != null) {
                entry.setActorUserId(actor.getId());
                entry.setActorEmail(actor.getEmail());
            }
            entry.setTargetType(targetType);
            entry.setTargetId(targetId != null ? targetId.substring(0, Math.min(targetId.length(), 200)) : "");
            entry.setMetadata(metadata != null ? metadata : Map.of());
            entry.setIp(requestIp != null ? requestIp.substring(0, Math.min(requestIp.length(), 100)) : null);
            entry.setUserAgent(
                    userAgent != null ? userAgent.substring(0, Math.min(userAgent.length(), 500)) : null);
            auditLogRepository.save(entry);
        } catch (Exception e) {
            log.error("Audit log write failed (action={}): {}", action, e.getMessage(), e);
        }
    }

    private Map<String, Object> toPublic(AuditLogEntity entry) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entry.getId());
        out.put("action", entry.getAction());
        out.put("actor_user_id", entry.getActorUserId());
        out.put("actor_email", entry.getActorEmail());
        out.put("target_type", entry.getTargetType());
        out.put("target_id", entry.getTargetId());
        out.put("metadata", entry.getMetadata() != null ? entry.getMetadata() : Map.of());
        out.put("ip", entry.getIp());
        out.put("user_agent", entry.getUserAgent());
        out.put("created_at", entry.getCreatedAt() != null ? entry.getCreatedAt().toString() : null);
        return out;
    }
}
