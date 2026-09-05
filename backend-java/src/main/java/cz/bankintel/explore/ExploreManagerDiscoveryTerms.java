package cz.bankintel.explore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogCountryAliasRegistry;
import cz.bankintel.search.CatalogGeoIntent;
import cz.bankintel.search.CatalogQueryIntent;
import cz.bankintel.search.CatalogSourceRegistry;
import cz.bankintel.search.CatalogTextUtils;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deterministic recall helpers for Manager Explorer discovery.
 *
 * <p>Manager questions are decision questions: even when the user asks about a sector (e.g.
 * manufacturing in a foreign country), the analysis still needs a minimum macro / production
 * scaffold (GDP, inflation, unemployment, interest rates, FX, industrial production). The classic
 * deep-search planner often emits only narrow CAPEX/sector phrases that miss IMF/FRED/ECB macro rows
 * and, under foreign-geo source capping, can also drop FRED/ECB entirely.
 *
 * <p>Topic/intent FTS surfaces come from {@code catalog/manager_intent_probes.json} so profitability,
 * trade, debt, etc. keep reserved lane slots and are not crowded out by the macro scaffold.
 *
 * <p>All geo-qualified probes are derived from {@link CatalogGeoIntent} + the country alias
 * registry — never from per-country hardcoding.
 */
public final class ExploreManagerDiscoveryTerms {

    private static final Logger log = LoggerFactory.getLogger(ExploreManagerDiscoveryTerms.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, IntentProbeDef> INTENT_PROBES = loadIntentProbes();

    /**
     * Always injected into manager discovery index probes. Keep this short — lane term caps still
     * apply — and prefer English catalog surfaces that IMF/FRED/Eurostat FTS actually index.
     *
     * <p>{@code Gross domestic product} + {@code nama_10_gdp} come before bare {@code GDP}: the short
     * token alone retrieves hundreds of enrichment {@code *_gdp_share} aliases that are not national
     * accounts GDP and then crowd the preview pool.
     */
    public static final List<String> MACRO_PROBE_TERMS = List.of(
            "Gross domestic product",
            "nama_10_gdp",
            "GDP",
            "inflation",
            "unemployment",
            "interest rate",
            "policy rate",
            "exchange rate",
            "industrial production",
            "manufacturing");

    /**
     * Macro terms reserved in the Manager lane budget (tier 2). Shorter than {@link #MACRO_PROBE_TERMS}
     * so intent probes keep their reserved slots; remaining macro terms may still enter as fill.
     */
    public static final List<String> MACRO_LANE_PROBE_TERMS = List.of(
            "Gross domestic product",
            "nama_10_gdp",
            "inflation",
            "unemployment",
            "interest rate",
            "exchange rate");

    /** Reserved Manager lane slots for intent/topic FTS surfaces. */
    public static final int MANAGER_INTENT_LANE_RESERVE = 4;

    /** Reserved Manager lane slots for core macro scaffold. */
    public static final int MANAGER_MACRO_LANE_RESERVE = 6;

    /**
     * Stable Eurostat dataset codes force-seeded into Manager discovery lanes. These are real API
     * codes (not enrichment aliases) and cover all EU geos in one table — no per-country hardcoding.
     */
    public static final List<String> CORE_EUROSTAT_MACRO_SEEDS = List.of(
            "nama_10_gdp",
            "prc_hicp_manr",
            "une_rt_m",
            "irt_h_ddmr_m");

    /**
     * ECB spot FX (USD/EUR) — intentional Manager scaffold for the price of foreign currency. Prefer
     * {@code SP00} spot series, never GDP-deflator EER variants that previously flooded as false GDP.
     */
    public static final List<String> CORE_ECB_MACRO_SEEDS = List.of("EXR/M.USD.EUR.SP00.A");

    /** FRED mirrors: ECB policy rate + USD/EUR for cost-of-money / FX scaffold. */
    public static final List<String> CORE_FRED_MACRO_SEEDS = List.of("ECBMRRFR", "DEXUSEU");

    /**
     * FRED series force-seeded by {@code manager_intent_probes.json}'s "production" intent
     * ({@code preview_seeds.fred}) that are inherently single-country (US) national statistics
     * with no per-row geo dimension of their own - {@code SearchResultCanonicalMetadataService}
     * has no way to know this. FRED as a whole is deliberately NOT in {@code
     * catalog/geo_scopes.json}'s {@code fixed_source_scopes} (unlike ARAD/ČSÚ), because most of
     * the FRED codes pinned here - {@link #CORE_FRED_MACRO_SEEDS} - are deliberately geo-agnostic
     * EU-rate/FX mirrors, not US statistics; tagging the whole source "US" would wrongly make
     * those get dropped as "foreign" for any non-US query. Confirmed live: IPMAN/INDPRO surfaced
     * as unfiltered "generic backdrop" - no geo-relevance check at all - for a Germany+Italy
     * factory question ("Ma smysl investovat do továrny v Německu nebo Itálii?"), a source-level
     * exception could not have caught this without also breaking ECBMRRFR/DEXUSEU.
     */
    public static final Map<String, String> KNOWN_SINGLE_COUNTRY_FRED_SERIES =
            Map.of("IPMAN", "US", "INDPRO", "US");

    /**
     * Needles used to keep Manager Explorer macro scaffold rows after the semantic gate. Broader than
     * sector match: GDP/inflation/unemployment/rates/FX/industrial production must survive even when
     * the user asked about a narrow product (e.g. semiconductors). Deliberately omits bare
     * {@code manufacturing} so sector manufacturing series still go through normal ranking.
     *
     * <p>FX is matched narrowly ({@code spot} / {@code sp00} / explicit kurz phrases) so GDP-deflator
     * EER titles do not re-enter as scaffold noise.
     */
    private static final List<String> MACRO_SCAFFOLD_NEEDLES = List.of(
            "gross domestic product",
            "nama_10_gdp",
            "gdp",
            "hdp",
            "inflation",
            "inflace",
            "hicp",
            "cpi",
            "unemployment",
            "nezamest",
            "interest rate",
            "policy rate",
            "refinancing",
            "urokov",
            "sazba",
            "euribor",
            "exchange rate",
            "smenny kurz",
            "menovy kurz",
            "usd.eur.sp00",
            "industrial production",
            "prumyslov",
            "production in industry");

    /** Cap how many non-actionable macro scaffold rows Manager discovery may force-keep. */
    public static final int MAX_MANAGER_MACRO_SCAFFOLD = 10;

    /** Cap how many topic/intent rows Manager discovery may force-keep after preview. */
    public static final int MAX_MANAGER_TOPIC_KEEP = 6;

    /** Sources Manager Explorer must keep for non-CZ geos (after dropping CZ-only catalogs). */
    public static final List<String> FOREIGN_MANAGER_CORE_SOURCES = List.of(
            "eurostat", "oecd4", "imf", "fred", "ecb2", "data360", "worldbank");

    public static final Set<String> CZ_ONLY_SOURCES = Set.of("arad", "csu");

    /** Cap geo-qualified production probes so lane term budgets stay usable. */
    private static final int MAX_GEO_PRODUCTION_COUNTRIES = 2;

    private ExploreManagerDiscoveryTerms() {}

    /**
     * Full Manager extra probe list: intent/topic surfaces first, then macro scaffold. Used as
     * {@code extra_index_probe_terms}; the Manager lane assembler re-splits intent vs macro into
     * reserved tiers.
     */
    public static List<String> probeTermsFor(String query) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.addAll(intentProbeTermsFor(query));
        out.addAll(MACRO_PROBE_TERMS);
        return List.copyOf(out);
    }

