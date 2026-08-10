package cz.bankintel.explore.manager.fetch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** OECD EO offline mirror — port {@code services/oecd4_manager_fetch.py}. */
@Component
public class Oecd4ManagerFetch implements ManagerSegmentFetch {

    private final ManagerMirrorFetchSupport support;

    public Oecd4ManagerFetch(ManagerMirrorFetchSupport support) {
        this.support = support;
    }

    @Override
    public boolean supports(String sourceType) {
        String st = normalize(sourceType);
        return "oecd4".equals(st) || "oecd_local".equals(st);
    }

    @Override
    public List<Map<String, Object>> fetchSegmentData(String query, Map<String, Object> context) {
        String datasetKey = "economic_outlook_118";
        String measure = resolveMeasure(query, context);
        String refArea = ManagerMirrorFetchSupport.refAreaForGeo(ManagerMirrorFetchSupport.resolveGeo(context));
        String freq = "A";
        Object qpObj = context.get("query_params");
        if (qpObj instanceof Map<?, ?> qp) {
            String fromQp = ManagerMirrorFetchSupport.str(qp.get("measure"));
            if (!fromQp.isBlank()) {
                measure = fromQp.toUpperCase(Locale.ROOT);
            }
            String fq = ManagerMirrorFetchSupport.str(qp.get("freq"));
            if (!fq.isBlank()) {
                freq = fq.toUpperCase(Locale.ROOT);
            }
            String ra = ManagerMirrorFetchSupport.firstNonBlank(qp.get("REF_AREA"), qp.get("ref_area"));
            if (!ra.isBlank()) {
                refArea = ra.toUpperCase(Locale.ROOT);
            }
        }
        if (measure.isBlank()) {
            measure = "GDPV_ANNPCT";
        }

        List<Map<String, Object>> rows = support.loadOecd4SnapshotRows(datasetKey);
        if (rows.isEmpty()) {
            return support.mirrorUnavailableRows(
                    "oecd4",
                    support.dataRoot().resolve("oecd4").resolve("snapshots").resolve(datasetKey));
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!refArea.equals(ManagerMirrorFetchSupport.str(row.get("REF_AREA")).toUpperCase(Locale.ROOT))) {
                continue;
            }
            if (!measure.equals(ManagerMirrorFetchSupport.str(row.get("MEASURE")).toUpperCase(Locale.ROOT))) {
                continue;
            }
            if (!freq.equals(ManagerMirrorFetchSupport.str(row.get("FREQ")).toUpperCase(Locale.ROOT))) {
                continue;
            }
            Double value = ManagerMirrorFetchSupport.toDouble(row.get("OBS_VALUE"));
            if (value == null) {
                value = ManagerMirrorFetchSupport.toDouble(row.get("value"));
            }
            String period =
                    ManagerMirrorFetchSupport.firstNonBlank(row.get("TIME_PERIOD"), row.get("date"), row.get("period"));
            if (value == null || period.isBlank()) {
                continue;
            }
            Map<String, Object> obs = new LinkedHashMap<>();
            obs.put("date", period);
            obs.put("period", period);
            obs.put("value", value);
            obs.put("source", "oecd4");
            out.add(obs);
        }
        out.sort((a, b) -> ManagerMirrorFetchSupport.str(a.get("period")).compareTo(ManagerMirrorFetchSupport.str(b.get("period"))));
        return out;
    }

    private static String resolveMeasure(String query, Map<String, Object> context) {
        String fromQuery = ManagerMirrorFetchSupport.str(query);
        if (!fromQuery.isBlank() && fromQuery.length() <= 20 && fromQuery.equals(fromQuery.toUpperCase(Locale.ROOT))) {
            return fromQuery;
        }
        Object qpObj = context.get("query_params");
        if (qpObj instanceof Map<?, ?> qp) {
            String m = ManagerMirrorFetchSupport.str(qp.get("oecd4_measure"));
            if (!m.isBlank()) {
                return m.toUpperCase(Locale.ROOT);
            }
        }
        return "";
    }

    private static String normalize(String sourceType) {
        return sourceType != null ? sourceType.trim().toLowerCase(Locale.ROOT) : "";
    }
}
