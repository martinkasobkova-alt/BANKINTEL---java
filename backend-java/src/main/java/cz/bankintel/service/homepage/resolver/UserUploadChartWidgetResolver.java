package cz.bankintel.service.homepage.resolver;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.domain.entity.UserUploadEntity;
import cz.bankintel.repository.UserUploadRepository;
import cz.bankintel.search.CatalogPreviewService;
import cz.bankintel.service.homepage.resolver.ChartComparisonSupport.ChartLine;
import cz.bankintel.service.myseries.SavedSeriesResolverService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserUploadChartWidgetResolver {

    private final UserUploadRepository uploadRepository;
    private final SavedSeriesResolverService savedSeriesResolverService;
    private final CatalogPreviewService catalogPreviewService;
    private final DatasetViewResolver datasetViewResolver;

    /** Bez uživatele - stávající volající (KPI pásmo, náhled), kde "Srovnat s řadou" nedává smysl. */
    public Map<String, Object> resolve(Map<String, Object> cfg) {
        return resolveSingle(cfg);
    }

    /**
     * S uživatelem navíc slučuje "Srovnat s řadou" položky uložené v {@code
     * cfg.chart_compare_with} - stejná funkce, jakou pro katalogový primární graf dělá {@link
     * ExternalCatalogChartWidgetResolver#resolve}. Dřív tahle třída {@code chart_compare_with}
     * vůbec nečetla, takže srovnávací katalogová řada přidaná k widgetu s vlastními daty jako
     * primárem se po reloadu vždy ztratila.
     */
    public Map<String, Object> resolve(Map<String, Object> cfg, UserEntity user) {
        Map<String, Object> out = resolveSingle(cfg);
        if (out.get("error") == null) {
            mergeComparisons(out, cfg, user);
        }
        return out;
    }

    private Map<String, Object> resolveSingle(Map<String, Object> cfg) {
        String uploadId = firstNonBlank(
                str(cfg.get("user_upload_id")), str(cfg.get("upload_id")), str(cfg.get("file_upload_id")));
        String ownerUserId = str(cfg.get("owner_user_id"));
        if (uploadId.isBlank()) {
            return Map.of("error", "Soubor není k dispozici (chybí upload).");
        }
        UserUploadEntity upload = uploadRepository.findById(uploadId).orElse(null);
        if (upload == null) {
            return Map.of("error", "Soubor není k dispozici.");
        }
        if (ownerUserId.isBlank()) {
            ownerUserId = upload.getUserId();
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>(cfg);
            payload.put("kind", "user_upload");
            payload.put("user_upload_id", uploadId);
            SavedSeriesResolverService.ResolvedPoints resolved =
                    savedSeriesResolverService.resolvePoints(ownerUserId, payload);
            List<Map<String, Object>> points = resolved.points();
            if (points.isEmpty()) {
                return Map.of("error", "Z nahraného souboru se nepodařilo načíst řadu.");
            }
            String view = str(cfg.get("view")).isBlank() ? "chart" : str(cfg.get("view")).toLowerCase(Locale.ROOT);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("title", upload.getOriginalName());
            out.put("view", view);
            if ("chart".equals(view)) {
                out.put(
                        "rows",
                        points.stream()
                                .map(p -> Map.<String, Object>of("x", p.get("period"), "y", p.get("value")))
                                .toList());
            } else {
                out.put("rows", points);
            }
            return out;
        } catch (Exception ex) {
            return Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Upload preview failed");
        }
    }

    /**
     * "Srovnat s řadou" pro widget, jehož PRIMÁRNÍ graf je vlastní nahraný soubor - zrcadlo {@link
     * ExternalCatalogChartWidgetResolver#mergeCatalogComparisons} pro opačný směr. Podporuje
     * katalogovou srovnávací řadu (hlavní žádaný případ) a druhý nahraný soubor jako srovnání
     * (symetrie, přes rekurzivní volání {@link #resolveSingle}). {@code computed_id} srovnávací
     * položky nejsou podporované ani v katalogovém směru - stejně se tiše přeskočí i zde.
     */
    @SuppressWarnings("unchecked")
    private void mergeComparisons(Map<String, Object> out, Map<String, Object> cfg, UserEntity user) {
        Object rawCompare = cfg.get("chart_compare_with");
        if (!(rawCompare instanceof List<?> requested) || requested.isEmpty()) {
            return;
        }

        List<ChartLine> lines = ChartComparisonSupport.chartLines(out, str(out.get("title")), "left");
        if (lines.isEmpty()) {
            out.put("compare_requested_count", requested.size());
            out.put("compare_added_count", 0);
            return;
        }

        String primaryUploadId = firstNonBlank(
                str(cfg.get("user_upload_id")), str(cfg.get("upload_id")), str(cfg.get("file_upload_id")));
        List<Map<String, Object>> lineIdentities = new ArrayList<>();
        Map<String, Object> primaryIdentity = new LinkedHashMap<>();
        primaryIdentity.put("source_type", "upload");
        primaryIdentity.put("user_upload_id", primaryUploadId);
        for (int i = 0; i < lines.size(); i++) {
            lineIdentities.add(primaryIdentity);
        }

        List<String> errors = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        seen.add("upload|" + primaryUploadId);
        int added = 0;
        for (Object item : requested) {
            if (!(item instanceof Map<?, ?> rawEntry) || added >= 8) {
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) rawEntry;
            String uploadId = ChartComparisonSupport.firstPresent(entry, "user_upload_id", "upload_id");
            if (!uploadId.isBlank()) {
                String identity = "upload|" + uploadId;
                if (!seen.add(identity)) {
                    continue;
                }
                Map<String, Object> uploadCfg = new LinkedHashMap<>(entry);
                if (user != null) {
                    uploadCfg.put("owner_user_id", user.getId());
                }
                Map<String, Object> uploadRendered = resolveSingle(uploadCfg);
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
            String identity = catalog.toLowerCase(Locale.ROOT) + "|" + setId;
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
            String indicator =
                    ChartComparisonSupport.firstPresent(entry, "selected_indicator", "indicator_id", "indicator");
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).strip() : "";
    }
}
