package cz.bankintel.explore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogGeoIntent;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.openai.OpenAiModelTask;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExploreQueryUnderstandingService {

    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;
    private final ExploreGeoCatalog geoCatalog;

    public Map<String, Object> understand(
            String question, String sector, String country, String geoMode, String continent, boolean queryOnly) {
        String q = question == null ? "" : question.trim();
        String sec = sector == null ? "" : sector.trim();
        boolean explicitGeo = hasExplicitGeo(country, geoMode, continent);
        if (!queryOnly || q.isBlank() || (!sec.isBlank() && explicitGeo)) {
            return explicitInputsUnderstanding(sec, country, geoMode, continent, q);
        }
        try {
            String system =
                    """
                    Jsi parser dotazů pro manažerský Explorer. Vrať pouze JSON objekt s klíči:
                    sector (cs název segmentu; pole názvů, pokud dotaz srovnává více segmentů, např.
                    "stavebnictví nebo autovýroba"), sector_id (id z katalogu nebo null),
                    country (ISO2 kód, nebo pole ISO2 kódů, pokud dotaz zmiňuje více zemí, např.
                    "Německo nebo Itálii" → ["DE","IT"]),
                    geo_mode (none|countries|continent), continent (id kontinentu nebo null),
                    primary_segment, segment_id, confidence (0-1), intent_summary (cs).

                    Dostupné segmenty katalogu (sector_id — český název):
                    """
                            + segmentMenu()
                            + """

                    Uživatel často použije obecné nebo hovorové slovo místo přesného názvu segmentu
                    (např. "továrna", "závod", "fabrika", "provoz" typicky znamenají obecný výrobní
                    podnik → sector_id "manufacturing_general"; "banka" → "banking_finance"; "obchod"
                    nebo "prodejna" → "retail_consumer"). VŽDY se snaž najít nejbližší odpovídající
                    segment ZE SEZNAMU výše, i pro takové obecné výrazy - sector_id nech null jen
                    pokud dotaz opravdu nemá žádný odvětvový rozměr (čistě makroekonomický dotaz jako
                    "jak se vyvíjí HDP"). sector = český název (label_cs) vybraného segmentu ze
                    seznamu - ne tvůj vlastní výmysl.
                    """;
            JsonNode response = openAiClient.chatCompletion(system, q, OpenAiModelTask.PLANNER);
            String content = extractContent(response);
            Map<String, Object> parsed = objectMapper.readValue(content, new TypeReference<>() {});
            return normalizeUnderstanding(parsed, q);
        } catch (Exception ex) {
            return fallbackUnderstanding(sec, country, geoMode, continent, q, ex.getMessage());
        }
    }

    private Map<String, Object> explicitInputsUnderstanding(
            String sector, String country, String geoMode, String continent, String question) {
        Map<String, Object> preset = geoCatalog.findSectorByIdOrLabel(sector);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("raw_query", question);
        out.put("sector", sector);
        out.put("sector_id", preset.getOrDefault("id", null));
        out.put("primary_segment", preset.getOrDefault("id", sector));
        out.put("segment_id", preset.getOrDefault("id", null));
        out.put("country", country);
        out.put("geo_mode", geoMode == null || geoMode.isBlank() ? "none" : geoMode);
        out.put("continent", continent);
        out.put("confidence", sector.isBlank() ? 0.3 : 0.9);
        out.put("intent_summary", question.isBlank() ? "Explicitní výběr segmentu a geografie." : question);
        out.put("source", "explicit_inputs");
        return out;
    }

    private Map<String, Object> fallbackUnderstanding(
            String sector, String country, String geoMode, String continent, String question, String reason) {
        Map<String, Object> out = explicitInputsUnderstanding(sector, country, geoMode, continent, question);
        out.put("source", "fallback");
        out.put("fallback_reason", reason);
        return out;
    }

    private Map<String, Object> normalizeUnderstanding(Map<String, Object> parsed, String question) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("raw_query", question);
        // Kept raw (not string-coerced) - the LLM returns an array for a multi-segment question
        // ("stavebnictví nebo autovýroba"), and ExploreSectorService needs the real list to fan
        // discovery out per-segment, not a display string it would have to re-parse.
        out.put("sector", parsed.get("sector"));
        out.put("sector_id", parsed.get("sector_id"));
        out.put("primary_segment", parsed.getOrDefault("primary_segment", parsed.get("sector_id")));
        out.put("segment_id", parsed.getOrDefault("segment_id", parsed.get("sector_id")));
        out.put("country", reconcileCountry(parsed.get("country"), question));
        out.put("geo_mode", parsed.getOrDefault("geo_mode", "none"));
        out.put("continent", parsed.get("continent"));
        out.put("confidence", parsed.getOrDefault("confidence", 0.6));
        out.put("intent_summary", parsed.getOrDefault("intent_summary", question));
        out.put("source", "openai");
        return out;
    }

    /**
     * The LLM occasionally hallucinates a plausible-looking but WRONG ISO2 code - confirmed live:
     * "Jak se vyvíjí cestovní ruch v Řecku nebo Turecku?" came back with {@code country=["RE","TR"]}
     * - "RE" is the real ISO2 code for Réunion, not Greece ("GR"). {@link CatalogGeoIntent}'s alias
     * registry (used pervasively elsewhere in this codebase for the exact same Czech/English
     * country-name detection - {@code world_country_aliases.json} already correctly maps
     * "recko"→GR) is deterministic and proven; when it confidently finds a specific country set
     * directly in the question's own text, that is more trustworthy than the LLM's free-form guess
     * and takes priority over it.
     */
    static Object reconcileCountry(Object llmCountry, String question) {
        List<String> detected = CatalogGeoIntent.requestedGeoCodes(CatalogGeoIntent.detectGeoIntent(question));
        if (detected.isEmpty()) {
            return llmCountry;
        }
        return detected.size() == 1 ? detected.get(0) : detected;
    }

    /** Builds the "id — label_cs" menu once per call from {@link ExploreGeoCatalog}'s loaded
     * segments (~25 entries, cheap to rebuild) - kept as plain text rather than JSON so it reads
     * naturally inside the system prompt. */
    private String segmentMenu() {
        StringBuilder menu = new StringBuilder();
        for (Map<String, Object> sector : geoCatalog.managerSectors()) {
            String id = String.valueOf(sector.getOrDefault("id", "")).trim();
            String label = String.valueOf(sector.getOrDefault("label_cs", "")).trim();
            if (id.isBlank() || label.isBlank()) {
                continue;
            }
            menu.append(id).append(" — ").append(label).append('\n');
        }
        return menu.toString();
    }

    private static boolean hasExplicitGeo(String country, String geoMode, String continent) {
        if (country != null && !country.isBlank()) {
            return true;
        }
        if (geoMode != null && !geoMode.isBlank() && !"none".equalsIgnoreCase(geoMode)) {
            return true;
        }
        return continent != null && !continent.isBlank();
    }

    private static String extractContent(JsonNode response) throws Exception {
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new IllegalStateException("OpenAI returned empty content");
        }
        String text = content.asText().trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start >= 0 && end > start) {
                text = text.substring(start + 1, end).trim();
            }
        }
        return text;
    }

}
