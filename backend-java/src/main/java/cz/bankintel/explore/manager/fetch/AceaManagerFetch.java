package cz.bankintel.explore.manager.fetch;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Automotive registrations — port {@code services/acea_manager_fetch.py}. */
@Component
public class AceaManagerFetch implements ManagerSegmentFetch {

    private static final String LONG_CSV = "acea_automotive_long.csv";

    private final ManagerMirrorFetchSupport support;

    public AceaManagerFetch(ManagerMirrorFetchSupport support) {
        this.support = support;
    }

    @Override
    public boolean supports(String sourceType) {
        String st = normalize(sourceType);
        return "acea_mirror".equals(st) || "acea".equals(st);
    }

    @Override
    public List<Map<String, Object>> fetchSegmentData(String query, Map<String, Object> context) {
        String seriesId = ManagerMirrorFetchSupport.resolveSeriesId(query, context, "acea_series_id");
        String geo = ManagerMirrorFetchSupport.resolveGeo(context);
        var csv = support.mirrorLongCsv("acea", LONG_CSV);
        if (!support.mirrorAvailable(csv)) {
            return support.mirrorUnavailableRows("acea", csv);
        }
        return support.filterBySeriesAndGeo(
                support.loadLongCsv(csv), seriesId, "series_id", "geo", geo, "period");
    }

    private static String normalize(String sourceType) {
        return sourceType != null ? sourceType.trim().toLowerCase(Locale.ROOT) : "";
    }
}