    /**
     * Narrow NACE C29 / car-specific FTS surfaces. Listed ahead of the "production" intent's generic
     * industrial-production synonyms by {@link #intentProbeTermsFor} whenever the query itself is
     * about cars — {@code MANAGER_INTENT_LANE_RESERVE} is too small to fit both, and the generic
     * dataset stays covered regardless via its own {@code preview_seeds} pin, so nothing is lost by
     * de-prioritizing the generic synonyms for this query.
     */
    private static final List<String> AUTOMOTIVE_PRODUCTION_PROBE_TERMS = List.of(
            "NACE C29", "manufacture of motor vehicles", "new passenger cars", "car registration");

    /** NACE C29 (motor vehicles) siblings of the generic eurostat production/prices/turnover triplet. */
    private static final List<String> AUTOMOTIVE_PRODUCTION_EUROSTAT_SEED_IDS =
            List.of("sts_inpr_m_c29", "sts_inppd_m_c29", "sts_intvd_m_c29");

    private static boolean mentionsAutomotive(String folded) {
        return folded.contains("automobil") || folded.contains("vozidl") || folded.contains("motor vehicle")
                || folded.contains("passenger car") || folded.contains("autovyroba") || folded.contains("autoprumysl")
                || folded.contains("auto vyroba") || folded.contains("automotive");
    }

