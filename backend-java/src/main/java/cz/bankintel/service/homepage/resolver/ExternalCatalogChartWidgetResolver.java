package cz.bankintel.service.homepage.resolver;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.search.CatalogPreviewService;
import cz.bankintel.service.homepage.resolver.ChartComparisonSupport.ChartLine;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Widget {@code external_catalog_chart} — live data z katalogu přes preview pipeline. */
@Component
@RequiredArgsConstructor
public class ExternalCatalogChartWidgetResolver {

    private final CatalogPreviewService catalogPreviewService;
    private final DatasetViewResolver datasetViewResolver;
    private final UserUploadChartWidgetResolver userUploadChartWidgetResolver;

    public Map<String, Object> resolve(Map<String, Object> cfg, UserEntity user) {
        String catalog = str(cfg.get("catalog")).isBlank() ? str(cfg.get("source_type")) : str(cfg.get("catalog"));
        String setId = str(cfg.get("set_id"));
        if (catalog.isBlank() || setId.isBlank()) {
            return Map.of("view", "chart", "error", "Chybí katalog nebo set_id.");
        }
        Map<String, Object> previewBody = new LinkedHashMap<>(cfg);
        previewBody.put("source_type", catalog);
        previewBody.putIfAbsent("catalog", catalog);
        previewBody.putIfAbsent("set_id", setId);
        Map<String, Object> preview = catalogPreviewService.preview(previewBody);
        Object err = preview.get("error");
        if (err != null) {
            return Map.of("view", "chart", "error", String.valueOf(err));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = preview.get("rows") instanceof List<?> list
                ? (List<Map<String, Object>>) (List<?>) list
                : List.of();
        if (rows.isEmpty()) {
            return Map.of("view", "chart", "error", "Pro tuto řadu nebyla nalezena žádná data k zobrazení.");
        }
        Map<String, Object> datasetCfg = new LinkedHashMap<>(cfg);
        datasetCfg.put("view", str(cfg.get("view")).isBlank() ? "chart" : str(cfg.get("view")));
        Map<String, Object> out = primarySnapshot(cfg);
        if (out == null) {
            out = datasetViewResolver.resolveFromRows(rows, datasetCfg, "external_catalog_chart", user);
        }
        out.putIfAbsent("title", cfg.get("title"));
        out.put("catalog", catalog);
        out.put("set_id", setId);
        mergeCatalogComparisons(out, cfg, user, catalog, setId);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> primarySnapshot(Map<String, Object> cfg) {
        if (!(cfg.get("chart_primary_snapshot") instanceof Map<?, ?> rawSnapshot)) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>((Map<String, Object>) rawSnapshot);
        if (!(snapshot.get("rows") instanceof List<?> rows) || rows.isEmpty() || rows.size() > 5000) {
            return null;
        }
        if (ChartComparisonSupport.chartLines(snapshot, str(snapshot.get("title")), "left").isEmpty()) {
            return null;
        }
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private void mergeCatalogComparisons(
            Map<String, Object> out,
            Map<String, Object> cfg,
            UserEntity user,
            String primaryCatalog,
            String primarySetId) {
        Object rawCompare = cfg.get("chart_compare_with");
        if (!(rawCompare instanceof List<?> requested) || requested.isEmpty() || out.get("error") != null) {
            return;
        }

        List<ChartLine> lines = ChartComparisonSupport.chartLines(out, str(out.get("title")), "left");
        if (lines.isEmpty()) {
            out.put("compare_requested_count", requested.size());
            out.put("compare_added_count", 0);
            return;
        }

        // Katalogova identita kazde radky v poradi, v jakem se pridavaji do `lines` - primarni
        // radka(y) + kazda uspesne pridana srovnavaci radka. Bez tohohle frontend (AI chat nad
        // grafem) nema jak poznat, ktery set_id/catalog patri k ktere pridane rade po jmenu.
        List<Map<String, Object>> lineIdentities = new ArrayList<>();
        Map<String, Object> primaryIdentity = new LinkedHashMap<>();
        primaryIdentity.put("source_type", primaryCatalog);
        primaryIdentity.put("set_id", primarySetId);
        for (int i = 0; i < lines.size(); i++) {
            lineIdentities.add(primaryIdentity);
        }

        List<String> errors = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(primaryCatalog.toLowerCase() + "|" + primarySetId);
        int added = 0;
        for (Object item : requested) {
            if (!(item instanceof Map<?, ?> rawEntry) || added >= 8) {
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) rawEntry;
            String uploadId = ChartComparisonSupport.firstPresent(entry, "user_upload_id", "upload_id");
            if (!uploadId.isBlank()) {
                // Vlastní nahraná data v "Srovnat s řadou" nemají catalog/set_id (mají
                // user_upload_id) - dřív proto vždycky spadly do "catalog.isBlank()" větve níže a
                // tiše zmizely, bez chyby v `errors`. Řeší se stejným resolverem jako samostatný
                // "Graf z mých dat" widget, jen se výsledek přidá jako další řádka do `lines`
                // místo vlastního widgetu.
                String identity = "upload|" + uploadId;
                if (!seen.add(identity) || user == null) {
                    continue;
                }
                Map<String, Object> uploadCfg = new LinkedHashMap<>(entry);
                uploadCfg.put("owner_user_id", user.getId());
                Map<String, Object> uploadRendered = userUploadChartWidgetResolver.resolve(uploadCfg);
                String uploadLabel = ChartComparisonSupport.seriesLabel(entry, str(uploadRendered.get("title")));
                if (uploadRendered.get("error") != null) {
                    errors.add(uploadLabel + ": " + uploadRendered.get("error"));
                    continue;
                }
                List<ChartLine> uploadLines =
                        ChartComparisonSupport.chartLines(uploadRendered, uploadLabel, str(entry.get("y_axis")));
                if (uploadLines.isEmpty() || uploadLines.getFirst().points().isEmpty()) {
                    errors.add(uploadLabel + ": no chart points");
                    continue;
                }
                lines.add(uploadLines.getFirst());
                Map<String, Object> uploadIdentity = new LinkedHashMap<>();
                uploadIdentity.put("source_type", "upload");
                uploadIdentity.put("user_upload_id", uploadId);
                lineIdentities.add(uploadIdentity);
                added++;
                continue;
            }
            String catalog = ChartComparisonSupport.firstPresent(entry, "catalog", "source_type", "source");
            String setId = ChartComparisonSupport.firstPresent(entry, "set_id", "series_id", "code");
            String identity = catalog.toLowerCase() + "|" + setId;
            if (catalog.isBlank() || setId.isBlank() || !seen.add(identity)) {
                continue;
            }

            Map<String, Object> extraBody = new LinkedHashMap<>(entry);
            extraBody.put("source_type", catalog);
            extraBody.put("catalog", catalog);
            extraBody.put("set_id", setId);
            extraBody.remove("chart_compare_with");
            Map<String, Object> extraPreview = catalogPreviewService.preview(extraBody);
            if (extraPreview.get("error") != null) {
                errors.add(ChartComparisonSupport.seriesLabel(entry, setId) + ": " + extraPreview.get("error"));
                continue;
            }
            List<Map<String, Object>> extraRows = extraPreview.get("rows") instanceof List<?> list
                    ? (List<Map<String, Object>>) (List<?>) list
                    : List.of();
            if (extraRows.isEmpty()) {
                errors.add(ChartComparisonSupport.seriesLabel(entry, setId) + ": no data");
                continue;
            }

            Map<String, Object> extraCfg = new LinkedHashMap<>(entry);
            extraCfg.put("source_type", catalog);
            extraCfg.put("catalog", catalog);
            extraCfg.put("set_id", setId);
            extraCfg.put("title", ChartComparisonSupport.seriesLabel(entry, setId));
            extraCfg.put("view", "chart");
            extraCfg.put("chart_series_mode", "single");
            extraCfg.remove("chart_compare_with");
            Map<String, Object> rendered = datasetViewResolver.resolveFromRows(
                    extraRows, extraCfg, "external_catalog_chart", user);
            if (rendered.get("error") != null) {
                errors.add(ChartComparisonSupport.seriesLabel(entry, setId) + ": " + rendered.get("error"));
                continue;
            }
            List<ChartLine> extraLines = ChartComparisonSupport.chartLines(
                    rendered, ChartComparisonSupport.seriesLabel(entry, setId), str(entry.get("y_axis")));
            if (extraLines.isEmpty() || extraLines.getFirst().points().isEmpty()) {
                errors.add(ChartComparisonSupport.seriesLabel(entry, setId) + ": no chart points");
                continue;
            }
            lines.add(extraLines.getFirst());
            Map<String, Object> lineIdentity = new LinkedHashMap<>();
            lineIdentity.put("source_type", catalog);
            lineIdentity.put("set_id", setId);
            String indicator = ChartComparisonSupport.firstPresent(entry, "selected_indicator", "indicator_id", "indicator");
            if (!indicator.isBlank()) {
                lineIdentity.put("selected_indicator", indicator);
            }
            lineIdentities.add(lineIdentity);
            added++;
        }

        out.put("compare_requested_count", requested.size());
        out.put("compare_added_count", added);
        if (!errors.isEmpty()) {
            out.put("compare_errors", errors);
        }
        if (added == 0) {
            return;
        }
        ChartComparisonSupport.applyMergedSeries(out, lines, lineIdentities);
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).strip() : "";
    }
}
