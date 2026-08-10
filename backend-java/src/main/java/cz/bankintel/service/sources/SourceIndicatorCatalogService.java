package cz.bankintel.service.sources;

import cz.bankintel.domain.entity.DatasetEntity;
import cz.bankintel.domain.entity.SourceEntity;
import cz.bankintel.repository.DatasetRepository;
import cz.bankintel.repository.RecordRepository;
import cz.bankintel.repository.SourceRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class SourceIndicatorCatalogService {

    private final SourceRepository sourceRepository;
    private final DatasetRepository datasetRepository;
    private final RecordRepository recordRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> indicatorCatalog(String sourceId) {
        SourceEntity source = sourceRepository.findById(sourceId).orElse(null);
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Zdroj nenalezen.");
        }

        String datasetName = source.getDatasetName() != null && !source.getDatasetName().isBlank()
                ? source.getDatasetName()
                : source.getName();
        DatasetEntity dataset = datasetRepository.findByName(datasetName).orElse(null);

        Map<String, Object> sourceMeta = Map.of(
                "id", source.getId(),
                "name", source.getName(),
                "source_type", source.getSourceType());

        if (dataset == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("message", "Pro tento zdroj zatím nejsou uložená data.");
            out.put("group_field", null);
            out.put("indicators", List.of());
            out.put("source", sourceMeta);
            out.put("peek_fields", List.of());
            return out;
        }

        List<Map<String, Object>> sampleRows = recordRepository
                .findByDatasetIdOrderByCreatedAtDesc(dataset.getId(), PageRequest.of(0, 500))
                .stream()
                .map(r -> r.getData() != null ? r.getData() : Map.<String, Object>of())
                .toList();
        String groupField = SourceRecordGroupHelper.detectGroupField(sampleRows);
        List<Map<String, Object>> indicators = SourceRecordGroupHelper.buildIndicators(groupField, sampleRows);
        List<String> peekFields = sampleRows.isEmpty() ? List.of() : new ArrayList<>(sampleRows.getFirst().keySet());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", indicators.isEmpty() ? "Pro tento zdroj zatím nejsou uložená data." : "");
        out.put("group_field", groupField);
        out.put("indicators", indicators);
        out.put("source", sourceMeta);
        out.put("peek_fields", peekFields);
        return out;
    }
}
