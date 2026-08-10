package cz.bankintel.explore.manager.fetch;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** EIOPA insurance — port {@code services/eiopa_manager_fetch.py}. */
@Component
public class EiopaManagerFetch implements ManagerSegmentFetch {

    private static final String LONG_CSV = "insurance_statistics/eiopa_insurance_long.csv";

    private final ManagerMirrorFetchSupport support;

    public EiopaManagerFetch(ManagerMirrorFetchSupport support) {
        this.support = support;
    }

    @Override
    public boolean supports(String sourceType) {
        String st = normalize(sourceType);
        return "eiopa".equals(st) || "eiopa_mirror".equals(st);
    }

    @Override
    public List<Map<String, Object>> fetchSegmentData(String query, Map<String, Object> context) {
        String seriesId = ManagerMirrorFetchSupport.resolveSeriesId(query, context, "eiopa_series_id");
        String geo = ManagerMirrorFetchSupport.resolveGeo(context);
        var csv = support.dataRoot().resolve("eiopa").resolve(LONG_CSV);
        if (!support.mirrorAvailable(csv)) {
            return support.mirrorUnavailableRows("eiopa", csv);
        }
        return support.filterBySeriesAndGeo(
                support.loadLongCsv(csv), seriesId, "series_id", "country_code", geo, "period");
    }

    private static String normalize(String sourceType) {
        return sourceType != null ? sourceType.trim().toLowerCase(Locale.ROOT) : "";
    }
}
