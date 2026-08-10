package cz.bankintel.explore.manager.fetch;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** ENTSO-E electricity — port {@code services/entsoe_manager_fetch.py}. */
@Component
public class EntsoeManagerFetch implements ManagerSegmentFetch {

    private static final String LONG_CSV = "entsoe_energy_long.csv";

    private final ManagerMirrorFetchSupport support;

    public EntsoeManagerFetch(ManagerMirrorFetchSupport support) {
        this.support = support;
    }

    @Override
    public boolean supports(String sourceType) {
        String st = normalize(sourceType);
        return "entsoe_mirror".equals(st) || "entsoe".equals(st);
    }

    @Override
    public List<Map<String, Object>> fetchSegmentData(String query, Map<String, Object> context) {
        String seriesId = ManagerMirrorFetchSupport.resolveSeriesId(query, context, "entsoe_series_id");
        String geo = ManagerMirrorFetchSupport.resolveGeo(context);
        var csv = support.mirrorLongCsv("entsoe", LONG_CSV);
        if (!support.mirrorAvailable(csv)) {
            return support.mirrorUnavailableRows("entsoe", csv);
        }
        List<Map<String, Object>> rows = support.loadLongCsv(csv);
        List<Map<String, Object>> byZone =
                support.filterBySeriesAndGeo(rows, seriesId, "series_id", "bidding_zone", geo, "period");
        if (byZone.size() >= 2) {
            return byZone;
        }
        return support.filterBySeriesAndGeo(rows, seriesId, "series_id", "country_iso2", geo, "period");
    }

    private static String normalize(String sourceType) {
        return sourceType != null ? sourceType.trim().toLowerCase(Locale.ROOT) : "";
    }
}
