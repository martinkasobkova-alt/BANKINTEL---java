package cz.bankintel.sources.oecd4;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.util.BankIntelDataPaths;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** OECD 4 offline mirror browse — port {@code oecd4_catalog.py}. */
@Service
public class Oecd4BrowseService {

    private static final String OECD4_BROWSE_ROOT = "OECD · offline mirror";

    /**
     * Živě ověřeno na reálných snímcích: různé OECD4 datasety mají úplně jiné SDMX dimenze
     * (industry_services.json: MEASURE+FREQ+ACTIVITY+ADJUSTMENT+UNIT_MEASURE; employment_lfs.json:
     * MEASURE+SEX+AGE+LABOUR_FORCE_STATUS+UNIT_MEASURE, žádné FREQ/ACTIVITY/ADJUSTMENT vůbec).
     * Dřívější kód počítal dedup podpis i název jen z pevné pětice polí - u datasetů s jinými
     * dimenzemi (např. SEX/AGE/LABOUR_FORCE_STATUS) se tak skutečně různé řady tiše slily do
     * jedné (243 kandidátů pro Česko → 3 přeživší), a graf té přeživší řady pak míchal
     * muže+ženy+celkem dohromady - chyba ve správnosti dat, ne jen v názvu. Tahle sada polí se
     * naopak NIKDY nepovažuje za dimenzi (metadata řady/pozorování, ne rozlišující rozměr).
     */
    private static final Set<String> NON_DIMENSION_ROW_FIELDS = Set.of(
            "REF_AREA", "REF_AREA_LABEL", "TIME_PERIOD", "OBS_VALUE", "amount", "date", "value", "period");

    private static final Set<String> TOTAL_LIKE_DIMENSION_VALUES = Set.of("_T", "_Z", "TOTAL", "ALL");

    /**
     * Klíče v `queryParams`, které NEjsou dimenzí k filtrování - buď je `previewRows` už řeší
     * zvlášť (measure/freq/unit_measure/ref_area/key ve všech svých aliasech), nebo je jde o
     * protokolová pole, která do stejné mapy přidává `OecdConnector.buildQuery` pro živou SDMX v2
     * cestu (zde irelevantní, offline mirror je čte jinak) - bez týhle výjimky by appka zkusila
     * řádky filtrovat třeba podle `attributes=dsd`, což žádný řádek nemá, a náhled by vždy vrátil
     * nic.
     */
    private static final Set<String> NON_DIMENSION_QUERY_PARAM_KEYS = Set.of(
            "oecd4_key", "key", "dataset_key",
            "oecd4_ref_area", "ref_area", "REF_AREA", "country",
            "oecd4_measure", "MEASURE", "measure",
            "freq", "FREQ",
            "unit_measure", "UNIT_MEASURE",
            "provider", "attributes", "measures", "dimensionAtObservation", "format", "lastNObservations",
            "oecd_api_mode", "base_url", "endpoint", "headers");

    private final ObjectMapper objectMapper;
    private final JsonFactory jsonFactory;
    private final Map<String, List<Oecd4DatasetMeta>> datasetsByKey = new ConcurrentHashMap<>();
    private volatile List<Oecd4DatasetMeta> allDatasets = List.of();