    /** High-recall EN FTS surfaces for detected query intents (no macro scaffold). */
    public static List<String> intentProbeTermsFor(String query) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        List<String> activeIntents = detectedIntentIds(query);
        String folded = CatalogTextUtils.foldAscii(query == null ? "" : query).toLowerCase(Locale.ROOT);
        if (activeIntents.contains("production") && mentionsAutomotive(folded)) {
            out.addAll(AUTOMOTIVE_PRODUCTION_PROBE_TERMS);
        }
        for (String intentId : activeIntents) {
            IntentProbeDef def = INTENT_PROBES.get(intentId);
            if (def == null) {
                continue;
            }
            out.addAll(def.probes());
        }
        if (activeIntents.contains("production")) {
            for (String countryName : detectedEnglishCountryNames(query)) {
                out.add(countryName + " manufacturing");
                out.add("Production Manufacturing " + countryName);
            }
            // Legacy CZ surface still useful for local catalogs.
            out.add("vyroba");
        }
        return List.copyOf(out);
    }

    /** Core macro surfaces reserved for Manager lane tier 2. */
    public static List<String> macroLaneProbeTermsFor() {
        return MACRO_LANE_PROBE_TERMS;
    }

    /** Intent ids matched for this query (stable order from the JSON file). */
    public static List<String> detectedIntentIds(String query) {
        String folded = CatalogTextUtils.foldAscii(query == null ? "" : query).toLowerCase(Locale.ROOT);
        Set<String> activeGroups = new LinkedHashSet<>(
                CatalogQueryIntent.classifyQueryIntent(query == null ? "" : query).activeGroups());
        LinkedHashSet<String> matched = new LinkedHashSet<>();
        for (Map.Entry<String, IntentProbeDef> entry : INTENT_PROBES.entrySet()) {
            IntentProbeDef def = entry.getValue();
            boolean hit = false;
            for (String group : def.matchGroups()) {
                if (activeGroups.contains(group)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                for (String alias : def.matchAliases()) {
                    String af = CatalogTextUtils.foldAscii(alias).toLowerCase(Locale.ROOT);
                    if (af.length() >= 3 && folded.contains(af)) {
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) {
                matched.add(entry.getKey());
            }
        }
        // Heuristic fallbacks when intent_groups miss production/investment wording.
        if (mentionsProduction(folded) && !matched.contains("production")) {
            matched.add("production");
        }
        if (mentionsInvestment(folded) && !matched.contains("investment")) {
            matched.add("investment");
        }
        if (mentionsProfitability(folded) && !matched.contains("profitability")) {
            matched.add("profitability");
        }
        if (mentionsTrade(folded) && !matched.contains("trade")) {
            matched.add("trade");
        }
        if (mentionsDebt(folded) && !matched.contains("debt")) {
            matched.add("debt");
        }
        if (mentionsRetail(folded) && !matched.contains("retail")) {
            matched.add("retail");
        }
        if (mentionsBankingCapital(folded) && !matched.contains("banking_capital")) {
            matched.add("banking_capital");
        }
        return List.copyOf(matched);
    }

    /** Keep-needles for detected intents (used by topic force-keep). */
    public static List<String> topicKeepNeedlesFor(String query) {
        LinkedHashSet<String> needles = new LinkedHashSet<>();
        for (String intentId : detectedIntentIds(query)) {
            IntentProbeDef def = INTENT_PROBES.get(intentId);
            if (def != null) {
                needles.addAll(def.keepNeedles());
            }
        }
        return List.copyOf(needles);
    }

    /** Exclude needles for detected intents (housing/EGSS noise under manufacturing, etc.). */
    public static List<String> topicExcludeNeedlesFor(String query) {
        LinkedHashSet<String> needles = new LinkedHashSet<>();
        for (String intentId : detectedIntentIds(query)) {
            IntentProbeDef def = INTENT_PROBES.get(intentId);
            if (def != null) {
                needles.addAll(def.excludeNeedles());
            }
        }
        // When production is active, also suppress generic dwelling GFCF that "investment" alone would keep.
        if (detectedIntentIds(query).contains("production")) {
            needles.add("dwellings");
            needles.add("dwelling");
            needles.add("housing");
            needles.add("residential property");
        }
        return List.copyOf(needles);
    }

    /**
     * True when a row conflicts with the active Manager intent (e.g. dwellings/EGSS under a
     * manufacturing question). Real macro scaffold rows are never excluded. Exclude wins over
     * broad topic-keep needles such as bare {@code investment} / {@code gross fixed capital}.
     */
    public static boolean isIntentExcludedRow(String query, Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        if (isMacroScaffoldRow(row) && !isGdpShareProxyRow(row)) {
            return false;
        }
        return rowMatchesAnyNeedle(rowHaystack(row), topicExcludeNeedlesFor(query));
    }

    /**
     * True when a verified-preview row matches the query's topic/intent needles (ROE, trade, …),
     * independent of the brittle semantic topic gate.
     */
    public static boolean isTopicIntentRow(String query, Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        List<String> needles = topicKeepNeedlesFor(query);
        if (needles.isEmpty()) {
            return false;
        }
        // Do not double-count pure macro scaffold as topic keep.
        if (isMacroScaffoldRow(row) && !hasNonMacroTopicSignal(row, needles)) {
            return false;
        }
        String haystack = rowHaystack(row);
        if (haystack.isBlank()) {
            return false;
        }
        if (!rowMatchesKeepNeedles(haystack, needles)) {
            return false;
        }
        // Broad investment keeps must not override production excludes (dwellings, EGSS…).
        if (rowMatchesAnyNeedle(haystack, topicExcludeNeedlesFor(query))) {
            return false;
        }
        return true;
    }

    private static boolean rowMatchesKeepNeedles(String haystack, List<String> needles) {
        for (String needle : needles) {
            String nf = CatalogTextUtils.foldAscii(needle).toLowerCase(Locale.ROOT).trim();
            if (nf.length() < 2) {
                continue;
            }
            if (nf.length() <= 3) {
                if ((" " + haystack + " ").contains(" " + nf + " ")
                        || haystack.startsWith(nf + " ")
                        || haystack.endsWith(" " + nf)
                        || haystack.equals(nf)) {
                    return true;
                }
            } else if (haystack.contains(nf)) {
                return true;
            }
        }
        return false;
    }

    private static boolean rowMatchesAnyNeedle(String haystack, List<String> needles) {
        if (haystack == null || haystack.isBlank() || needles == null || needles.isEmpty()) {
            return false;
        }
        for (String needle : needles) {
            String nf = CatalogTextUtils.foldAscii(needle).toLowerCase(Locale.ROOT).trim();
            if (nf.length() < 3) {
                continue;
            }
            if (haystack.contains(nf)) {
                return true;
            }
        }
        return false;
    }

    public static List<String> normalizedForeignCoreSources() {
        List<String> out = new ArrayList<>();
        for (String source : FOREIGN_MANAGER_CORE_SOURCES) {
            String normalized = CatalogSourceRegistry.normalizeSearchSource(source);
            if (!normalized.isBlank() && !CZ_ONLY_SOURCES.contains(normalized)) {
                out.add(normalized);
            }
        }
        return List.copyOf(out);
    }

    public static boolean isCzOnlySource(String source) {
        String normalized = CatalogSourceRegistry.normalizeSearchSource(source);
        return CZ_ONLY_SOURCES.contains(normalized);
    }

    public static List<String> coreMacroSeedsForSource(String source) {
        String normalized = CatalogSourceRegistry.normalizeSearchSource(source);
        if ("eurostat".equals(normalized)) {
            return CORE_EUROSTAT_MACRO_SEEDS;
        }
        if ("fred".equals(normalized)) {
            return CORE_FRED_MACRO_SEEDS;
        }
        if ("ecb2".equals(normalized) || "ecb".equals(normalized)) {
            return CORE_ECB_MACRO_SEEDS;
        }
        return List.of();
    }

    /**
     * Data-driven preview set_ids for active intents on a given source (mirrors macro seeds, but
     * intent-scoped). Empty when the query has no matching intent seeds for {@code source}.
     */
    public static List<String> intentPreviewSeedsForSource(String query, String source) {
        String normalized = CatalogSourceRegistry.normalizeSearchSource(source);
        if (normalized.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        List<String> activeIntents = detectedIntentIds(query);
        for (String intentId : activeIntents) {
            IntentProbeDef def = INTENT_PROBES.get(intentId);
            if (def == null) {
                continue;
            }
            List<String> seeds = def.previewSeedsBySource().getOrDefault(normalized, List.of());
            for (String seed : seeds) {
                if (seed != null && !seed.isBlank()) {
                    out.add(seed.trim());
                }
            }
        }
        // NACE C29 is a distinct eurostat dataset family from the generic production/prices/turnover
        // triplet above (coreMacroSeedsForSource / this method's generic "production" entry) — pin it
        // only for car-related queries, or every unrelated "production" query would pin an automotive
        // row ahead of what it actually asked for.
        if (activeIntents.contains("production")
                && "eurostat".equals(normalized)
                && mentionsAutomotive(CatalogTextUtils.foldAscii(query == null ? "" : query).toLowerCase(Locale.ROOT))) {
            out.addAll(AUTOMOTIVE_PRODUCTION_EUROSTAT_SEED_IDS);
        }
        return List.copyOf(out);
    }

    /** Macro + intent preview seeds for one source (Manager pin/retain set). */
    public static List<String> managerPinnedSeedsForSource(String query, String source) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.addAll(coreMacroSeedsForSource(source));
        out.addAll(intentPreviewSeedsForSource(query, source));
        return List.copyOf(out);
    }

    /** True when a row was force-seeded as an intent preview candidate. */
    public static boolean isIntentPreviewSeedRow(Map<String, Object> row) {
        return row != null && Boolean.TRUE.equals(row.get("manager_intent_seed"));
    }

    /**
     * True when the row's set_id / dataset matches an intent {@code preview_seeds} entry for the
     * query — even if the {@code manager_intent_seed} flag was dropped by dedupe/scoring.
     */
    public static boolean matchesIntentPreviewSeed(String query, Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        if (isIntentPreviewSeedRow(row)) {
            return true;
        }
        String source = CatalogSourceRegistry.normalizeSearchSource(
                String.valueOf(row.getOrDefault("source_type", row.getOrDefault("source", ""))));
        if (source.isBlank()) {
            return false;
        }
        List<String> seeds = intentPreviewSeedsForSource(query, source);
        if (seeds.isEmpty()) {
            return false;
        }
        return rowIds(row).stream()
                .anyMatch(id -> seeds.stream().anyMatch(seed -> seed != null && seed.equalsIgnoreCase(id)));
    }

    /**
     * True when set_id matches any configured intent preview seed (any intent/source). Used when
     * the query is not available (preview pool pin) or the stamp flag was lost.
     */
    public static boolean isConfiguredIntentPreviewSeedSetId(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        Set<String> ids = rowIds(row);
        if (ids.isEmpty()) {
            return false;
        }
        for (IntentProbeDef def : INTENT_PROBES.values()) {
            for (List<String> seeds : def.previewSeedsBySource().values()) {
                for (String seed : seeds) {
                    if (seed != null && ids.contains(seed.trim().toLowerCase(Locale.ROOT))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Set<String> rowIds(Map<String, Object> row) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String key : List.of("set_id", "dataset", "dataset_id", "_original_set_id")) {
            String value = String.valueOf(row.getOrDefault(key, "")).trim();
            if (!value.isBlank() && !"null".equalsIgnoreCase(value)) {
                ids.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return ids;
    }

    /** True for Manager-pinned rows (core macro or intent preview seed). */
    public static boolean isManagerPinnedSeedRow(Map<String, Object> row) {
        return isCoreMacroSeedRow(row)
                || isIntentPreviewSeedRow(row)
                || isConfiguredIntentPreviewSeedSetId(row)
                || isCoreGdpDatasetRow(row);
    }

    public static boolean isManagerPinnedSeedRow(String query, Map<String, Object> row) {
        return isManagerPinnedSeedRow(row) || matchesIntentPreviewSeed(query, row);
    }

    /**
     * True when a live-preview row is part of the Manager macro scaffold (GDP / inflation /
     * unemployment / industrial production), independent of sector-topic match.
     */
    public static boolean isMacroScaffoldRow(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        String setId = String.valueOf(row.getOrDefault("set_id", "")).toLowerCase(Locale.ROOT);
        String dataset = String.valueOf(row.getOrDefault("dataset", "")).toLowerCase(Locale.ROOT);
        if (dataset.isBlank()) {
            dataset = String.valueOf(row.getOrDefault("dataset_id", "")).toLowerCase(Locale.ROOT);
        }
        String originalSetId = String.valueOf(row.getOrDefault("_original_set_id", "")).toLowerCase(Locale.ROOT);
        String haystack = rowHaystack(row);
        if (haystack.isBlank()) {
            return false;
        }
        if (isCoreMacroSeedRow(row)) {
            return true;
        }
        // FX / EER titles often say "GDP deflators deflated" — that is not a GDP scaffold series.
        boolean fxNoise = setId.startsWith("exr/")
                || haystack.contains("exchange")
                || haystack.contains(" eer ")
                || haystack.contains("effective exch")
                || haystack.contains("deflator");
        // Enrichment aliases like eurostat_egss_output_gdp_share match "gdp" but are not national accounts.
        boolean gdpShareProxy = setId.contains("gdp_share")
                || originalSetId.contains("gdp_share")
                || haystack.contains("gdp share")
                || haystack.contains("gdp_share");
        if ("nama_10_gdp".equals(setId) || "nama_10_gdp".equals(dataset) || haystack.contains("gross domestic product")) {
            return !fxNoise;
        }
        for (String needle : MACRO_SCAFFOLD_NEEDLES) {
            if (!haystack.contains(needle)) {
                continue;
            }
            if (fxNoise && (needle.equals("gdp") || needle.equals("hdp") || needle.equals("gross domestic product"))) {
                continue;
            }
            boolean fxScaffoldNeedle = needle.equals("exchange rate")
                    || needle.contains("kurz")
                    || needle.contains("sp00");
            boolean fxDeflatorNoise = haystack.contains("deflator")
                    || setId.contains("erd0")
                    || haystack.contains("effective exch");
            if (fxScaffoldNeedle && fxDeflatorNoise) {
                continue;
            }
            if (gdpShareProxy && (needle.equals("gdp") || needle.equals("hdp") || needle.contains("domestic"))) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** True for enrichment / share-of-GDP proxies that should lose to real national-accounts GDP. */
    public static boolean isGdpShareProxyRow(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        String setId = String.valueOf(row.getOrDefault("set_id", "")).toLowerCase(Locale.ROOT);
        String original = String.valueOf(row.getOrDefault("_original_set_id", "")).toLowerCase(Locale.ROOT);
        String title = CatalogTextUtils.foldAscii(
                        String.join(
                                " ",
                                String.valueOf(row.getOrDefault("title", "")),
                                String.valueOf(row.getOrDefault("name", ""))))
                .toLowerCase(Locale.ROOT);
        return setId.contains("gdp_share")
                || original.contains("gdp_share")
                || title.contains("gdp share")
                || title.contains("gdp_share");
    }

    /**
     * Existující intenty (viz {@code manager_intent_probes.json}) mapované na jednu z 8 report
     * sekcí, podle OBSAHU řádku - ne podle záměru dotazu jako {@link #detectedIntentIds}. Účel je
     * jiný: {@code detectedIntentIds} rozhoduje, CO hledat; tohle rozhoduje, do KTERÉ sekce
     * reportu už NALEZENÝ řádek patří.
     *
     * <p>{@code banking_capital} (CET1/kapitálová přiměřenost) jde do „Finanční podmínky", ne
     * „Rizika" - je to spíš míra síly bilance než popis toho, co by se mohlo pokazit; hraniční
     * rozhodnutí, jde přehodnotit. {@code production}/{@code investment}/{@code retail} tu
     * záměrně chybí - zůstávají v obecném „Sektor" jako dnes, jsou to jádrová sektorová témata,
     * ne finější podkategorie.
     */
    private static final Map<String, String> INTENT_REPORT_SECTIONS = Map.of(
            "trade", "external_indicators",
            "profitability", "financial_indicators",
            "banking_capital", "financial_indicators",
            "debt", "risk_indicators");

    /**
     * Leading indikátory (PMI, důvěra spotřebitelů/podniků, nové objednávky, předstihový index) -
     * na rozdíl od ostatních sekcí tu není existující intent v {@code manager_intent_probes.json}
     * k namapování, takže needly žijí přímo tady. Anglicky i česky, ve stejném ASCII-folded tvaru
     * jako zbytek souboru ({@link #rowHaystack}).
     */
    private static final List<String> LEADING_INDICATOR_NEEDLES = List.of(
            "purchasing managers",
            "pmi",
            "business confidence",
            "consumer confidence",
            "economic sentiment",
            "leading indicator",
            "new orders",
            "building permits",
            "duvera spotrebitelu",
            "duvera podniku",
            "predstihovy indikator");

    /** Náklady a ceny (PPI, mzdové náklady, jednotkové náklady práce) - viz {@link #LEADING_INDICATOR_NEEDLES}. */
    private static final List<String> COST_INDICATOR_NEEDLES = List.of(
            "producer price",
            "ppi",
            "labour cost",
            "labor cost",
            "unit labour cost",
            "unit labor cost",
            "wage",
            "input price",
            "ceny vyrobcu",
            "mzdove naklady",
            "naklady prace",
            "jednotkove naklady prace");

    /**
     * Jemnější zařazení NEmakro řádku do jedné z 8 kontraktových sekcí. Volá se jen pro řádky, co
     * už prošly makro-scaffold kontrolou jako „ne makro" - nikdy nepřeřazuje makro řádek do jemné
     * sekce, zůstává vlastní, samostatnou kategorií jako dnes. Nezapadne-li řádek nikam, zůstává
     * dosavadní výchozí „sector_indicators".
     */
    public static String reportSectionFor(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return "sector_indicators";
        }
        String haystack = rowHaystack(row);
        if (haystack.isBlank()) {
            return "sector_indicators";
        }
        for (Map.Entry<String, String> entry : INTENT_REPORT_SECTIONS.entrySet()) {
            IntentProbeDef def = INTENT_PROBES.get(entry.getKey());
            if (def != null && rowMatchesKeepNeedles(haystack, def.keepNeedles())) {
                return entry.getValue();
            }
        }
        if (rowMatchesKeepNeedles(haystack, LEADING_INDICATOR_NEEDLES)) {
            return "leading_indicators";
        }
        if (rowMatchesKeepNeedles(haystack, COST_INDICATOR_NEEDLES)) {
            return "cost_indicators";
        }
        return "sector_indicators";
    }

    /** True for any force-seeded Manager core macro dataset (GDP / HICP / unemployment / policy rate). */
    public static boolean isCoreMacroSeedRow(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        String setId = String.valueOf(row.getOrDefault("set_id", "")).trim();
        if (setId.isBlank()) {
            return false;
        }
        String lower = setId.toLowerCase(Locale.ROOT);
        for (String seed : CORE_EUROSTAT_MACRO_SEEDS) {
            if (seed.equalsIgnoreCase(setId)) {
                return true;
            }
        }
        for (String seed : CORE_FRED_MACRO_SEEDS) {
            if (seed.equalsIgnoreCase(setId)) {
                return true;
            }
        }
        for (String seed : CORE_ECB_MACRO_SEEDS) {
            if (seed.equalsIgnoreCase(setId)) {
                return true;
            }
        }
        // Dataset field may hold the parent code while set_id is an enrichment alias.
        String dataset = String.valueOf(row.getOrDefault("dataset", "")).toLowerCase(Locale.ROOT);
        if (dataset.isBlank()) {
            dataset = String.valueOf(row.getOrDefault("dataset_id", "")).toLowerCase(Locale.ROOT);
        }
        for (String seed : CORE_EUROSTAT_MACRO_SEEDS) {
            if (seed.equalsIgnoreCase(dataset)) {
                return true;
            }
        }
        return "nama_10_gdp".equals(lower)
                || "ecbmrrfr".equals(lower)
                || "dexuseu".equals(lower)
                || lower.contains("usd.eur.sp00")
                || lower.contains("mrr_fr.lev");
    }

    /** True for the canonical Eurostat GDP national-accounts dataset. */
    public static boolean isCoreGdpDatasetRow(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        String setId = String.valueOf(row.getOrDefault("set_id", "")).toLowerCase(Locale.ROOT);
        String dataset = String.valueOf(row.getOrDefault("dataset", "")).toLowerCase(Locale.ROOT);
        if (dataset.isBlank()) {
            dataset = String.valueOf(row.getOrDefault("dataset_id", "")).toLowerCase(Locale.ROOT);
        }
        String title = CatalogTextUtils.foldAscii(
                        String.join(
                                " ",
                                String.valueOf(row.getOrDefault("title", "")),
                                String.valueOf(row.getOrDefault("name", ""))))
                .toLowerCase(Locale.ROOT);
        return "nama_10_gdp".equals(setId)
                || "nama_10_gdp".equals(dataset)
                || title.contains("gross domestic product");
    }

    /**
     * English catalog country names for geo-qualified FTS probes, derived from detected ISO codes.
     */
    static List<String> detectedEnglishCountryNames(String query) {
        Map<String, Object> geoIntent = CatalogGeoIntent.detectGeoIntent(query);
        List<String> codes = CatalogGeoIntent.requestedGeoCodes(geoIntent);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String code : codes) {
            if (names.size() >= MAX_GEO_PRODUCTION_COUNTRIES) {
                break;
            }
            String name = englishCountryProbeName(code);
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /**
     * Picks the first non-wildcard alias that looks like an English country noun (registry order is
     * English-first for most European codes).
     */
    static String englishCountryProbeName(String countryCode) {
        for (String alias : CatalogCountryAliasRegistry.aliasesFor(countryCode)) {
            if (alias == null || alias.isBlank() || alias.indexOf('*') >= 0) {
                continue;
            }
            String folded = CatalogTextUtils.foldAscii(alias).toLowerCase(Locale.ROOT).trim();
            if (folded.length() < 4 || !folded.chars().allMatch(ch -> ch >= 'a' && ch <= 'z' || ch == ' ')) {
                continue;
            }
            return capitalizeAsciiWords(folded);
        }
        return "";
    }

    private static String capitalizeAsciiWords(String folded) {
        String[] parts = folded.split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1));
            }
        }
        return out.toString();
    }

    private static boolean mentionsProduction(String folded) {
        return folded.contains("vyrob")
                || folded.contains("manufactur")
                || folded.contains("prumysl")
                || folded.contains("industrial")
                || folded.contains("production")
                || folded.contains("pocitac")
                || folded.contains("automobil");
    }

    private static boolean mentionsInvestment(String folded) {
        return folded.contains("invest")
                || folded.contains("capex")
                || folded.contains("navysit")
                || folded.contains("fixniho kapital")
                || folded.contains("fixed capital");
    }

    private static boolean mentionsProfitability(String folded) {
        return folded.contains("ziskovost")
                || folded.contains("zisk bank")
                || folded.contains("rentabilit")
                || folded.contains("return on equity")
                || folded.contains("return on assets")
                || folded.contains(" roe")
                || folded.startsWith("roe ")
                || folded.contains(" roa")
                || folded.startsWith("roa ");
    }

    private static boolean mentionsTrade(String folded) {
        return folded.contains("obchod")
                || folded.contains("export")
                || folded.contains("import")
                || folded.contains("vyvoz")
                || folded.contains("dovoz")
                || folded.contains("trade balance")
                || folded.contains("foreign trade");
    }

    private static boolean mentionsDebt(String folded) {
        return folded.contains("zadluz")
                || folded.contains("dluh")
                || folded.contains("npl")
                || folded.contains("non-performing")
                || folded.contains("indebt");
    }

    private static boolean mentionsRetail(String folded) {
        return folded.contains("maloobchod")
                || folded.contains("retail sales")
                || folded.contains("retail turnover")
                || folded.contains("volume of retail");
    }

    private static boolean mentionsBankingCapital(String folded) {
        return folded.contains("cet1")
                || folded.contains("capital adequacy")
                || folded.contains("tier 1")
                || folded.contains("tier-1")
                || folded.contains("own funds");
    }

    private static String rowHaystack(Map<String, Object> row) {
        String setId = String.valueOf(row.getOrDefault("set_id", "")).toLowerCase(Locale.ROOT);
        String dataset = String.valueOf(row.getOrDefault("dataset", "")).toLowerCase(Locale.ROOT);
        if (dataset.isBlank()) {
            dataset = String.valueOf(row.getOrDefault("dataset_id", "")).toLowerCase(Locale.ROOT);
        }
        String originalSetId = String.valueOf(row.getOrDefault("_original_set_id", "")).toLowerCase(Locale.ROOT);
        return CatalogTextUtils.foldAscii(
                        String.join(
                                " ",
                                String.valueOf(row.getOrDefault("title", "")),
                                String.valueOf(row.getOrDefault("name", "")),
                                String.valueOf(row.getOrDefault("canonical_title_en", "")),
                                String.valueOf(row.getOrDefault("canonical_title_cs", "")),
                                setId,
                                dataset,
                                originalSetId,
                                String.valueOf(row.getOrDefault("indicator_name", "")),
                                String.valueOf(row.getOrDefault("why_relevant", "")),
                                String.valueOf(row.getOrDefault("full_path", "")),
                                String.valueOf(row.getOrDefault("aliases_en", "")),
                                String.valueOf(row.getOrDefault("abbreviations", ""))))
                .toLowerCase(Locale.ROOT);
    }

    private static boolean hasNonMacroTopicSignal(Map<String, Object> row, List<String> needles) {
        String haystack = rowHaystack(row);
        for (String needle : needles) {
            String nf = CatalogTextUtils.foldAscii(needle).toLowerCase(Locale.ROOT).trim();
            if (nf.length() < 4) {
                continue;
            }
            // Skip needles that are themselves macro scaffold vocabulary.
            if (MACRO_SCAFFOLD_NEEDLES.contains(nf) || "industrial production".equals(nf)) {
                continue;
            }
            if (haystack.contains(nf)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, IntentProbeDef> loadIntentProbes() {
        try (InputStream in = ExploreManagerDiscoveryTerms.class.getResourceAsStream(
                "/catalog/manager_intent_probes.json")) {
            if (in == null) {
                log.warn("manager_intent_probes.json missing — intent probes disabled");
                return Map.of();
            }
            Map<String, Object> root = MAPPER.readValue(in, new TypeReference<>() {});
            Object rawIntents = root.get("intents");
            if (!(rawIntents instanceof Map<?, ?> intentsMap)) {
                return Map.of();
            }
            LinkedHashMap<String, IntentProbeDef> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : intentsMap.entrySet()) {
                String id = String.valueOf(entry.getKey()).trim().toLowerCase(Locale.ROOT);
                if (id.isBlank() || !(entry.getValue() instanceof Map<?, ?> rawDef)) {
                    continue;
                }
                Map<String, Object> def = (Map<String, Object>) rawDef;
                out.put(
                        id,
                        new IntentProbeDef(
                                readStringList(def.get("match_groups")),
                                readStringList(def.get("match_aliases")),
                                readStringList(def.get("probes")),
                                readStringList(def.get("keep_needles")),
                                readStringList(def.get("exclude_needles")),
                                readPreviewSeeds(def.get("preview_seeds"))));
            }
            return Map.copyOf(out);
        } catch (Exception ex) {
            log.warn("manager_intent_probes.json load failed: {}", ex.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> readPreviewSeeds(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String source = CatalogSourceRegistry.normalizeSearchSource(String.valueOf(entry.getKey()));
            if (source.isBlank()) {
                continue;
            }
            List<String> seeds = readStringList(entry.getValue());
            if (!seeds.isEmpty()) {
                out.put(source, seeds);
            }
        }
        return Map.copyOf(out);
    }

    private static List<String> readStringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String text = String.valueOf(item).trim();
            if (!text.isBlank()) {
                out.add(text);
            }
        }
        return List.copyOf(out);
    }

    private record IntentProbeDef(
            List<String> matchGroups,
            List<String> matchAliases,
            List<String> probes,
            List<String> keepNeedles,
            List<String> excludeNeedles,
            Map<String, List<String>> previewSeedsBySource) {}
}
