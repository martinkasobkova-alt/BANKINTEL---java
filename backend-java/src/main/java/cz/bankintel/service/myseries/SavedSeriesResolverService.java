package cz.bankintel.service.myseries;

import cz.bankintel.domain.entity.RecordEntity;
import cz.bankintel.domain.entity.SourceEntity;
import cz.bankintel.domain.entity.UserUploadEntity;
import cz.bankintel.repository.RecordRepository;
import cz.bankintel.repository.SourceRepository;
import cz.bankintel.repository.UserUploadRepository;
import cz.bankintel.service.upload.UploadPolicy;
import cz.bankintel.service.upload.UserPrivateStorageService;
import cz.bankintel.service.userdata.UserDataParseService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class SavedSeriesResolverService {

    private static final int MAX_STORED_POINTS = 4000;
    private static final Set<String> UNSUPPORTED_SOURCE_TYPES = Set.of("external_catalog", "rss");

    private final UserUploadRepository uploadRepository;
    private final UserPrivateStorageService storageService;
    private final SourceRepository sourceRepository;
    private final RecordRepository recordRepository;

    public record ResolvedPoints(List<Map<String, Object>> points, Map<String, Object> meta) {}

    public ResolvedPoints resolvePoints(String userId, Map<String, Object> resolverPayload) {
        if (resolverPayload == null || resolverPayload.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatný resolver_payload.");
        }
        String kind = String.valueOf(resolverPayload.getOrDefault("kind", "source_indicator"))
                .strip()
                .toLowerCase(Locale.ROOT);
        if ("user_upload".equals(kind)) {
            return resolveUserUpload(userId, resolverPayload);
        }
        if (!kind.isBlank() && !"source_indicator".equals(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neznámý typ resolveru.");
        }
        return resolveSourceIndicator(resolverPayload);
    }

    private ResolvedPoints resolveUserUpload(String userId, Map<String, Object> payload) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nahranou řadu lze uložit jen po přihlášení.");
        }
        String uploadId = String.valueOf(payload.getOrDefault("user_upload_id", "")).strip();
        if (uploadId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybí user_upload_id.");
        }
        UserUploadEntity upload = uploadRepository
                .findByIdAndUserId(uploadId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload nenalezen."));
        byte[] raw = storageService.read(userId, upload.getStoredRelPath());
        String ext = UploadPolicy.extension(upload.getOriginalName());
        List<Map<String, Object>> rows = UserDataParseService.readTabularRows(raw, ext);
        String xField = firstNonBlank(payload.get("x_field"), "date");
        String yField = firstNonBlank(payload.get("y_field"), "value");
        Map<String, Double> seriesMap = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object x = pickField(row, xField);
            Object y = pickField(row, yField);
            if (x == null || y == null) {
                continue;
            }
            Double val = UserDataParseService.parseNumber(y);
            String period = UserDataParseService.formatPeriod(x);
            if (val == null || period == null) {
                continue;
            }
            seriesMap.put(period, val);
        }
        if (seriesMap.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Z nahraného souboru se nepodařilo načíst číselnou řadu (zkontrolujte sloupce času a hodnoty).");
        }
        List<Map<String, Object>> points = trimPoints(normalizePoints(seriesMap));
        Map<String, Object> meta = spanMeta(points);
        meta.put("source_type", "user_upload");
        meta.put("unit", String.valueOf(payload.getOrDefault("unit", "")));
        meta.put("frequency", String.valueOf(payload.getOrDefault("frequency", "")));
        meta.put("area", "");
        meta.put("category", "");
        return new ResolvedPoints(points, meta);
    }

    private ResolvedPoints resolveSourceIndicator(Map<String, Object> payload) {
        String sourceId = String.valueOf(payload.getOrDefault("source_id", "")).strip();
        if (sourceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybí source_id v resolver_payload.");
        }
        SourceEntity source = sourceRepository
                .findById(sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Zdroj nenalezen."));
        String sourceType = source.getSourceType() != null ? source.getSourceType().strip().toLowerCase(Locale.ROOT) : "";
        if (UNSUPPORTED_SOURCE_TYPES.contains(sourceType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tento zdroj zatím nelze uložit do Moje datové řady.");
        }

        String xField = String.valueOf(payload.getOrDefault("x_field", "")).strip();
        String yField = String.valueOf(payload.getOrDefault("y_field", "")).strip();
        String indicatorId = String.valueOf(payload.getOrDefault("indicator_id", "")).strip();

        List<RecordEntity> records = recordRepository
                .findAll((root, query, cb) -> cb.equal(root.get("sourceId"), sourceId), PageRequest.of(0, MAX_STORED_POINTS))
                .getContent();
        if (records.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Pro tento výběr nejsou v databázi žádná data. Zkuste znovu po synchronizaci zdroje, nebo jinou řadu.");
        }

        Map<String, Double> seriesMap = new LinkedHashMap<>();
        for (RecordEntity record : records) {
            Map<String, Object> data = record.getData() != null ? record.getData() : Map.of();
            if (!indicatorId.isBlank()) {
                String dedupe = record.getDedupeKey() != null ? record.getDedupeKey() : "";
                if (!dedupe.contains(indicatorId) && !data.containsKey(indicatorId)) {
                    continue;
                }
            }
            Object x;
            Object y;
            if (!xField.isBlank() && !yField.isBlank()) {
                x = pickField(data, xField);
                y = pickField(data, yField);
            } else if (!indicatorId.isBlank()) {
                x = firstOf(data, "date", "period", "time", "obdobi");
                y = data.get(indicatorId);
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vyberte indikátor nebo dvojici sloupců (čas + hodnota).");
            }
            Double val = UserDataParseService.parseNumber(y);
            String period = UserDataParseService.formatPeriod(x);
            if (val == null || period == null) {
                continue;
            }
            seriesMap.put(period, val);
        }
        if (seriesMap.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Pro tento výběr nejsou v databázi žádná data. Zkuste znovu po synchronizaci zdroje, nebo jinou řadu.");
        }
        List<Map<String, Object>> points = trimPoints(normalizePoints(seriesMap));
        Map<String, Object> meta = spanMeta(points);
        meta.put("source_type", sourceType.isBlank() ? "custom" : sourceType);
        meta.put("unit", String.valueOf(payload.getOrDefault("unit", "")));
        meta.put("frequency", String.valueOf(payload.getOrDefault("frequency", "")));
        meta.put("area", String.valueOf(payload.getOrDefault("area", "")));
        meta.put("category", String.valueOf(payload.getOrDefault("category", "")));
        return new ResolvedPoints(points, meta);
    }

    private static Object pickField(Map<String, Object> row, String field) {
        if (row.containsKey(field)) {
            return row.get(field);
        }
        String target = field.strip().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().strip().toLowerCase(Locale.ROOT).equals(target)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Object firstOf(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            if (data.containsKey(key) && data.get(key) != null) {
                return data.get(key);
            }
        }
        return null;
    }

    private static List<Map<String, Object>> normalizePoints(Map<String, Double> seriesMap) {
        List<Map.Entry<String, Double>> items = new ArrayList<>(seriesMap.entrySet());
        items.sort(Comparator.comparing(Map.Entry::getKey));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Double> item : items) {
            out.add(Map.of("period", item.getKey(), "value", item.getValue()));
        }
        return out;
    }

    private static List<Map<String, Object>> trimPoints(List<Map<String, Object>> points) {
        if (points.size() <= MAX_STORED_POINTS) {
            return points;
        }
        return new ArrayList<>(points.subList(points.size() - MAX_STORED_POINTS, points.size()));
    }

    private static Map<String, Object> spanMeta(List<Map<String, Object>> points) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (points.isEmpty()) {
            meta.put("start_period", "");
            meta.put("end_period", "");
            meta.put("last_period", "");
            meta.put("last_value", null);
            return meta;
        }
        Map<String, Object> first = points.getFirst();
        Map<String, Object> last = points.getLast();
        meta.put("start_period", first.get("period"));
        meta.put("end_period", last.get("period"));
        meta.put("last_period", last.get("period"));
        meta.put("last_value", last.get("value"));
        return meta;
    }

    private static String firstNonBlank(Object value, String fallback) {
        String raw = value == null ? "" : String.valueOf(value).strip();
        return raw.isBlank() ? fallback : raw;
    }
}
