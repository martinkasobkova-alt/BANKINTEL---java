package cz.bankintel.explore.manager.fetch;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** FX/rates and financial markets mirror — port {@code services/financial_markets_manager_fetch.py}. */
@Component
public class FinancialMarketsManagerFetch implements ManagerSegmentFetch {

    private static final String LONG_CSV = "financial_markets_long.csv";

    private final ManagerMirrorFetchSupport support;

    public FinancialMarketsManagerFetch(ManagerMirrorFetchSupport support) {
        this.support = support;
    }

    @Override
    public boolean supports(String sourceType) {
        String st = normalize(sourceType);
        return "financial_markets_mirror".equals(st) || "financial_markets".equals(st);
    }

    @Override
    public List<Map<String, Object>> fetchSegmentData(String query, Map<String, Object> context) {
        String seriesId = ManagerMirrorFetchSupport.resolveSeriesId(query, context, "fm_series_id");
        var csv = support.mirrorLongCsv("financial_markets", LONG_CSV);
        if (!support.mirrorAvailable(csv)) {
            return support.mirrorUnavailableRows("financial_markets", csv);
        }
        return support.filterBySeriesAndGeo(
                support.loadLongCsv(csv), seriesId, "series_id", "geo_scope", "", "period");
    }

    private static String normalize(String sourceType) {
        return sourceType != null ? sourceType.trim().toLowerCase(Locale.ROOT) : "";
    }
}