    public Oecd4BrowseService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.jsonFactory = objectMapper.getFactory();
    }

    public Map<String, Object> getBrowseTree() {
        ensureDatasets();
        Map<String, List<Map<String, Object>>> byCat = new LinkedHashMap<>();
        int totalSets = 0;
        int offlineCount = 0;
        for (Oecd4DatasetMeta ds : allDatasets) {
            if (!ds.offline()) {
                continue;
            }
            offlineCount++;
            int rowCount = snapshotRowCount(ds.key());
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("path", OECD4_BROWSE_ROOT + " > " + ds.category() + " > " + ds.key());
            child.put("name", ds.title());
            child.put("children", List.of());
            child.put("sets", List.of());
            child.put("oecd4_key", ds.key());
            child.put("oecd4_offline", true);
            child.put("oecd4_row_count", rowCount);
            child.put("oecd4_dataset_lazy", true);
            child.put(
                    "browse_notice",
                    rowCount > 0
                            ? rowCount + " řádků v offline snímku — rozbalte pro země a ukazatele."
                            : "Snímek zatím není stažen — spusťte oecd4_outlook_store.");
            byCat.computeIfAbsent(ds.category(), ignored -> new ArrayList<>()).add(child);
        }
        List<Map<String, Object>> categories = new ArrayList<>();
        for (String cat : byCat.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()) {
            List<Map<String, Object>> children = byCat.get(cat);
            children.sort(Comparator.comparing(c -> stringOrBlank(c.get("name")).toLowerCase(Locale.ROOT)));
            totalSets += children.size();
            categories.add(Map.of(
                    "path", OECD4_BROWSE_ROOT + " > " + cat,
                    "name", cat,
                    "children", children,
                    "sets", List.of()));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("path", OECD4_BROWSE_ROOT);
        root.put("name", OECD4_BROWSE_ROOT);
        root.put("children", categories);
        root.put("sets", List.of());
        root.put(
                "browse_notice",
                "OECD — " + offlineCount + " offline datasetů v lokálním mirroru. Data bez limitu 429; náhled okamžitě z disku.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", List.of(root));
        out.put("total_sets", totalSets);
        out.put("oecd4_offline_count", offlineCount);
        out.put("oecd4_browse_mode", "category_dataset_country_measure");
        return out;
    }

    public Map<String, Object> getDatasetNode(String key) {
        Oecd4DatasetMeta ds = requireDataset(key);
        if (!ds.offline()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Offline snímek pro " + key + " není k dispozici.");
        }
        List<Map<String, String>> countries = countriesForDataset(key);
        String dsPath = OECD4_BROWSE_ROOT + " > " + ds.category() + " > " + key;
        List<Map<String, Object>> children = new ArrayList<>();
        for (Map<String, String> c : countries) {
            String code = c.get("code");
            children.add(Map.of(
                    "path", dsPath + " > " + code,
                    "name", c.get("name") + " (" + code + ")",
                    "children", List.of(),
                    "sets", List.of(),
                    "oecd4_key", key,
                    "oecd4_ref_area", code,
                    "oecd4_country_lazy", true));
        }
        Map<String, Object> datasetNode = new LinkedHashMap<>();
        datasetNode.put("path", dsPath);
        datasetNode.put("name", ds.title());
        datasetNode.put("children", children);
        datasetNode.put("sets", List.of());
        datasetNode.put("oecd4_key", key);
        datasetNode.put("oecd4_country_count", children.size());
        datasetNode.put("browse_notice", children.size() + " zemí/agregátů — rozbalte zemi pro ukazatele.");
        return Map.of("dataset_node", datasetNode, "oecd4_key", key);
    }

    public Map<String, Object> getCountryNode(String key, String refArea) {
        Oecd4DatasetMeta ds = requireDataset(key);
        String ra = stringOrBlank(refArea).toUpperCase(Locale.ROOT);
        if (ra.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chybí REF_AREA");
        }
        List<Map<String, Object>> sets = seriesForCountry(ds, ra);
        String dsPath = OECD4_BROWSE_ROOT + " > " + ds.category() + " > " + key;
        String label = refAreaLabel(ra, ra);
        Map<String, Object> countryNode = new LinkedHashMap<>();
        countryNode.put("path", dsPath + " > " + ra);
        countryNode.put("name", label + " (" + ra + ")");
        countryNode.put("children", List.of());
        countryNode.put("sets", sets);
        countryNode.put("oecd4_key", key);
        countryNode.put("oecd4_ref_area", ra);
        countryNode.put("oecd4_series_count", sets.size());
        countryNode.put("browse_notice", sets.size() + " ukazatelů pro " + label + ".");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("country_node", countryNode);
        out.put("oecd4_key", key);
        out.put("oecd4_ref_area", ra);
        out.put("total", sets.size());
        return out;
    }

    /**
     * Whether a local offline-mirror snapshot exists for this dataset key - lets callers route a
     * request to the offline preview path (see {@link #previewRows}) instead of a live network call,
     * without needing to load the full dataset index first.
     */
    public boolean hasOfflineSnapshot(String key) {
        return key != null && !key.isBlank() && Files.isRegularFile(snapshotPath(key));
    }

    public List<Map<String, Object>> previewRows(Map<String, Object> queryParams) {
        Map<String, Object> qp = queryParams != null ? queryParams : Map.of();
        String key = firstNonBlank(qp, "oecd4_key", "key", "dataset_key");
        if (key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OECD4 offline preview vyzaduje oecd4_key.");
        }
        String refArea = normalizeRefArea(firstNonBlank(qp, "oecd4_ref_area", "REF_AREA", "ref_area", "country"));
        String measure = firstNonBlank(qp, "oecd4_measure", "MEASURE", "measure").toUpperCase(Locale.ROOT);
        String freq = firstNonBlank(qp, "freq", "FREQ").toUpperCase(Locale.ROOT);
        String unit = firstNonBlank(qp, "unit_measure", "UNIT_MEASURE").toUpperCase(Locale.ROOT);
        // Živě zjištěno: bez tohohle appka řadu identifikovala jen podle MEASURE/FREQ/UNIT_MEASURE
        // a náhled/graf pak tiše smíchal dohromady třeba muže+ženy+celkem nebo sezónně očištěná i
        // neočištěná data - karta ve stromu (viz `seriesQueryParams`) přitom už tyhle další
        // rozlišující dimenze do query_params dávala, jen se tady nikdy nečetly zpátky. Nejde o
        // pevný seznam jmen - liší se dataset od datasetu - takže se prostě přefiltruje všechno,
        // co v příchozích parametrech není jedno z výše řešených/protokolových polí.
        Map<String, String> extraFilters = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : qp.entrySet()) {
            if (NON_DIMENSION_QUERY_PARAM_KEYS.contains(e.getKey())) {
                continue;
            }
            String v = stringOrBlank(e.getValue());
            if (!v.isBlank()) {
                extraFilters.put(e.getKey().toUpperCase(Locale.ROOT), v.toUpperCase(Locale.ROOT));
            }
        }

        Path snapshot = snapshotPath(key);
        if (!Files.isRegularFile(snapshot)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        try (InputStream in = Files.newInputStream(snapshot);
                JsonParser parser = jsonFactory.createParser(in)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return List.of();
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();
                if (!"rows".equals(field) || parser.currentToken() != JsonToken.START_ARRAY) {
                    parser.skipChildren();
                    continue;
                }
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (parser.currentToken() != JsonToken.START_OBJECT) {
                        parser.skipChildren();
                        continue;
                    }
                    Map<String, Object> row = objectMapper.readValue(parser, new TypeReference<>() {});
                    if (!matchesDimension(row, "REF_AREA", refArea)) {
                        continue;
                    }
                    if (!matchesDimension(row, "MEASURE", measure)) {
                        continue;
                    }
                    if (!matchesDimension(row, "FREQ", freq)) {
                        continue;
                    }
                    if (!matchesDimension(row, "UNIT_MEASURE", unit)) {
                        continue;
                    }
                    if (!matchesAllExtraDimensions(row, extraFilters)) {
                        continue;
                    }
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    copy.putIfAbsent("source", "oecd4");
                    copy.putIfAbsent("dataset_id", key);
                    copy.putIfAbsent("set_id", "OECD4|" + key + "|" + refArea + "|" + measure + "|" + freq);
                    out.add(copy);
                }
            }
        } catch (IOException ex) {
            return List.of();
        }
        out.sort(Comparator.comparing(r -> stringOrBlank(
                r.getOrDefault("TIME_PERIOD", r.getOrDefault("date", r.get("period"))))));
        return out;
    }

    private List<Map<String, Object>> seriesForCountry(Oecd4DatasetMeta ds, String refArea) {
        Path snapshot = snapshotPath(ds.key());
        if (!Files.isRegularFile(snapshot)) {
            return List.of();
        }
        Set<List<String>> seen = new LinkedHashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        try (InputStream in = Files.newInputStream(snapshot);
                JsonParser parser = jsonFactory.createParser(in)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return List.of();
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();
                if (!"rows".equals(field) || parser.currentToken() != JsonToken.START_ARRAY) {
                    parser.skipChildren();
                    continue;
                }
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (parser.currentToken() != JsonToken.START_OBJECT) {
                        continue;
                    }
                    Map<String, Object> row = objectMapper.readValue(parser, new TypeReference<>() {});
                    if (!refArea.equalsIgnoreCase(stringOrBlank(row.get("REF_AREA")))) {
                        continue;
                    }
                    String meas = stringOrBlank(row.get("MEASURE")).toUpperCase(Locale.ROOT);
                    if (meas.isBlank()) {
                        continue;
                    }
                    List<String> dimKeys = dimensionKeys(row);
                    List<String> sig = new ArrayList<>(dimKeys.size());
                    for (String k : dimKeys) {
                        sig.add(k + "=" + stringOrBlank(row.get(k)).toUpperCase(Locale.ROOT));
                    }
                    if (!seen.add(sig)) {
                        continue;
                    }
                    // MEASURE a FREQ mají už dnes vlastní, pevnou pozici v set_id/query_params
                    // (zpětná kompatibilita s `InMemorySourceBuilder.buildOecd4Offline`, které
                    // set_id parsuje po pozicích parts[1..4] - viz komentář u seriesSetId) -
                    // "extra" jsou jen ZBYLÉ dimenze, cokoli navíc, podle skutečného datasetu.
                    List<String> extraDimKeys = new ArrayList<>(dimKeys);
                    extraDimKeys.remove("MEASURE");
                    extraDimKeys.remove("FREQ");
                    String name = seriesDisplayName(row, extraDimKeys);
                    Map<String, Object> set = new LinkedHashMap<>();
                    set.put("set_id", seriesSetId(ds.key(), refArea, row, extraDimKeys));
                    set.put("name", name);
                    set.put("kind", "selection");
                    set.put("item_kind", "selection");
                    set.put("territory", refAreaLabel(refArea, refArea));
                    set.put("oecd4_key", ds.key());
                    set.put("oecd4_ref_area", refArea);
                    set.put("query_params", seriesQueryParams(ds, refArea, row, extraDimKeys));
                    set.put("oecd_browse_source", "oecd4_offline");
                    out.add(set);
                }
            }
        } catch (IOException ex) {
            return List.of();
        }
        out.sort(Comparator.comparing(r -> stringOrBlank(r.get("name")).toLowerCase(Locale.ROOT)));
        return out;
    }

    private List<Map<String, String>> countriesForDataset(String key) {
        Path snapshot = snapshotPath(key);
        if (!Files.isRegularFile(snapshot)) {
            return List.of();
        }
        Map<String, String> seen = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(snapshot);
                JsonParser parser = jsonFactory.createParser(in)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return List.of();
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();
                if (!"rows".equals(field) || parser.currentToken() != JsonToken.START_ARRAY) {
                    parser.skipChildren();
                    continue;
                }
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (parser.currentToken() != JsonToken.START_OBJECT) {
                        continue;
                    }
                    Map<String, Object> row = objectMapper.readValue(parser, new TypeReference<>() {});
                    String ra = stringOrBlank(row.get("REF_AREA")).toUpperCase(Locale.ROOT);
                    if (ra.isBlank()) {
                        continue;
                    }
                    String lbl = stringOrBlank(row.get("REF_AREA_LABEL"));
                    if (!seen.containsKey(ra) || (lbl.isBlank() && seen.get(ra).equals(ra))) {
                        seen.put(ra, lbl.isBlank() ? refAreaLabel(ra, ra) : lbl);
                    }
                }
            }
        } catch (IOException ex) {
            return List.of();
        }
        return seen.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().toLowerCase(Locale.ROOT)))
                .map(e -> Map.of("code", e.getKey(), "name", e.getValue()))
                .map(m -> (Map<String, String>) m)
                .toList();
    }

    private int snapshotRowCount(String key) {
        Path snapshot = snapshotPath(key);
        if (!Files.isRegularFile(snapshot)) {
            return 0;
        }
        try (InputStream in = Files.newInputStream(snapshot);
                JsonParser parser = jsonFactory.createParser(in)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return 0;
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if ("row_count".equals(parser.currentName())) {
                    parser.nextToken();
                    return parser.getIntValue();
                }
                parser.skipChildren();
            }
        } catch (IOException ex) {
            return 0;
        }
        return 0;
    }

    public Oecd4DatasetMeta datasetMeta(String key) {
        return requireDataset(key);
    }

    private Oecd4DatasetMeta requireDataset(String key) {
        ensureDatasets();
        return allDatasets.stream()
                .filter(d -> d.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Neznámý OECD4 dataset: " + key));
    }

    private void ensureDatasets() {
        if (!allDatasets.isEmpty()) {
            return;
        }
        synchronized (this) {
            if (!allDatasets.isEmpty()) {
                return;
            }
            allDatasets = loadMirrorIndex();
            for (Oecd4DatasetMeta ds : allDatasets) {
                datasetsByKey.put(ds.key(), List.of(ds));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Oecd4DatasetMeta> loadMirrorIndex() {
        Path indexPath = BankIntelDataPaths.oecd4Dir().resolve("segment_mirror_index.json");
        if (!Files.isRegularFile(indexPath)) {
            return scanOfflineFiles();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(indexPath.toFile(), new TypeReference<>() {});
            Object segmentsObj = raw.get("segments");
            List<Oecd4DatasetMeta> out = new ArrayList<>();
            if (segmentsObj instanceof Map<?, ?> segments) {
                for (Object listObj : segments.values()) {
                    if (!(listObj instanceof List<?> list)) {
                        continue;
                    }
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            Oecd4DatasetMeta meta = metaFromMap((Map<String, Object>) map);
                            if (meta != null) {
                                out.add(meta);
                            }
                        }
                    }
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        } catch (IOException ignored) {
            // fall through
        }
        return scanOfflineFiles();
    }

    private List<Oecd4DatasetMeta> scanOfflineFiles() {
        Path dir = BankIntelDataPaths.oecd4Dir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Oecd4DatasetMeta> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".json")).toList()) {
                String name = file.getFileName().toString();
                if ("segment_mirror_index.json".equals(name)) {
                    continue;
                }
                String key = name.replace(".json", "");
                out.add(new Oecd4DatasetMeta(key, key, "Other", "", "", "", true));
            }
        } catch (IOException ex) {
            return List.of();
        }
        return out;
    }

    private static Oecd4DatasetMeta metaFromMap(Map<String, Object> map) {
        String key = stringOrBlank(map.get("key"));
        if (key.isBlank()) {
            return null;
        }
        boolean offline = Boolean.TRUE.equals(map.get("offline")) || Files.isRegularFile(snapshotPath(key));
        return new Oecd4DatasetMeta(
                key,
                stringOrBlank(map.get("title")).isBlank() ? key : stringOrBlank(map.get("title")),
                stringOrBlank(map.get("category")).isBlank() ? "Other" : stringOrBlank(map.get("category")),
                stringOrBlank(map.get("agency")),
                stringOrBlank(map.get("dataflow")),
                stringOrBlank(map.get("version")),
                offline);
    }

    private static Path snapshotPath(String key) {
        return BankIntelDataPaths.oecd4Dir().resolve(key + ".json");
    }

    private static Map<String, Object> seriesQueryParams(
            Oecd4DatasetMeta ds, String refArea, Map<String, Object> row, List<String> extraDimKeys) {
        Map<String, Object> qp = new LinkedHashMap<>();
        qp.put("provider", "oecd4");
        qp.put("oecd4_key", ds.key());
        qp.put("oecd4_ref_area", refArea);
        qp.put("measure", row.get("MEASURE"));
        qp.put("freq", row.get("FREQ"));
        if (row.get("UNIT_MEASURE") != null) {
            qp.put("unit_measure", row.get("UNIT_MEASURE"));
        }
        // Zbylé dimenze podle skutečného datasetu (ACTIVITY/ADJUSTMENT u industry_services,
        // SEX/AGE/LABOUR_FORCE_STATUS u employment_lfs, ...) - beze změny se ztratí přesně ty,
        // co dřív appka tiše promíchávala. `previewRows` je čte zpátky stejnou dolní-case
        // konvencí a filtruje podle nich stejně jako podle measure/freq/unit_measure.
        for (String k : extraDimKeys) {
            if ("UNIT_MEASURE".equals(k)) {
                continue;
            }
            Object v = row.get(k);
            if (v != null && !stringOrBlank(v).isBlank()) {
                qp.put(k.toLowerCase(Locale.ROOT), v);
            }
        }
        return qp;
    }

    /**
     * MEASURE/FREQ zůstávají na svých dosavadních pozicích 3/4 - {@code InMemorySourceBuilder.
     * buildOecd4Offline} set_id na tyhle pozice spoléhá, když rekonstruuje zdroj jen ze
     * set_id bez uložených query_params (vzácná, ale reálná cesta). Zbylé rozlišující dimenze se
     * jen PŘIDÁVAJÍ za ně jako "KLIC=hodnota" - dřívější minimální délka (`parts.length >= 5`,
     * `CatalogPreviewSetIdSupport.oecdPreviewable`) zůstává splněná, jen se řetězec prodlouží.
     */
    private static String seriesSetId(String key, String refArea, Map<String, Object> row, List<String> extraDimKeys) {
        StringBuilder sb = new StringBuilder("OECD4|")
                .append(key).append('|')
                .append(refArea).append('|')
                .append(stringOrBlank(row.get("MEASURE"))).append('|')
                .append(stringOrBlank(row.get("FREQ")));
        for (String k : extraDimKeys) {
            String v = stringOrBlank(row.get(k));
            if (!v.isBlank()) {
                sb.append('|').append(k).append('=').append(v);
            }
        }
        return sb.toString();
    }

    private static String seriesDisplayName(Map<String, Object> row, List<String> extraDimKeys) {
        String base = stringOrBlank(row.get("MEASURE_LABEL"));
        if (base.isBlank()) {
            String measure = stringOrBlank(row.get("MEASURE"));
            String freq = stringOrBlank(row.get("FREQ_LABEL"));
            base = freq.isBlank() ? (measure.isBlank() ? "Series" : measure) : measure + " · " + freq;
        }
        List<String> extras = new ArrayList<>();
        for (String k : extraDimKeys) {
            String code = stringOrBlank(row.get(k));
            if (code.isBlank() || TOTAL_LIKE_DIMENSION_VALUES.contains(code.toUpperCase(Locale.ROOT))) {
                continue;
            }
            String label = stringOrBlank(row.get(k + "_LABEL"));
            extras.add(label.isBlank() ? code : label);
        }
        return extras.isEmpty() ? base : base + " · " + String.join(" · ", extras);
    }

    /**
     * Všechny SDMX dimenze reálně přítomné na řádku - schéma se dataset od datasetu liší (viz
     * komentář u {@link #NON_DIMENSION_ROW_FIELDS}), takže se nevyjmenovává napevno, ale odvozuje
     * z dat samotných. `_LABEL` varianty se vynechávají - to je jen zobrazovací název KE
     * kódu, ne samostatná dimenze (obě by jinak zdvojily podpis i set_id).
     */
    private static List<String> dimensionKeys(Map<String, Object> row) {
        List<String> keys = new ArrayList<>();
        for (String k : row.keySet()) {
            if (NON_DIMENSION_ROW_FIELDS.contains(k) || k.endsWith("_LABEL")) {
                continue;
            }
            if (stringOrBlank(row.get(k)).isBlank()) {
                continue;
            }
            keys.add(k);
        }
        Collections.sort(keys);
        return keys;
    }

    private static String refAreaLabel(String code, String fallback) {
        return switch (code) {
            case "CZE" -> "Czechia";
            case "DEU" -> "Germany";
            case "SVK" -> "Slovakia";
            case "POL" -> "Poland";
            case "USA" -> "United States";
            case "OECD" -> "OECD";
            case "EA20" -> "Euro area";
            default -> fallback;
        };
    }

    private static boolean matchesDimension(Map<String, Object> row, String field, String expectedUpper) {
        if (expectedUpper == null || expectedUpper.isBlank()) {
            return true;
        }
        String actual = stringOrBlank(row.get(field)).toUpperCase(Locale.ROOT);
        if (actual.isBlank()) {
            return false;
        }
        for (String expected : expectedUpper.split("[+,]")) {
            String candidate = expected.trim().toUpperCase(Locale.ROOT);
            if (!candidate.isBlank() && candidate.equals(actual)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAllExtraDimensions(Map<String, Object> row, Map<String, String> extraFilters) {
        for (Map.Entry<String, String> e : extraFilters.entrySet()) {
            if (!matchesDimension(row, e.getKey(), e.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static String firstNonBlank(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            String value = stringOrBlank(map.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String normalizeRefArea(String raw) {
        String code = stringOrBlank(raw).toUpperCase(Locale.ROOT).replace("[", "").replace("]", "");
        if (code.isBlank()) {
            return code;
        }
        if (code.contains("+") || code.contains(",")) {
            Set<String> normalized = new LinkedHashSet<>();
            for (String part : code.split("[+,]")) {
                String one = normalizeSingleRefArea(part);
                if (!one.isBlank()) {
                    normalized.add(one);
                }
            }
            return String.join("+", normalized);
        }
        return normalizeSingleRefArea(code);
    }

    private static String normalizeSingleRefArea(String raw) {
        String code = stringOrBlank(raw).toUpperCase(Locale.ROOT);
        if (code.isBlank() || code.length() == 3) {
            return code;
        }
        return switch (code) {
            case "CZ" -> "CZE";
            case "DE" -> "DEU";
            case "SK" -> "SVK";
            case "PL" -> "POL";
            case "AT" -> "AUT";
            case "US" -> "USA";
            case "GB", "UK" -> "GBR";
            case "FR" -> "FRA";
            case "IT" -> "ITA";
            case "ES" -> "ESP";
            case "NL" -> "NLD";
            case "BE" -> "BEL";
            case "HU" -> "HUN";
            default -> code;
        };
    }

    private static String stringOrBlank(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    public record Oecd4DatasetMeta(
            String key, String title, String category, String agency, String dataflow, String version, boolean offline) {}
}
