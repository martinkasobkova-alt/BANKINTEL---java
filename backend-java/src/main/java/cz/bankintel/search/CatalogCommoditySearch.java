package cz.bankintel.search;

import cz.bankintel.search.model.CatalogHit;
import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.search.model.CatalogRawRow;
import cz.bankintel.search.scoring.CatalogScoringPipeline;
import cz.bankintel.sources.commodities.WorldbankCommoditiesService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Pink Sheet / CMO commodity catalog search — port {@code _commodities_results} in catalog_global_search.py. */
@Service
@RequiredArgsConstructor
public class CatalogCommoditySearch {

    private final WorldbankCommoditiesService commoditiesService;
    private final CatalogScoringPipeline scoringPipeline;

    public boolean commodityQueryAllowed(String query) {
        return CatalogSearchLexicon.commodityQuery(query);
    }

    public List<CatalogHit> searchHits(String query, int limit) {
        if (!commodityQueryAllowed(query)) {
            return List.of();
        }
        commoditiesService.ensureCommoditiesCache();
        List<Map<String, Object>> rows = buildCatalogRows();
        List<String> directTerms = CatalogSearchLexicon.commodityEnglishTerms(query);
        if (!directTerms.isEmpty()) {
            rows = prioritizeDirectMatches(rows, directTerms);
        }
        List<CatalogRawRow> typed = rows.stream().map(CatalogRawRow::of).toList();
        return scoringPipeline.scoreAndRank("commodities", query, typed, limit);
    }

    private List<Map<String, Object>> buildCatalogRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> series : asMapList(commoditiesService.loadPinkSheetCatalog().get("series"))) {
            rows.add(toCatalogRow(series, "Pink Sheet", "pink_sheet_actual", "actual"));
        }
        for (Map<String, Object> item : asMapList(commoditiesService.loadForecasts().get("items"))) {
            rows.add(toCatalogRow(item, "CMO Forecast", "cmo_forecast", "forecast"));
        }
        return rows;
    }

    private static Map<String, Object> toCatalogRow(
            Map<String, Object> series, String defaultCategory, String itemKind, String kind) {
        String setId = CatalogMapSupport.str(series.get("set_id"));
        String name = CatalogMapSupport.firstNonBlank(series.get("name"), setId);
        String category = CatalogMapSupport.firstNonBlank(series.get("category"), defaultCategory);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(CatalogKeys.SET_ID, setId);
        row.put(CatalogKeys.NAME, name);
        row.put(CatalogKeys.TITLE, name);
        row.put(CatalogKeys.FULL_PATH, CatalogMapSupport.firstNonBlank(series.get("full_path"), defaultCategory + " > " + name));
        row.put("description", category + " · World Bank Pink Sheet");
        row.put("category", category);
        row.put("unit", series.get("unit"));
        row.put("kind", "set");
        row.put("item_kind", itemKind);
        row.put(CatalogKeys.SOURCE_TYPE, "commodities");
        row.put(CatalogKeys.CATALOG_ID, "commodities");
        Map<String, Object> qp = new LinkedHashMap<>();
        qp.put("kind", kind);
        qp.put("pink_sheet_code", setId);
        qp.put("commodity_code", setId);
        row.put("query_params", qp);
        return row;
    }

    private static List<Map<String, Object>> prioritizeDirectMatches(List<Map<String, Object>> rows, List<String> terms) {
        List<Map<String, Object>> priority = new ArrayList<>();
        List<Map<String, Object>> rest = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = CatalogTextUtils.foldAscii(CatalogMapSupport.str(row.get(CatalogKeys.NAME)));
            boolean match = terms.stream().anyMatch(term -> name.contains(CatalogTextUtils.foldAscii(term)));
            if (match) {
                priority.add(row);
            } else {
                rest.add(row);
            }
        }
        if (priority.isEmpty()) {
            return rows;
        }
        List<Map<String, Object>> merged = new ArrayList<>(priority);
        merged.addAll(rest);
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add(CatalogMapSupport.castMap(map));
            }
        }
        return out;
    }
}
