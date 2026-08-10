package cz.bankintel.service.myseries;

import cz.bankintel.domain.dto.MySeriesDtos.MySavedSeriesCreateRequest;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.domain.entity.UserSavedSeriesEntity;
import cz.bankintel.repository.UserSavedSeriesRepository;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.util.IdGenerator;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MySavedSeriesService {

    private final UserSavedSeriesRepository repository;
    private final SavedSeriesResolverService resolverService;
    private final FeatureAccessService featureAccessService;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UserEntity user) {
        requireAccess(user);
        return repository.findByUserIdOrderByUpdatedAtDesc(user.getId()).stream()
                .map(this::toListItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(UserEntity user, String seriesId) {
        requireAccess(user);
        UserSavedSeriesEntity doc = requireSeries(user.getId(), seriesId);
        return toDetail(doc, true);
    }

    @Transactional
    public Map<String, Object> create(UserEntity user, MySavedSeriesCreateRequest body) {
        requireAccess(user);
        Map<String, Object> payload = body.resolverPayload() != null ? body.resolverPayload() : Map.of();
        SavedSeriesResolverService.ResolvedPoints resolved =
                resolverService.resolvePoints(user.getId(), payload);

        Instant now = Instant.now();
        String kind = String.valueOf(payload.getOrDefault("kind", "source_indicator"))
                .strip()
                .toLowerCase(Locale.ROOT);
        String srcSeries = body.sourceSeriesId() != null ? body.sourceSeriesId().strip() : "";
        if (srcSeries.isBlank() && !"user_upload".equals(kind)) {
            srcSeries = String.valueOf(payload.getOrDefault("indicator_id", "")).strip();
        }

        UserSavedSeriesEntity entity = new UserSavedSeriesEntity();
        entity.setId(IdGenerator.newId());
        entity.setUserId(user.getId());
        entity.setTitle(body.title().strip());
        entity.setSource(body.source() != null ? body.source().strip() : "");
        entity.setSourceType(firstNonBlank(body.sourceType(), String.valueOf(resolved.meta().get("source_type"))));
        entity.setSourceSeriesId(srcSeries);
        entity.setSourceDatasetId(body.sourceDatasetId() != null ? body.sourceDatasetId().strip() : "");
        entity.setResolverPayload(payload);
        entity.setUnit(firstNonBlank(body.unit(), String.valueOf(resolved.meta().get("unit"))));
        entity.setFrequency(firstNonBlank(body.frequency(), String.valueOf(resolved.meta().get("frequency"))));
        entity.setArea(firstNonBlank(body.area(), String.valueOf(resolved.meta().get("area"))));
        entity.setCategory(firstNonBlank(body.category(), String.valueOf(resolved.meta().get("category"))));
        entity.setStartPeriod(String.valueOf(resolved.meta().getOrDefault("start_period", "")));
        entity.setEndPeriod(String.valueOf(resolved.meta().getOrDefault("end_period", "")));
        entity.setLastPeriod(String.valueOf(resolved.meta().getOrDefault("last_period", "")));
        Object lastValue = resolved.meta().get("last_value");
        entity.setLastValue(lastValue instanceof Number n ? n.doubleValue() : null);
        entity.setDataPoints(resolved.points());
        entity.setPointCount(resolved.points().size());
        entity.setMetadata(body.metadata() != null ? body.metadata() : Map.of());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        repository.save(entity);
        return toDetail(entity, true);
    }

    @Transactional
    public Map<String, Object> delete(UserEntity user, String seriesId) {
        requireAccess(user);
        UserSavedSeriesEntity doc = requireSeries(user.getId(), seriesId);
        repository.delete(doc);
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> refresh(UserEntity user, String seriesId) {
        requireAccess(user);
        UserSavedSeriesEntity doc = requireSeries(user.getId(), seriesId);
        SavedSeriesResolverService.ResolvedPoints resolved =
                resolverService.resolvePoints(user.getId(), doc.getResolverPayload());
        doc.setDataPoints(resolved.points());
        doc.setPointCount(resolved.points().size());
        doc.setUnit(firstNonBlank(String.valueOf(resolved.meta().get("unit")), doc.getUnit()));
        doc.setFrequency(firstNonBlank(String.valueOf(resolved.meta().get("frequency")), doc.getFrequency()));
        doc.setArea(firstNonBlank(String.valueOf(resolved.meta().get("area")), doc.getArea()));
        doc.setCategory(firstNonBlank(String.valueOf(resolved.meta().get("category")), doc.getCategory()));
        doc.setStartPeriod(String.valueOf(resolved.meta().getOrDefault("start_period", "")));
        doc.setEndPeriod(String.valueOf(resolved.meta().getOrDefault("end_period", "")));
        doc.setLastPeriod(String.valueOf(resolved.meta().getOrDefault("last_period", "")));
        Object lastValue = resolved.meta().get("last_value");
        doc.setLastValue(lastValue instanceof Number n ? n.doubleValue() : null);
        doc.setUpdatedAt(Instant.now());
        repository.save(doc);
        return toDetail(doc, true);
    }

    private void requireAccess(UserEntity user) {
        featureAccessService.requireFeature(user, "saved_calculations");
        if (!FeatureAccessService.isSubscriber(user)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Tato funkce je dostupná pro předplatitele časopisu Bankovnictví.");
        }
    }

    private UserSavedSeriesEntity requireSeries(String userId, String seriesId) {
        return repository
                .findByIdAndUserId(seriesId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Řada nenalezena."));
    }

    private Map<String, Object> toListItem(UserSavedSeriesEntity doc) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", doc.getId());
        out.put("title", doc.getTitle());
        out.put("source", doc.getSource());
        out.put("source_type", doc.getSourceType());
        out.put("source_series_id", doc.getSourceSeriesId());
        out.put("source_dataset_id", doc.getSourceDatasetId());
        out.put("unit", doc.getUnit());
        out.put("frequency", doc.getFrequency());
        out.put("area", doc.getArea());
        out.put("category", doc.getCategory());
        out.put("last_period", doc.getLastPeriod());
        out.put("last_value", doc.getLastValue());
        out.put("point_count", doc.getPointCount());
        out.put("created_at", doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : null);
        out.put("updated_at", doc.getUpdatedAt() != null ? doc.getUpdatedAt().toString() : null);
        return out;
    }

    private Map<String, Object> toDetail(UserSavedSeriesEntity doc, boolean includePoints) {
        Map<String, Object> out = toListItem(doc);
        out.put("resolver_payload", doc.getResolverPayload() != null ? doc.getResolverPayload() : Map.of());
        out.put("metadata", doc.getMetadata() != null ? doc.getMetadata() : Map.of());
        out.put("start_period", doc.getStartPeriod());
        out.put("end_period", doc.getEndPeriod());
        if (includePoints) {
            out.put("data_points", doc.getDataPoints() != null ? doc.getDataPoints() : List.of());
        }
        return out;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }
}
