package cz.bankintel.sources.alphavantage;
import cz.bankintel.util.BankIntelEnvVars;

import cz.bankintel.domain.dto.SourceDtos.SourceCreateRequest;
import cz.bankintel.domain.entity.SourceEntity;
import cz.bankintel.repository.SourceRepository;
import cz.bankintel.security.AdminAccess;
import cz.bankintel.service.sources.SourceService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlphaVantageCatalogService {

    private static final String CAT_PATH = "ALPHA VANTAGE — akcie / indexy";
    private static final Pattern AV_SYM_RE = Pattern.compile("^__av_sym__([A-Za-z0-9.-]{1,20})$", Pattern.CASE_INSENSITIVE);
    private static final String AV_ROOT = "https://www.alphavantage.co";

    private final SourceRepository sourceRepository;
    private final SourceService sourceService;
    private final AdminAccess adminAccess;

    public AlphaVantageCatalogService(
            SourceRepository sourceRepository, SourceService sourceService, AdminAccess adminAccess) {
        this.sourceRepository = sourceRepository;
        this.sourceService = sourceService;
        this.adminAccess = adminAccess;
    }

    public Map<String, Object> getCatalog() {
        List<Map<String, Object>> sets = new ArrayList<>();
        for (SourceEntity source : sourceRepository.findBySourceTypeOrderByNameAsc("alphavantage")) {
            sets.add(Map.of("set_id", source.getId(), "name", seriesLabel(source), "kind", "selection"));
        }
        return Map.of(
                "categories",
                List.of(Map.of("path", CAT_PATH, "name", CAT_PATH, "children", List.of(), "sets", sets)),
                "total_sets", sets.size());
    }

    public Map<String, Object> addSource(Map<String, Object> payload) {
        adminAccess.requireAdmin();
        if (!alphavantageApiKeyConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Na serveru není nastaven ALPHAVANTAGE_API_KEY — nelze synchronizovat Alpha Vantage.");
        }
        String setId = stringOrBlank(payload.get("set_id"));
        Matcher matcher = AV_SYM_RE.matcher(setId);
        if (!matcher.matches()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Neplatné set_id — očekává se symbol z vyhledávání ve tvaru __av_sym__TICKER.");
        }
        String symbol = matcher.group(1).trim().toUpperCase(Locale.ROOT);
        String fn = stringOrBlank(payload.get("function"));
        Object qpExtra = payload.get("query_params");
        if (qpExtra instanceof Map<?, ?> qpMap && !stringOrBlank(qpMap.get("function")).isBlank()) {
            fn = stringOrBlank(qpMap.get("function"));
        }
        if (fn.isBlank()) {
            fn = "TIME_SERIES_DAILY";
        }
        String displayName = stringOrBlank(payload.get("name"));
        if (displayName.isBlank()) {
            displayName = "Alpha Vantage · " + symbol + " (" + fn + ")";
        }
        if (sourceRepository.existsByName(displayName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Zdroj '" + displayName + "' už existuje.");
        }
        Map<String, Object> queryParams = new LinkedHashMap<>();
        queryParams.put("function", fn);
        queryParams.put("symbol", symbol);
        queryParams.put("outputsize", "full");
        queryParams.put("datatype", "json");
        SourceCreateRequest request = new SourceCreateRequest(
                displayName,
                "alphavantage",
                AV_ROOT,
                "/query",
                "GET",
                "none",
                null,
                Map.of("Accept", "application/json"),
                queryParams,
                1440,
                true,
                displayName,
                null);
        Map<String, Object> created = sourceService.createSource(request);
        return Map.of("id", created.get("id"), "name", displayName, "set_id", created.get("id"));
    }

    /**
     * Port of {@code invalidate_av_cache} (backend/routes/alphavantage_catalog_routes.py, ř. 160).
     *
     * <p>Poznámka k portu: Python cachuje syrové odpovědi Alpha Vantage API v Mongo kolekci
     * {@code alphavantage_cache} s TTL; Java port zdroje Alpha Vantage synchronizuje generickým
     * connector/sync pipeline bez samostatné mezivrstvy pro cache syrových odpovědí, takže zde není
     * co reálně mazat. Endpoint nicméně zachovává stejný request/response kontrakt (admin, {@code
     * ok/symbol/function}) pro kompatibilitu s frontendem a budoucí rozšíření.
     */
    public Map<String, Object> invalidateCache(Map<String, Object> payload) {
        adminAccess.requireAdmin();
        Map<String, Object> body = payload != null ? payload : Map.of();
        String symbol = stringOrBlank(body.get("symbol")).toUpperCase(Locale.ROOT);
        if (symbol.isBlank()) {
            symbol = "*";
        }
        String function = stringOrBlank(body.get("function")).toUpperCase(Locale.ROOT);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("symbol", symbol);
        out.put("function", function.isBlank() ? null : function);
        return out;
    }

    public boolean alphavantageApiKeyConfigured() {
        String key = BankIntelEnvVars.get("ALPHAVANTAGE_API_KEY");
        return key != null && !key.isBlank();
    }

    private static String seriesLabel(SourceEntity source) {
        Map<String, Object> qp = queryParams(source);
        String sym = stringOrBlank(qp.get("symbol")).toUpperCase(Locale.ROOT);
        String fn = stringOrBlank(qp.get("function"));
        if (fn.isBlank()) {
            fn = "TIME_SERIES_DAILY";
        }
        String nm = stringOrBlank(source.getName());
        String base = nm.isBlank() ? sym : nm;
        if (!sym.isBlank()) {
            return base + " · " + sym + " (" + fn + ")";
        }
        return base + " (" + fn + ")";
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> queryParams(SourceEntity source) {
        Map<String, Object> config = source.getConnectorConfig();
        if (config == null) {
            return Map.of();
        }
        Object qp = config.get("query_params");
        if (qp instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return out;
        }
        return Map.of();
    }
}
