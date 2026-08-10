package cz.bankintel.explore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ExploreGeoResolver {

    private static final Logger log = LoggerFactory.getLogger(ExploreGeoResolver.class);

    /**
     * "EU" is not a real ISO-3166 country code, but the AI query-understanding step sometimes
     * collapses a continent-level question ("Evropa") to {@code country=EU, geo_mode=countries}
     * instead of the proper {@code geo_mode=continent, continent=europe} — which would otherwise
     * fall through to the generic "countries" branch below and get treated as a single unknown
     * 2-letter pseudo-country. This is a deterministic safety net, not a general alias table: it
     * only fires for a bare, non-delimited "EU" token, and "EU" is not used as a real country code
     * anywhere else in {@link ExploreGeoCatalog}.
     */
    private static final Map<String, String> PSEUDO_REGION_TO_CONTINENT = Map.of("EU", "europe");

    private static final Map<String, ContinentSpec> CONTINENTS = Map.ofEntries(
            Map.entry("europe", new ContinentSpec("Evropa", List.of("CZ", "DE", "AT", "PL", "SK", "FR", "IT", "ES", "NL", "BE"), "EU")),
            Map.entry("north_america", new ContinentSpec("Severní Amerika", List.of("US", "CA", "MX"), "US")),
            Map.entry("south_america", new ContinentSpec("Jižní Amerika", List.of("BR", "AR", "CL"), "BR")),
            Map.entry("asia", new ContinentSpec("Asie", List.of("JP", "CN", "IN", "KR", "ID"), "JP")),
            Map.entry("africa", new ContinentSpec("Afrika", List.of("ZA", "NG", "EG"), "ZA")),
            Map.entry("oceania", new ContinentSpec("Oceánie", List.of("AU", "NZ"), "AU")),
            Map.entry("americas", new ContinentSpec("Amerika", List.of("US", "CA", "BR", "MX"), "US")));

    /**
     * Reverse lookup (country code -> continent id) so callers can tell a genuinely cross-continent
     * conflict (a US-tagged row surfacing for a Europe question) apart from a same-continent country
     * simply outside {@link #CONTINENTS}' small "major economies" anchor list (e.g. Portugal for
     * Europe) - only the former is safe to drop outright as noise.
     *
     * <p>Loaded from {@code explore/geo-catalog.json}'s {@code all_countries} list (~195 countries,
     * the same data already backing the country picker) rather than derived from {@link
     * #CONTINENTS}' own ~30-country member lists - those lists are intentionally small (continent-mode
     * anchors/defaults), so deriving the reverse lookup from them left most of the world
     * unclassified and silently unable to ever trigger the cross-continent drop. Confirmed live:
     * "United Arab Emirates" consumer-price data surfaced unfiltered for a France/Spain
     * auto-parts-import question - UAE is nowhere in the small {@link #CONTINENTS} lists, so the
     * old lookup could not tell it apart from a same-continent neighbor no matter how far away it
     * actually is.
     */
    private static final Map<String, String> COUNTRY_TO_CONTINENT = loadCountryToContinent();

    private final ExploreGeoCatalog geoCatalog;

    public ExploreGeoResolver(ExploreGeoCatalog geoCatalog) {
        this.geoCatalog = geoCatalog;
    }

    public Map<String, Object> resolve(String country, String geoMode, String continent) {
        String mode = normalize(geoMode);
        String continentId = normalize(continent);
        String countryRaw = country == null ? "" : country.trim();

        // A specific, non-blank country always outranks a contradictory geo_mode="none" - the LLM
        // can produce exactly this combination for a sub-national/regional question with no
        // "region" mode to express (confirmed live: "Jakým významem se Praha podílí na HDP Česka?"
        // came back with country="CZ" AND geo_mode="none" together, since Prague itself has no
        // slot in the none|countries|continent schema). Silently trusting geo_mode over an
        // explicitly named country would discard real, useful geo context the caller already has.
        if (!countryRaw.isBlank() && "none".equals(mode)) {
            mode = "countries";
        }

        if ("continent".equals(mode) || !continentId.isBlank()) {
            ContinentSpec spec = CONTINENTS.get(continentId);
            if (spec == null) {
                return unknownGeo(countryRaw.isBlank() ? continentId : countryRaw);
            }
            Map<String, Object> geo = new LinkedHashMap<>();
            geo.put("mode", "continent");
            geo.put("country_codes", spec.members());
            geo.put("continent_id", continentId);
            geo.put("continent_label", spec.labelCs());
            geo.put("primary_code", spec.anchor());
            geo.put("display", spec.labelCs());
            return geo;
        }

        if ("none".equals(mode) || countryRaw.isBlank()) {
            Map<String, Object> geo = new LinkedHashMap<>();
            geo.put("mode", "none");
            geo.put("country_codes", List.of());
            geo.put("continent_id", null);
            geo.put("primary_code", "U2");
            geo.put("display", "Svět (globální kontext)");
            return geo;
        }

        String impliedContinentId = PSEUDO_REGION_TO_CONTINENT.get(countryRaw.toUpperCase(Locale.ROOT));
        if (impliedContinentId != null) {
            ContinentSpec spec = CONTINENTS.get(impliedContinentId);
            Map<String, Object> geo = new LinkedHashMap<>();
            geo.put("mode", "continent");
            geo.put("country_codes", spec.members());
            geo.put("continent_id", impliedContinentId);
            geo.put("continent_label", spec.labelCs());
            geo.put("primary_code", spec.anchor());
            geo.put("display", spec.labelCs());
            return geo;
        }

        List<String> codes = new ArrayList<>();
        for (String part : countryRaw.split("[,;|/]")) {
            String code = part.trim().toUpperCase(Locale.ROOT);
            if (code.length() == 2 && !codes.contains(code)) {
                codes.add(code);
            }
        }
        if (codes.isEmpty()) {
            return unknownGeo(countryRaw);
        }
        String display = codes.stream().map(geoCatalog::countryLabel).reduce((a, b) -> a + ", " + b).orElse(countryRaw);
        Map<String, Object> geo = new LinkedHashMap<>();
        geo.put("mode", "countries");
        geo.put("country_codes", codes);
        geo.put("continent_id", null);
        geo.put("primary_code", codes.getFirst());
        geo.put("display", display);
        return geo;
    }

    /** Extracts {@code country_codes} from a geo map produced by {@link #resolve}, for callers that
     * need the resolved target geo without re-parsing the raw request fields (e.g. a fallback for
     * macro-scaffold geo-conflict filtering when the query text names no specific country). */
    public static List<String> countryCodesFrom(Object geoValue) {
        if (!(geoValue instanceof Map<?, ?> geo)) {
            return List.of();
        }
        Object raw = geo.get("country_codes");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                out.add(String.valueOf(item).toUpperCase(Locale.ROOT));
            }
        }
        return List.copyOf(out);
    }

    /**
     * True when {@code countryCode} and {@code otherCountryCode} are both known members of
     * {@link #CONTINENTS} but on DIFFERENT continents - e.g. "US" vs any Europe member. Returns
     * false whenever either code is unrecognized (not in the curated member lists), since absence
     * from that short list is not evidence of anything.
     */
    public static boolean isKnownDifferentContinent(String countryCode, String otherCountryCode) {
        String a = COUNTRY_TO_CONTINENT.get(normalizeCode(countryCode));
        String b = COUNTRY_TO_CONTINENT.get(normalizeCode(otherCountryCode));
        return a != null && b != null && !a.equals(b);
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private static Map<String, String> loadCountryToContinent() {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream in = ExploreGeoResolver.class.getResourceAsStream("/explore/geo-catalog.json")) {
            if (in == null) {
                log.warn("explore/geo-catalog.json missing — cross-continent geo-conflict detection disabled");
                return Map.of();
            }
            Map<String, Object> raw = new ObjectMapper().readValue(in, new TypeReference<>() {});
            Object rawCountries = raw.get("all_countries");
            if (rawCountries instanceof List<?> countries) {
                for (Object item : countries) {
                    if (item instanceof Map<?, ?> country) {
                        String code = normalizeCode(String.valueOf(country.get("code")));
                        Object rawContinentId = country.get("continent_id");
                        String continentId = rawContinentId == null ? "" : String.valueOf(rawContinentId).trim();
                        if (!code.isBlank() && !continentId.isBlank() && !"null".equals(continentId)) {
                            out.putIfAbsent(code, continentId);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("explore/geo-catalog.json load failed — cross-continent geo-conflict detection disabled: {}", ex.getMessage());
            return Map.of();
        }
        // Fall back to CONTINENTS' own anchor/member lists for any code the catalog file doesn't
        // cover (defensive - the catalog is expected to be a superset already).
        for (Map.Entry<String, ContinentSpec> entry : CONTINENTS.entrySet()) {
            for (String member : entry.getValue().members()) {
                out.putIfAbsent(member, entry.getKey());
            }
        }
        return Map.copyOf(out);
    }

    private static Map<String, Object> unknownGeo(String display) {
        Map<String, Object> geo = new LinkedHashMap<>();
        geo.put("mode", "unknown");
        geo.put("country_codes", List.of());
        geo.put("continent_id", null);
        geo.put("primary_code", "U2");
        geo.put("display", display);
        return geo;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record ContinentSpec(String labelCs, List<String> members, String anchor) {}
}
