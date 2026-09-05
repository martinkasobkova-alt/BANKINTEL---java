package cz.bankintel.service.homepage;

import cz.bankintel.domain.entity.HomepageConfigEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.HomepageConfigRepository;
import cz.bankintel.service.homepage.resolver.SourceRecordsWidgetResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HomepageHeadlineKpiService {

    private static final String CONFIG_ID = "main";

    private final HomepageConfigRepository configRepository;
    private final SourceRecordsWidgetResolver sourceRecordsWidgetResolver;
    private final cz.bankintel.service.homepage.resolver.ExternalCatalogChartWidgetResolver externalCatalogChartWidgetResolver;
    private final cz.bankintel.service.homepage.resolver.ComputedViewWidgetResolver computedViewWidgetResolver;
    private final cz.bankintel.service.homepage.resolver.UserUploadChartWidgetResolver userUploadChartWidgetResolver;

    @Transactional(readOnly = true)
    public Map<String, Object> resolveHeadlineKpis() {
        HomepageConfigEntity config = configRepository.findById(CONFIG_ID).orElse(null);
        // Verejny prehled kuratoruje admin a je dostupny i anonymne -> bez uzivatele.
        return resolveList(config != null ? config.getHeadlineKpis() : null, null);
    }

    /**
     * Dopočítá hodnoty pro libovolný seznam KPI konfigurací — používá to veřejný přehled
     * i osobní dashboard, aby resolvování existovalo jen na jednom místě.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> resolveList(List<Map<String, Object>> kpis, UserEntity user) {
        List<Map<String, Object>> source = kpis != null ? kpis : List.of();
        List<Map<String, Object>> resolved = new ArrayList<>();
        for (Map<String, Object> kpi : source) {
            resolved.add(resolveOne(kpi, user));
        }
        return Map.of("kpis", resolved);
    }

    private Map<String, Object> resolveOne(Map<String, Object> kpi, UserEntity user) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("id", kpi.getOrDefault("id", ""));
        base.put("title", kpi.getOrDefault("title", ""));
        base.put("value", null);
        base.put("unit", "");
        base.put("period", null);
        base.put("prev_value", null);
        base.put("prev_period", null);
        base.put("trend", "neutral");
        String type = String.valueOf(kpi.getOrDefault("type", "")).strip();
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = kpi.get("config") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        fillFromRows(base, resolveData(type, cfg, user));
        return base;
    }

    /**
     * Dopocet podle typu dlazdice. Vetve odpovidaji tomu, co umi HeadlineKpiPicker vyrobit —
     * driv tu byl jen `arad_view`, takze dlazdice z Eurostatu, CSU, vypoctu i vlastnich dat
     * zustavaly prazdne.
     */
    private Map<String, Object> resolveData(String type, Map<String, Object> cfg, UserEntity user) {
        if ("computed_view".equals(type)) {
            return computedViewWidgetResolver.resolve(cfg, user);
        }
        if ("user_upload_chart".equals(type) || "uploaded_data_chart".equals(type)) {
            return userUploadChartWidgetResolver.resolve(cfg);
        }
        if ("external_catalog_chart".equals(type)) {
            // Katalogova rada. Kdyz ma config `chart_primary_snapshot`, resolver ho vrati
            // rovnou a preview pipeline se vubec nevola — proto se prehled otevre okamzite.
            return externalCatalogChartWidgetResolver.resolve(cfg, user);
        }
        // arad_view, eurostat_view, csu_view, fred_view, … — vsechny ctou zaznamy
        // registrovaneho zdroje pres source_id + indicator_id, typ zdroje je jim jedno.
        if (type.endsWith("_view")) {
            return sourceRecordsWidgetResolver.resolveAradView(cfg, null);
        }
        return null;
    }

    /**
     * Posledni hodnota rady + predchozi pro trend. Tvar `rows` je stejny u obou resolveru
     * (klice `value`/`y` a `period`/`x`), takze extrakce je spolecna.
     */
    private void fillFromRows(Map<String, Object> base, Map<String, Object> data) {
        if (data == null) {
            base.put("error", "Žádná data.");
            return;
        }
        if (data.containsKey("error")) {
            base.put("error", data.get("error"));
            return;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = data.get("rows") instanceof List<?> list
                ? (List<Map<String, Object>>) (List<?>) list
                : List.of();
        if (rows.isEmpty()) {
            base.put("error", "Žádná data.");
            return;
        }
        // Poradi radku se mezi zdroji lisi — ARAD vraci od nejnovejsiho, CSU od nejstarsiho.
        // Brat proste posledni radek znamenalo u ARAD ukazat hodnotu z roku 1993.
        // A kdyz sada obsahuje vic ukazatelu, musi predchozi hodnota patrit teze rade.
        List<Map<String, Object>> series = sameSeriesAs(rows, newestRow(rows));
        Map<String, Object> last = newestRow(series);
        Map<String, Object> prev = secondNewestRow(series, last);

        Object value = readValue(last);
        base.put("value", value);
        base.put("period", periodOf(last));
        base.put("unit", data.getOrDefault("unit", ""));
        if (prev != null) {
            Object prevValue = readValue(prev);
            base.put("prev_value", prevValue);
            base.put("prev_period", periodOf(prev));
            if (value instanceof Number n && prevValue instanceof Number p) {
                base.put("trend", n.doubleValue() >= p.doubleValue() ? "up" : "down");
            }
        }
    }

    private static Object periodOf(Map<String, Object> row) {
        return row == null ? null : row.getOrDefault("period", row.get("x"));
    }

    /** Klic rady — u sad s vice ukazateli oddeli jednotlive rady od sebe. */
    private static String seriesKeyOf(Map<String, Object> row) {
        if (row == null) return "";
        for (String k : new String[] {"indicator_id", "series_id", "indicator", "series"}) {
            Object v = row.get(k);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        return "";
    }

    private static List<Map<String, Object>> sameSeriesAs(List<Map<String, Object>> rows, Map<String, Object> ref) {
        String key = seriesKeyOf(ref);
        if (key.isBlank()) return rows;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (key.equals(seriesKeyOf(r))) out.add(r);
        }
        return out.isEmpty() ? rows : out;
    }

    /**
     * Porovnani obdobi napric zdroji: „20260831", „2026-Q1", „2025", „prosinec 2025".
     * Cislice se porovnaji cislem, zbytek textem — spolehne se to na to, ze v jedne rade
     * maji obdobi stejny tvar.
     */
    private static int comparePeriods(Object a, Object b) {
        String x = String.valueOf(a == null ? "" : a).trim();
        String y = String.valueOf(b == null ? "" : b).trim();
        String dx = x.replaceAll("[^0-9]", "");
        String dy = y.replaceAll("[^0-9]", "");
        if (!dx.isEmpty() && !dy.isEmpty() && dx.length() == dy.length()) {
            int byDigits = dx.compareTo(dy);
            if (byDigits != 0) return byDigits;
        }
        return x.compareTo(y);
    }

    private static Map<String, Object> newestRow(List<Map<String, Object>> rows) {
        Map<String, Object> best = null;
        for (Map<String, Object> r : rows) {
            if (best == null || comparePeriods(periodOf(r), periodOf(best)) > 0) best = r;
        }
        return best != null ? best : rows.get(rows.size() - 1);
    }

    private static Map<String, Object> secondNewestRow(List<Map<String, Object>> rows, Map<String, Object> newest) {
        Map<String, Object> best = null;
        for (Map<String, Object> r : rows) {
            if (r == newest) continue;
            if (comparePeriods(periodOf(r), periodOf(newest)) >= 0) continue;
            if (best == null || comparePeriods(periodOf(r), periodOf(best)) > 0) best = r;
        }
        return best;
    }

    private static Object readValue(Map<String, Object> row) {
        Object v = row.get("value");
        return v != null ? v : row.get("y");
    }
}
