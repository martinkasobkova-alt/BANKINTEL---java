package cz.bankintel.service.data;

import cz.bankintel.domain.entity.DatasetEntity;
import cz.bankintel.domain.entity.RecordEntity;
import cz.bankintel.repository.DatasetRepository;
import cz.bankintel.repository.RecordRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RecordService {

    private final RecordRepository recordRepository;
    private final DatasetRepository datasetRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> listRecords(String datasetId, String datasetName, String query, int limit, int skip) {
        String resolvedDatasetId = datasetId;
        if ((resolvedDatasetId == null || resolvedDatasetId.isBlank()) && datasetName != null && !datasetName.isBlank()) {
            resolvedDatasetId = datasetRepository.findByName(datasetName).map(DatasetEntity::getId).orElse(null);
        }

        String finalDatasetId = resolvedDatasetId;
        Specification<RecordEntity> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (finalDatasetId != null && !finalDatasetId.isBlank()) {
                predicates.add(cb.equal(root.get("datasetId"), finalDatasetId));
            }
            if (query != null && !query.isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("dedupeKey")), "%" + query.toLowerCase() + "%"));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        long total = recordRepository.count(spec);
        int page = skip / Math.max(limit, 1);
        Page<RecordEntity> rows =
                recordRepository.findAll(spec, PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt")));

        List<Map<String, Object>> rowMaps = rows.getContent().stream().map(this::toPublic).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("rows", rowMaps);
        return out;
    }

    private Map<String, Object> toPublic(RecordEntity record) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", record.getId());
        out.put("dataset_id", record.getDatasetId());
        if (record.getSourceId() != null) {
            out.put("source_id", record.getSourceId());
        }
        if (record.getDedupeKey() != null) {
            out.put("dedupe_key", record.getDedupeKey());
        }
        out.put("data", record.getData() != null ? record.getData() : Map.of());
        out.put("created_at", record.getCreatedAt() != null ? record.getCreatedAt().toString() : null);
        return out;
    }
}
