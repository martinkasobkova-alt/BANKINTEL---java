package cz.bankintel.service.data;

import cz.bankintel.domain.entity.DatasetEntity;
import cz.bankintel.repository.DatasetRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class DatasetService {

    private final DatasetRepository datasetRepository;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listDatasets() {
        return datasetRepository.findAllByOrderByNameAsc().stream()
                .map(this::toPublic)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDataset(String id) {
        DatasetEntity dataset = datasetRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dataset not found"));
        return toPublic(dataset);
    }

    private Map<String, Object> toPublic(DatasetEntity dataset) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", dataset.getId());
        out.put("name", dataset.getName());
        out.put("source_id", dataset.getSourceId());
        out.put("source_name", dataset.getSourceName());
        out.put("fields", dataset.getFields() != null ? dataset.getFields() : List.of());
        out.put("record_count", dataset.getRecordCount());
        out.put("created_at", dataset.getCreatedAt() != null ? dataset.getCreatedAt().toString() : null);
        return out;
    }
}
