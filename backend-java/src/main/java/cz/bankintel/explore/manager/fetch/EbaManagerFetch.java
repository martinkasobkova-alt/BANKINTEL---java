package cz.bankintel.explore.manager.fetch;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** EBA banking capital/risks — port {@code services/eba_manager_fetch.py}. */
@Component
public class EbaManagerFetch implements ManagerSegmentFetch {

    private static final String LONG_CSV = "eba_banking_long.csv";

    private final ManagerMirrorFetchSupport support;

    public EbaManagerFetch(ManagerMirrorFetchSupport support) {
        this.support = support;
    }

    @Override
    public boolean supports(String sourceType) {
        String st = normalize(sourceType);
        return "eba_mirror".equals(st) || "eba".equals(st);
    }

    @Override
    public List<Map<String, Object>> fetchSegmentData(String query, Map<String, Object> context) {
        String seriesId = ManagerMirrorFetchSupport.resolveSeriesId(query, context, "eba_series_id");
        String geo = ManagerMirrorFetchSupport.resolveGeo(context);
        var csv = support.mirrorLongCsv("eba", LONG_CSV);
        if (!support.mirrorAvailable(csv)) {
            return support.mirrorUnavailableRows("eba", csv);
        }
        return support.filterBySeriesAndGeo(
                support.loadLongCsv(csv), seriesId, "series_id", "country", geo, "period");
    }

    private static String normalize(String sourceType) {
        return sourceType != null ? sourceType.trim().toLowerCase(Locale.ROOT) : "";
    }
}
