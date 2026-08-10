package cz.bankintel.explore.manager.fetch;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** GIE gas storage/flows — port {@code services/gie_manager_fetch.py}. */
@Component
public class GieManagerFetch implements ManagerSegmentFetch {

    private static final String LONG_CSV = "gie_energy_long.csv";

    private final ManagerMirrorFetchSupport support;

    public GieManagerFetch(ManagerMirrorFetchSupport support) {
        this.support = support;
    }

    @Override
    public boolean supports(String sourceType) {
        String st = normalize(sourceType);
        return "gie_mirror".equals(st) || "gie".equals(st);
    }

    @Override
    public List<Map<String, Object>> fetchSegmentData(String query, Map<String, Object> context) {
        String seriesId = ManagerMirrorFetchSupport.resolveSeriesId(query, context, "gie_series_id");
        String geo = ManagerMirrorFetchSupport.resolveGeo(context);
        var csv = support.mirrorLongCsv("gie", LONG_CSV);
        if (!support.mirrorAvailable(csv)) {
            return support.mirrorUnavailableRows("gie", csv);
        }
        List<Map<String, Object>> rows = support.loadLongCsv(csv);
        List<Map<String, Object>> byGasDay =
                support.filterBySeriesAndGeo(rows, seriesId, "series_id", "geo", geo, "gas_day");
        if (byGasDay.size() >= 2) {
            return byGasDay;
        }
        return support.filterBySeriesAndGeo(rows, seriesId, "series_id", "geo", geo, "period");
    }

    private static String normalize(String sourceType) {
        return sourceType != null ? sourceType.trim().toLowerCase(Locale.ROOT) : "";
    }
}
