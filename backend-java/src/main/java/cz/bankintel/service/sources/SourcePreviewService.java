package cz.bankintel.service.sources;

import cz.bankintel.connector.BaseConnector;
import cz.bankintel.connector.ConnectorFactory;
import cz.bankintel.connector.ConnectorFetchResult;
import cz.bankintel.domain.entity.DatasetEntity;
import cz.bankintel.domain.entity.RecordEntity;
import cz.bankintel.domain.entity.SourceEntity;
import cz.bankintel.repository.DatasetRepository;
import cz.bankintel.repository.RecordRepository;
import cz.bankintel.repository.SourceRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class SourcePreviewService {

    private static final Logger log = LoggerFactory.getLogger(SourcePreviewService.class);
    private static final int ARAD_AUTO_MULTI_INDICATOR_LIMIT = 12;
    private static final int EXPLICIT_INDICATOR_CAP = 40;
    private static final Set<String> LIVE_CONNECTOR_TYPES = Set.of("arad", "ecb", "ecb2", "eurostat");

    private final SourceRepository sourceRepository;
    private final DatasetRepository datasetRepository;
    private final RecordRepository recordRepository;
    private final ConnectorFactory connectorFactory;

    @Transactional(readOnly = true)
    public Map<String, Object> preview(
            String sourceId, int limit, String indicatorId, String indicatorIds, String dimensionFilters) {
        SourceEntity source = sourceRepository
                .findById(sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found"));

        String datasetName = source.getDatasetName() != null && !source.getDatasetName().isBlank()
                ? source.getDatasetName()
                : source.getName();
        DatasetEntity dataset = datasetRepository.findByName(datasetName).orElse(null);

        SourcePreviewStateHelper.MapPreviewSource previewSource = SourcePreviewStateHelper.toPreviewSource(source);
        Map<String, Object> sourceOut = previewSourceMap(previewSource);

        int capped = Math.max(1, Math.min(limit, 500));

        if (dataset == null) {
            if (supportsLiveConnectorPreview(source.getSourceType())) {
                Map<String, Object> live = tryLiveConnectorPreview(source, capped, indicatorId, indicatorIds, dimensionFilters);
                if (live != null) {
                    return live;
                }
            }
            String state = SourcePreviewStateHelper.previewState(source, false, 0);
            return emptyPreview(sourceOut, source.getName(), state, SourcePreviewStateHelper.previewMessage(source, state));
        }

        int fetchCap = capped;
        if ("eurostat".equalsIgnoreCase(source.getSourceType())) {
            fetchCap = Math.min(5000, Math.max(capped, capped * 12));
        }

        List<Map<String, Object>> sampleRows = loadSampleRows(dataset.getId(), 500);
        String groupField = SourceRecordGroupHelper.detectGroupField(sampleRows);
        List<Map<String, Object>> indicators = SourceRecordGroupHelper.buildIndicators(groupField, sampleRows);

        List<String> explicitIds =
                SourceRecordGroupHelper.parseExplicitIndicatorIds(indicatorId, indicatorIds, EXPLICIT_INDICATOR_CAP);
        String selectedSingle = explicitIds.size() == 1 ? explicitIds.getFirst() : null;
        List<String> selectedMulti = explicitIds.size() > 1 ? explicitIds : List.of();

        if (groupField != null
                && selectedSingle == null
                && selectedMulti.isEmpty()
                && !indicators.isEmpty()
                && "arad".equalsIgnoreCase(source.getSourceType())
                && indicators.size() > ARAD_AUTO_MULTI_INDICATOR_LIMIT) {
            selectedSingle = String.valueOf(indicators.getFirst().get("id"));
        }

        Map<String, Object> dimFilters = SourceRecordGroupHelper.parseDimensionFilters(dimensionFilters);
        List<Map<String, Object>> fetched = loadFilteredRows(
                dataset.getId(), fetchCap, groupField, selectedSingle, selectedMulti, dimFilters);
        List<Map<String, Object>> rows = SourceRecordGroupHelper.sampleEven(fetched, capped);
        List<String> fields = SourceRecordGroupHelper.collectFields(rows);
        String state = SourcePreviewStateHelper.previewState(source, true, rows.size());

        List<String> selectedIndicators = new ArrayList<>();
        if (!selectedMulti.isEmpty()) {
            selectedIndicators.addAll(selectedMulti);
        } else if (groupField != null && selectedSingle == null) {
            indicators.forEach(i -> selectedIndicators.add(String.valueOf(i.get("id"))));
        } else if (selectedSingle != null) {
            selectedIndicators.add(selectedSingle);
        }

        String selectedIndicatorOut =
                selectedSingle != null ? selectedSingle : (selectedIndicators.isEmpty() ? null : selectedIndicators.getFirst());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("row_count", rows.size());
        metadata.put("filters_applied", dimFilters);
        metadata.put("dimensions", Map.of());
        metadata.put("warning", null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", sourceOut);
        out.put("dataset", dataset.getName());
        out.put("dataset_id", dataset.getId());
        out.put("title", source.getName());
        out.put("rows", rows);
        out.put("fields", fields);
        out.put("columns", SourceRecordGroupHelper.columnsFromFields(fields));
        out.put("metadata", metadata);
        out.put("indicators", indicators);
        out.put("group_field", groupField);
        out.put("selected_indicator", selectedIndicatorOut);
        out.put("selected_indicators", selectedIndicators);
        out.put("preview_state", state);
        out.put("sync_state", state);
        out.put("message", SourcePreviewStateHelper.previewMessage(source, state));
        return out;
    }

    private List<Map<String, Object>> loadSampleRows(String datasetId, int cap) {
        return recordRepository.findByDatasetIdOrderByCreatedAtDesc(datasetId, PageRequest.of(0, cap)).stream()
                .map(this::rowData)
                .toList();
    }

    private List<Map<String, Object>> loadFilteredRows(
            String datasetId,
            int fetchCap,
            String groupField,
            String selectedSingle,
            List<String> selectedMulti,
            Map<String, Object> dimFilters) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RecordEntity record :
                recordRepository.findByDatasetIdOrderByCreatedAtDesc(datasetId, PageRequest.of(0, fetchCap))) {
            Map<String, Object> data = rowData(record);
            if (SourceRecordGroupHelper.rowMatchesFilters(data, dimFilters, groupField, selectedMulti, selectedSingle)) {
                rows.add(data);
            }
        }
        java.util.Collections.reverse(rows);
        return rows;
    }

    private Map<String, Object> rowData(RecordEntity record) {
        return record.getData() != null ? record.getData() : Map.of();
    }

    private static Map<String, Object> previewSourceMap(SourcePreviewStateHelper.MapPreviewSource source) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", source.id());
        out.put("name", source.name());
        out.put("source_type", source.sourceType());
        if (source.datasetName() != null && !source.datasetName().isBlank()) {
            out.put("dataset_name", source.datasetName());
        }
        out.put("last_sync_message", source.lastSyncMessage());
        return out;
    }

    private static Map<String, Object> emptyPreview(
            Map<String, Object> sourceOut, String title, String state, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", sourceOut);
        out.put("dataset", null);
        out.put("dataset_id", "");
        out.put("title", title != null ? title : "");
        out.put("rows", List.of());
        out.put("fields", List.of());
        out.put("columns", List.of());
        // Map.of() nepovoluje null hodnoty ("warning") — LinkedHashMap ano.
        Map<String, Object> emptyMetadata = new LinkedHashMap<>();
        emptyMetadata.put("row_count", 0);
        emptyMetadata.put("filters_applied", Map.of());
        emptyMetadata.put("dimensions", Map.of());
        emptyMetadata.put("warning", null);
        out.put("metadata", emptyMetadata);
        out.put("indicators", List.of());
        out.put("group_field", null);
        out.put("preview_state", state);
        out.put("sync_state", state);
        out.put("message", message);
        return out;
    }

    /**
     * Live connector fetch when synced dataset missing — minimal port {@code catalog_preview_routes.py}
     * for arad/ecb/eurostat.
     */
    private Map<String, Object> tryLiveConnectorPreview(
            SourceEntity source,
            int limit,
            String indicatorId,
            String indicatorIds,
            String dimensionFilters) {
        String sourceType = ConnectorFactory.normalizeSourceType(source.getSourceType());
        if (!connectorFactory.isSupported(sourceType)) {
            return null;
        }
        try {
            Map<String, Object> connectorSource = SourceConnectorMapper.toConnectorSource(source);
            BaseConnector connector = connectorFactory.get(sourceType);
            ConnectorFetchResult fetchResult = connector.fetch(connectorSource);
            if (!fetchResult.isSuccess()) {
                log.debug(
                        "live preview fetch failed source={} status={}",
                        source.getId(),
                        fetchResult.httpStatus());
                return null;
            }
            List<Map<String, Object>> parsed = connector.parse(fetchResult.raw(), connectorSource);
            if (parsed.isEmpty()) {
                return null;
            }
            int capped = Math.max(1, Math.min(limit, 500));
            List<Map<String, Object>> sampleRows = parsed.size() > 500 ? parsed.subList(0, 500) : parsed;
            String groupField = SourceRecordGroupHelper.detectGroupField(sampleRows);
            List<Map<String, Object>> indicators = SourceRecordGroupHelper.buildIndicators(groupField, sampleRows);
            List<String> explicitIds =
                    SourceRecordGroupHelper.parseExplicitIndicatorIds(indicatorId, indicatorIds, EXPLICIT_INDICATOR_CAP);
            String selectedSingle = explicitIds.size() == 1 ? explicitIds.getFirst() : null;
            List<String> selectedMulti = explicitIds.size() > 1 ? explicitIds : List.of();
            Map<String, Object> dimFilters = SourceRecordGroupHelper.parseDimensionFilters(dimensionFilters);
            List<Map<String, Object>> fetched = new ArrayList<>();
            for (Map<String, Object> row : parsed) {
                if (SourceRecordGroupHelper.rowMatchesFilters(
                        row, dimFilters, groupField, selectedMulti, selectedSingle)) {
                    fetched.add(row);
                }
                if (fetched.size() >= Math.min(5000, capped * 12)) {
                    break;
                }
            }
            List<Map<String, Object>> rows = SourceRecordGroupHelper.sampleEven(fetched, capped);
            List<String> fields = SourceRecordGroupHelper.collectFields(rows);
            SourcePreviewStateHelper.MapPreviewSource previewSource = SourcePreviewStateHelper.toPreviewSource(source);
            Map<String, Object> sourceOut = previewSourceMap(previewSource);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("row_count", rows.size());
            metadata.put("filters_applied", dimFilters);
            metadata.put("dimensions", Map.of());
            metadata.put("warning", null);
            metadata.put("live_connector_preview", true);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("source", sourceOut);
            out.put("dataset", null);
            out.put("dataset_id", "");
            out.put("title", source.getName());
            out.put("rows", rows);
            out.put("fields", fields);
            out.put("columns", SourceRecordGroupHelper.columnsFromFields(fields));
            out.put("metadata", metadata);
            out.put("indicators", indicators);
            out.put("group_field", groupField);
            out.put("selected_indicator", selectedSingle);
            out.put("selected_indicators", selectedMulti.isEmpty() && selectedSingle == null ? List.of() : explicitIds);
            out.put("preview_state", "live_preview");
            out.put("sync_state", "not_synced");
            out.put(
                    "message",
                    "Živý náhled přes konektor (bez lokální DB) — parita s catalog_preview_routes.py.");
            return out;
        } catch (Exception ex) {
            log.debug("live connector preview failed source={}: {}", source.getId(), ex.getMessage());
            return null;
        }
    }

    private static boolean supportsLiveConnectorPreview(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            return false;
        }
        String normalized = ConnectorFactory.normalizeSourceType(sourceType).toLowerCase(Locale.ROOT);
        return LIVE_CONNECTOR_TYPES.contains(normalized);
    }
}
