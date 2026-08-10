package cz.bankintel.explore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.explore.ExploreSectionMeta.SectionDef;
import cz.bankintel.explore.OpenAiWebSearchResponseParser.Parsed;
import cz.bankintel.search.openai.OpenAiClient;
import cz.bankintel.search.openai.OpenAiModelTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExploreSectionedSynthesisService {

    private static final Logger log = LoggerFactory.getLogger(ExploreSectionedSynthesisService.class);
    private static final int SECTION_PARALLELISM = 2;
    private static final int SECTION_ITEM_LIMIT = 14;
    private static final int POLITICAL_SOURCE_LIMIT = 6;
    private static final Set<String> ALWAYS_RUN_SECTIONS =
            Set.of("executive_verdict", "limitations_data_quality", "political_situation");

    private final OpenAiClient openAiClient;
    private final ExploreSectionBucketService bucketService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService sectionExecutor = Executors.newFixedThreadPool(SECTION_PARALLELISM);

    public SynthesisResult synthesize(SynthesisRequest request) throws Exception {
        if (!openAiClient.isConfigured()) {
            throw new IllegalStateException("OpenAI API klíč není nakonfigurovaný");
        }
        Map<String, List<Map<String, Object>>> bySection =
                bucketService.bucketItemsBySection(request.loadedItems(), request.primaryCountryCode());
        List<SectionDef> sections = ExploreSectionMeta.activeSections();
        Map<String, Object> detailMeta = new LinkedHashMap<>();
        detailMeta.put("sectioned_synthesis", true);
        detailMeta.put("completed_sections", new ArrayList<String>());
        detailMeta.put("failed_sections", new ArrayList<String>());
        detailMeta.put("skipped_sections", new ArrayList<String>());
        detailMeta.put("fallback_sections", new ArrayList<String>());
        detailMeta.put("section_errors", new LinkedHashMap<String, String>());
        detailMeta.put("total_sections", sections.size());
        detailMeta.put("final_synthesis_completed", false);

        @SuppressWarnings("unchecked")
        List<String> completed = (List<String>) detailMeta.get("completed_sections");
        @SuppressWarnings("unchecked")
        List<String> skipped = (List<String>) detailMeta.get("skipped_sections");
        @SuppressWarnings("unchecked")
        List<String> failed = (List<String>) detailMeta.get("failed_sections");

        List<CompletableFuture<SectionOutcome>> futures = new ArrayList<>();
        for (SectionDef section : sections) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> runSectionAi(section, bySection, request), sectionExecutor));
        }
        Map<String, SectionOutcome> outcomes = new LinkedHashMap<>();
        for (CompletableFuture<SectionOutcome> future : futures) {
            try {
                SectionOutcome outcome = future.join();
                outcomes.put(outcome.sectionId(), outcome);
                if (outcome.skipped()) {
                    skipped.add(outcome.sectionId());
                } else if (outcome.analysis().isBlank()) {
                    failed.add(outcome.sectionId());
                } else {
                    completed.add(outcome.sectionId());
                }
            } catch (Exception ex) {
                log.warn("section AI task failed: {}", ex.getMessage());
            }
        }

        List<Map<String, Object>> analysisSections = new ArrayList<>();
        Map<String, String> analysisFields = new LinkedHashMap<>();
        StringBuilder digest = new StringBuilder();
        for (SectionDef section : sections) {
            SectionOutcome outcome = outcomes.get(section.id());
            String analysis = outcome != null ? outcome.analysis() : "";
            if (!analysis.isBlank()) {
                analysisFields.put(section.analysisKey(), analysis);
                Map<String, Object> analysisSection = new LinkedHashMap<>();
                analysisSection.put("id", section.id());
                analysisSection.put("title", section.title());
                analysisSection.put("text", analysis);
                analysisSection.put("series_refs", outcome != null ? outcome.seriesRefs() : List.of());
                if (outcome != null && outcome.sourceUrls() != null && !outcome.sourceUrls().isEmpty()) {
                    analysisSection.put("source_urls", outcome.sourceUrls());
                }
                analysisSections.add(analysisSection);
                digest.append("=== ").append(section.title()).append(" ===\n").append(analysis).append("\n\n");
            }
        }

        String finalAnswer = runFinalSynthesis(request, digest.toString().trim());
        detailMeta.put("final_synthesis_completed", !finalAnswer.isBlank());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assistant_answer_cz", finalAnswer);
        result.put("analysis_sections", analysisSections);
        result.put("detail_synthesis_metadata", detailMeta);
        result.putAll(analysisFields);
        return new SynthesisResult(result, detailMeta);
    }

    private SectionOutcome runSectionAi(
            SectionDef section, Map<String, List<Map<String, Object>>> bySection, SynthesisRequest request) {
        if ("political_situation".equals(section.id())) {
            return runPoliticalSituationSection(section, request);
        }
        List<Map<String, Object>> items = sectionItems(section.id(), bySection, request);
        if (items.isEmpty() && !ALWAYS_RUN_SECTIONS.contains(section.id())) {
            return new SectionOutcome(section.id(), "", true, "no_relevant_data", List.of(), List.of());
        }
        if (items.size() > SECTION_ITEM_LIMIT) {
            items = items.subList(0, SECTION_ITEM_LIMIT);
        }
        List<Map<String, Object>> seriesRefs = buildSeriesRefs(items);
        String context = ExploreSectionBucketService.sectionContextBullets(items, 9000);
        if (context.isBlank()) {
            context = "Pro oblast „" + section.title() + "“ nejsou dedikované načtené řady s čísly.";
        }
        String system =
                """
                Jsi senior ekonomický analytik pro manažerská rozhodnutí.
                Analyzuješ JEDNU sekci dat a musíš vrátit JSON v češtině.
                Pole analysis je POVINNÉ — 4 až 8 odrážek, každá na vlastním řádku začíná přesně "- ".
                Žádné dlouhé odstavce, žádný markdown kromě úvodních "- ".
                Do odrážek uveď konkrétní čísla a období z dat.
                Vrať JSON: {"analysis":"..."}
                """;
        String relationshipsDigest = nullSafe(request.relationshipsDigest());
        String user =
                "Sekce: "
                        + section.title()
                        + "\nSektor: "
                        + nullSafe(request.sector())
                        + "\nOtázka: "
                        + nullSafe(request.question())
                        + "\nPrimární země: "
                        + nullSafe(request.primaryCountryCode())
                        + "\nInstrukce: "
                        + section.instruction()
                        + "\n\nData:\n"
                        + context
                        + (relationshipsDigest.isBlank()
                                ? ""
                                : "\n\nSpočítané statistické vztahy (použij, pokud jsou pro tuto sekci relevantní — čísla jsou reálná, ne odhad):\n"
                                        + relationshipsDigest);
        try {
            JsonNode json = openAiClient.chatCompletionJson(system, user, OpenAiModelTask.CHAT);
            String analysis = extractAnalysis(json);
            if (analysis.isBlank()) {
                analysis = deterministicSectionText(section, items, request.question());
                return new SectionOutcome(section.id(), analysis, false, "deterministic_fallback", seriesRefs, List.of());
            }
            return new SectionOutcome(section.id(), analysis, false, null, seriesRefs, List.of());
        } catch (Exception ex) {
            log.debug("section {} AI failed: {}", section.id(), ex.getMessage());
            return new SectionOutcome(
                    section.id(),
                    deterministicSectionText(section, items, request.question()),
                    false,
                    ex.getMessage(),
                    seriesRefs,
                    List.of());
        }
    }

    private SectionOutcome runPoliticalSituationSection(SectionDef section, SynthesisRequest request) {
        String instructions =
                """
                Jsi analytik politického a byznysového rizika. Odpověz česky.
                Použij web_search pro aktuální politickou situaci primární země.
                Výstup: 4–8 odrážek, každá na vlastním řádku začíná přesně "- ".
                Poslední odrážka musí obsahovat krátký verdikt pro byznys záměr: vhodné / rizikové / smíšené.
                Uveď nejistotu. Nevymýšlej časové řady ani falešná čísla z oficiálních statistik.
                """;
        String input =
                "Země (ISO): "
                        + nullSafe(request.primaryCountryCode())
                        + "\nSektor: "
                        + nullSafe(request.sector())
                        + "\nManažerská otázka: "
                        + nullSafe(request.question())
                        + "\nÚkol: "
                        + section.instruction();
        try {
            JsonNode response = openAiClient.webSearch(instructions, input);
            Parsed parsed = OpenAiWebSearchResponseParser.parse(response);
            String analysis = parsed.text();
            List<Map<String, Object>> sourceUrls =
                    OpenAiWebSearchResponseParser.sourceUrlMaps(parsed.sources(), POLITICAL_SOURCE_LIMIT);
            if (analysis.isBlank()) {
                analysis = politicalFallbackText(request);
                return new SectionOutcome(
                        section.id(), analysis, false, "web_search_empty", List.of(), sourceUrls);
            }
            if (!looksLikeBulletText(analysis)) {
                analysis = toBulletLines(analysis);
            }
            return new SectionOutcome(section.id(), analysis, false, null, List.of(), sourceUrls);
        } catch (Exception ex) {
            log.debug("political_situation web_search failed: {}", ex.getMessage());
            return new SectionOutcome(
                    section.id(), politicalFallbackText(request), false, ex.getMessage(), List.of(), List.of());
        }
    }

    private List<Map<String, Object>> sectionItems(
            String sectionId, Map<String, List<Map<String, Object>>> bySection, SynthesisRequest request) {
        if ("executive_verdict".equals(sectionId)) {
            return request.loadedItems();
        }
        if ("limitations_data_quality".equals(sectionId)) {
            return request.loadedItems();
        }
        if ("political_situation".equals(sectionId)) {
            return List.of();
        }
        return bucketService.reportSectionItems(bySection, sectionId, request.primaryCountryCode());
    }

    private static List<Map<String, Object>> buildSeriesRefs(List<Map<String, Object>> items) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> ref = new LinkedHashMap<>();
            for (String key : List.of("title", "source_type", "set_id", "dataset_id", "series_id", "query_params")) {
                if (item.containsKey(key) && item.get(key) != null) {
                    ref.put(key, item.get(key));
                }
            }
            if (!ref.isEmpty()) refs.add(ref);
        }
        return refs;
    }

    private String runFinalSynthesis(SynthesisRequest request, String sectionDigest) throws Exception {
        String system =
                """
                Jsi český ekonomický analytik pro manažerský Explorer.
                Na základě sekčních analýz napiš finální verdikt pro manažera.
                Odpověz česky v 4–8 odrážkách: každá na vlastním řádku začíná přesně "- ".
                Žádné souvislé odstavce, žádný markdown kromě úvodních "- ".
                """;
        String relationshipsDigest = nullSafe(request.relationshipsDigest());
        String user =
                "Dotaz: "
                        + nullSafe(request.question())
                        + "\nSegment: "
                        + nullSafe(request.sector())
                        + "\nZemě: "
                        + nullSafe(request.primaryCountryCode())
                        + "\n\nSekční analýzy:\n"
                        + (sectionDigest.isBlank() ? "(bez sekčních analýz)" : sectionDigest)
                        + (relationshipsDigest.isBlank()
                                ? ""
                                : "\n\nSpočítané statistické vztahy (reálná čísla, cituj je pokud jsou relevantní pro verdikt):\n"
                                        + relationshipsDigest);
        JsonNode response = openAiClient.chatCompletion(system, user, OpenAiModelTask.CHAT);
        String answer = response.path("choices").path(0).path("message").path("content").asText("").trim();
        if (!answer.isBlank() && !looksLikeBulletText(answer)) {
            return toBulletLines(answer);
        }
        return answer;
    }

    private static String deterministicSectionText(
            SectionDef section, List<Map<String, Object>> items, String question) {
        if (items.isEmpty()) {
            return "- Pro oblast „" + section.title() + "“ nejsou v tomto běhu načtená numerická data.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("- Oblast „").append(section.title()).append("“");
        if (!nullSafe(question).isBlank()) {
            sb.append(" k otázce „").append(question.trim()).append("“");
        }
        sb.append(" — dostupné signály:");
        int count = 0;
        for (Map<String, Object> item : items) {
            if (count >= 4) {
                break;
            }
            String line = str(item.get("data_context_line"));
            if (!line.isBlank()) {
                sb.append("\n- ").append(line);
                count++;
            }
        }
        return sb.toString().trim();
    }

    private static String politicalFallbackText(SynthesisRequest request) {
        String country = nullSafe(request.primaryCountryCode());
        String countryBit = country.isBlank() ? "primární země" : country;
        return "- Politický kontext pro "
                + countryBit
                + " nelze v tomto běhu ověřit z webu.\n"
                + "- Verdikt pro byznys záměr: smíšené — rozhodování doplňte o aktuální politický screening z důvěryhodných zdrojů.";
    }

    /** True when text already contains bullet-style lines. */
    static boolean looksLikeBulletText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int bullets = 0;
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ") || trimmed.startsWith("• ") || trimmed.startsWith("* ")) {
                bullets++;
            }
        }
        return bullets >= 2;
    }

    static String toBulletLines(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] parts = text.split("(?<=[.!?])\\s+|\\R+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("• ") || trimmed.startsWith("* ")) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(trimmed.startsWith("- ") ? trimmed : "- " + trimmed.substring(2).trim());
            } else {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append("- ").append(trimmed);
            }
        }
        return sb.toString().trim();
    }

    private String extractAnalysis(JsonNode json) {
        if (json == null) {
            return "";
        }
        if (json.has("analysis")) {
            return json.path("analysis").asText("").trim();
        }
        JsonNode content = json.path("choices").path(0).path("message").path("content");
        if (content.isTextual()) {
            try {
                JsonNode parsed = objectMapper.readTree(content.asText());
                return parsed.path("analysis").asText("").trim();
            } catch (Exception ignored) {
                return content.asText("").trim();
            }
        }
        return "";
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }

    public record SynthesisRequest(
            String question,
            String sector,
            String primaryCountryCode,
            List<Map<String, Object>> loadedItems,
            String relationshipsDigest) {
        public SynthesisRequest(
                String question, String sector, String primaryCountryCode, List<Map<String, Object>> loadedItems) {
            this(question, sector, primaryCountryCode, loadedItems, "");
        }
    }

    public record SynthesisResult(Map<String, Object> payload, Map<String, Object> detailSynthesisMetadata) {}

    private record SectionOutcome(
            String sectionId,
            String analysis,
            boolean skipped,
            String errorReason,
            List<Map<String, Object>> seriesRefs,
            List<Map<String, Object>> sourceUrls) {}
}
