package cz.bankintel.search.v2.sidecar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.AradSeriesIdentity;
import cz.bankintel.search.CatalogGeoIntent;
import cz.bankintel.search.CatalogSourceRegistry;
import cz.bankintel.search.CatalogTextUtils;
import cz.bankintel.search.model.CatalogMapSupport;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SearchCatalogSidecarBuilder {

    public static final String ENRICHMENT_VERSION = "sidecar-v2-reporter-sector-evidence-2026-07-23";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private List<ConceptRule> conceptRules = List.of();
    private Map<String, List<ConceptRule>> conceptRulesByAlias = Map.of();
    private Map<String, String> ecbCbd2ItemLabels = Map.of();

    public SearchCatalogSidecarBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String enrichmentVersion() {
        return ENRICHMENT_VERSION;
    }

    @PostConstruct
    void loadTaxonomy() {
        this.conceptRules = readRules();
        this.conceptRulesByAlias = indexRulesByAlias(this.conceptRules);
        this.ecbCbd2ItemLabels = readEcbCbd2ItemLabels();
    }

    public SearchCatalogSidecarDocument build(Map<String, Object> rawRow) {
        Map<String, Object> raw = enrichEcbMetadata(rawRow);
        String source = CatalogSourceRegistry.normalizeSearchSource(
                CatalogMapSupport.firstNonBlank(raw.get("source"), raw.get("source_type"), raw.get("catalog_id")));
        String seriesId = resolveSeriesId(raw, source);
        String dataset = CatalogMapSupport.firstNonBlank(
                raw.get("dataset"), raw.get("dataset_id"), raw.get("dataset_code"), raw.get("flow"), raw.get("table"));
        String originalTitle = clean(CatalogMapSupport.firstNonBlank(
                raw.get("title_original"), raw.get("title"), raw.get("name"), raw.get("indicator_name"), raw.get("dataset_name")));
        String originalDescription = clean(CatalogMapSupport.firstNonBlank(
                raw.get("description"),
                raw.get("description_cs"),
                raw.get("description_en"),
                raw.get("full_path"),
                raw.get("tree_path"),
                raw.get("search_blob")));
        String canonicalTitleCs = clean(CatalogMapSupport.firstNonBlank(
                raw.get("human_label_cs"), raw.get("label_cs"), raw.get("title_cs"), raw.get("name_cs")));
        String canonicalTitleEn = clean(CatalogMapSupport.firstNonBlank(
                raw.get("human_label_en"), raw.get("label_en"), raw.get("title_en"), raw.get("name_en")));
        if (canonicalTitleCs.isBlank()) {
            canonicalTitleCs = originalTitle;
        }
        canonicalTitleCs = repairContradictingGeneratedTitle(source, canonicalTitleCs, originalTitle);
        if (canonicalTitleEn.isBlank()) {
            canonicalTitleEn = originalTitle;
        }
        String canonicalDescriptionCs = clean(CatalogMapSupport.firstNonBlank(raw.get("description_cs"), raw.get("long_description_cs")));
        String canonicalDescriptionEn = clean(CatalogMapSupport.firstNonBlank(raw.get("description_en"), raw.get("long_description_en")));
        String conceptEvidenceText = metadataEvidenceText(
                source,
                seriesId,
                dataset,
                originalTitle,
                originalDescription,
                canonicalTitleCs,
                canonicalTitleEn,
                canonicalDescriptionCs,
                canonicalDescriptionEn);
        String structuralEvidenceText = metadataEvidenceText(
                source,
                seriesId,
                dataset,
                originalTitle,
                originalDescription);

        List<String> aliasesCs = distinct(concat(
                stringList(raw.get("search_keywords_cs")),
                stringList(raw.get("aliases_cs")),
                List.of(canonicalTitleCs)));
        List<String> aliasesEn = distinct(concat(
                stringList(raw.get("search_keywords_en")),
                stringList(raw.get("aliases_en")),
                List.of(canonicalTitleEn)));
        // Generated catalog labels are hints, not evidence. Validate them before
        // concept selection so a stale label cannot manufacture a false concept.
        aliasesCs = sanitizeAliasesForEvidence(aliasesCs, structuralEvidenceText);
        aliasesEn = sanitizeAliasesForEvidence(aliasesEn, structuralEvidenceText);
        List<String> abbreviations = distinct(concat(
                stringList(raw.get("abbreviations")),
                abbreviationsFromText(String.join(" ", originalTitle, canonicalTitleCs, canonicalTitleEn, dataset, seriesId))));
        List<String> negativeConcepts = distinct(concat(
                stringList(raw.get("negative_keywords")),
                stringList(raw.get("negative_concepts"))));

        List<String> existingTags = distinct(concat(
                stringList(raw.get("intent_tags")),
                stringList(raw.get("domain_tags")),
                stringList(raw.get("metric_tags")),
                stringList(raw.get("concepts")),
                stringList(raw.get("tags"))));
        ConceptRule selected = selectConcept(aliasesCs, aliasesEn, existingTags, conceptEvidenceText);
        String primaryConcept = selected != null
                ? selected.id()
                : firstNonBlank(existingTags.stream().findFirst().orElse(""), normalizeConcept(dataset), normalizeConcept(originalTitle));
        if (selected != null) {
            aliasesCs = distinct(concat(aliasesCs, selected.aliasesCs()));
            aliasesEn = distinct(concat(aliasesEn, selected.aliasesEn()));
            abbreviations = distinct(concat(abbreviations, selected.abbreviations()));
            negativeConcepts = distinct(concat(negativeConcepts, selected.negativeConcepts()));
        }

        List<String> secondaryConcepts = distinct(concat(
                selected == null ? List.of() : selected.secondaryConcepts(),
                existingTags));
        String geo = firstNonBlank(
                CatalogGeoIntent.extractRowCountryCode(raw),
                CatalogMapSupport.firstNonBlank(raw.get("geo"), raw.get("geo_code"), raw.get("REF_AREA"), raw.get("country"), raw.get("territory")));
        String frequency = clean(CatalogMapSupport.firstNonBlank(
                raw.get("frequency"), raw.get("freq"), raw.get("FREQ"), raw.get("period")));
        raw = SearchSeriesLifecycleClassifier.enrich(raw, frequency);
        String unit = clean(CatalogMapSupport.firstNonBlank(
                raw.get("unit"), raw.get("unit_label"), raw.get("UNIT_MEASURE"), raw.get("measure")));
        String seasonalAdjustment = clean(CatalogMapSupport.firstNonBlank(
                raw.get("seasonal_adjustment"), raw.get("adjustment"), raw.get("s_adj"), raw.get("S_ADJ")));

        String measureType = firstNonBlank(raw, selected, "measure_type");
        String economicObject = firstNonBlank(raw, selected, "economic_object");
        String institutionalSector = resolveInstitutionalSector(
                raw, selected, originalTitle, originalDescription, structuralEvidenceText);
        String counterpartSector = firstNonBlank(raw, selected, "counterpart_sector");
        String instrument = firstNonBlank(raw, selected, "instrument");
        String priceType = firstNonBlank(raw, selected, "price_type");
        String flowStock = firstNonBlank(raw, selected, "flow_stock");
        String industrySector = firstNonBlank(raw, selected, "industry_sector");
        String nominalReal = firstNonBlank(raw, selected, "nominal_real");
        String scope = firstNonBlank(raw, selected, "scope");
        String priceBasis = clean(CatalogMapSupport.firstNonBlank(raw.get("price_basis"), raw.get("price_base")));
        String datasetFamily = firstNonBlank(raw, selected, "dataset_family");
        String catalogFamily = firstNonBlank(raw, selected, "catalog_family");
        String semanticText = conceptEvidenceText;
        measureType = firstNonBlank(deriveMeasureType(source, semanticText), measureType);
        economicObject = firstNonBlank(deriveEconomicObject(semanticText), economicObject);
        instrument = firstNonBlank(deriveInstrument(source, semanticText), instrument);
        priceType = firstNonBlank(derivePriceType(source, semanticText), priceType);
        flowStock = firstNonBlank(deriveFlowStock(semanticText), flowStock);
        industrySector = firstNonBlank(deriveIndustrySector(semanticText), industrySector);
        nominalReal = firstNonBlank(deriveNominalReal(semanticText), nominalReal);
        datasetFamily = firstNonBlank(deriveDatasetFamily(source, dataset, semanticText), datasetFamily);
        catalogFamily = firstNonBlank(
                catalogFamily,
                deriveCatalogFamily(
                        source, primaryConcept, measureType, economicObject, instrument, industrySector, semanticText));
        primaryConcept = refinePrimaryConcept(primaryConcept, measureType, economicObject, industrySector, instrument, catalogFamily);
        MetadataSanity sanity = sanitizeMetadata(
                source,
                semanticText,
                primaryConcept,
                measureType,
                economicObject,
                instrument,
                catalogFamily);
        primaryConcept = sanity.primaryConcept();
        measureType = sanity.measureType();
        economicObject = sanity.economicObject();
        instrument = sanity.instrument();
        catalogFamily = sanity.catalogFamily();
        aliasesCs = sanitizeAliasesForEvidence(aliasesCs, structuralEvidenceText);
        aliasesEn = sanitizeAliasesForEvidence(aliasesEn, structuralEvidenceText);

        String searchTextCs = searchText(
                source,
                dataset,
                canonicalTitleCs,
                canonicalDescriptionCs,
                originalTitle,
                originalDescription,
                primaryConcept,
                secondaryConcepts,
                aliasesCs,
                abbreviations,
                structuredMetadata(measureType, economicObject, institutionalSector, counterpartSector, instrument, priceType,
                        flowStock, industrySector, nominalReal, scope, datasetFamily, catalogFamily),
                geo,
                unit,
                frequency);
        String searchTextEn = searchText(
                source,
                dataset,
                canonicalTitleEn,
                canonicalDescriptionEn,
                originalTitle,
                originalDescription,
                primaryConcept,
                secondaryConcepts,
                aliasesEn,
                abbreviations,
                structuredMetadata(measureType, economicObject, institutionalSector, counterpartSector, instrument, priceType,
                        flowStock, industrySector, nominalReal, scope, datasetFamily, catalogFamily),
                geo,
                unit,
                frequency);
        double quality = qualityScore(canonicalTitleCs, canonicalTitleEn, originalDescription, geo, unit, frequency, primaryConcept);
        return new SearchCatalogSidecarDocument(
                seriesId,
                source,
                dataset,
                originalTitle,
                originalDescription,
                canonicalTitleCs,
                canonicalTitleEn,
                canonicalDescriptionCs,
                canonicalDescriptionEn,
                primaryConcept,
                secondaryConcepts,
                measureType,
                economicObject,
                institutionalSector,
                counterpartSector,
                instrument,
                priceType,
                flowStock,
                industrySector,
                nominalReal,
                scope,
                geo,
                frequency,
                unit,
                seasonalAdjustment,
                priceBasis,
                datasetFamily,
                catalogFamily,
                aliasesCs,
                aliasesEn,
                abbreviations,
                negativeConcepts,
                quality,
                ENRICHMENT_VERSION,
                raw.containsKey("generated_by") ? "deterministic+existing_sidecar" : "deterministic",
                Instant.now().toString(),
                searchTextCs,
                searchTextEn,
                raw);
    }

    String rawSeriesId(Map<String, Object> rawRow) {
        Map<String, Object> raw = rawRow == null ? Map.of() : rawRow;
        String source = CatalogSourceRegistry.normalizeSearchSource(
                CatalogMapSupport.firstNonBlank(raw.get("source"), raw.get("source_type"), raw.get("catalog_id")));
        return resolveSeriesId(raw, source);
    }

    /**
     * Builds a lightweight raw-catalog document while retaining taxonomy classification.
     *
     * <p>Large mirrors use this path, so it intentionally skips the deeper metadata derivations in
     * {@link #build(Map)}. Concept selection still uses the same registry and evidence gate; raw rows
     * therefore cannot disappear from semantic reranking merely because no sidecar metadata row exists.
     */
    SearchCatalogSidecarDocument buildRaw(Map<String, Object> rawRow) {
        Map<String, Object> raw = enrichEcbMetadata(rawRow);
        String source = CatalogSourceRegistry.normalizeSearchSource(
                CatalogMapSupport.firstNonBlank(raw.get("source"), raw.get("source_type"), raw.get("catalog_id")));
        String seriesId = resolveSeriesId(raw, source);
        String dataset = CatalogMapSupport.firstNonBlank(
                raw.get("dataset"), raw.get("dataset_id"), raw.get("dataset_code"), raw.get("flow"), raw.get("table"));
        String title = clean(CatalogMapSupport.firstNonBlank(
                raw.get("title_original"),
                raw.get("title"),
                raw.get("name"),
                raw.get("indicator_name"),
                raw.get("dataset_name")));
        String description = clean(CatalogMapSupport.firstNonBlank(
                raw.get("description"),
                raw.get("description_cs"),
                raw.get("description_en"),
                raw.get("full_path"),
                raw.get("tree_path"),
                raw.get("search_blob")));
        List<String> existingTags = distinct(concat(
                stringList(raw.get("intent_tags")),
                stringList(raw.get("domain_tags")),
                stringList(raw.get("metric_tags")),
                stringList(raw.get("concepts")),
                stringList(raw.get("tags"))));
        List<String> aliasesCs = distinct(concat(
                stringList(raw.get("search_keywords_cs")),
                stringList(raw.get("aliases_cs")),
                List.of(title)));
        List<String> aliasesEn = distinct(concat(
                stringList(raw.get("search_keywords_en")),
                stringList(raw.get("aliases_en")),
                List.of(title)));
        String evidenceText = metadataEvidenceText(source, seriesId, dataset, title, description);
        ConceptRule selected = selectConcept(aliasesCs, aliasesEn, existingTags, evidenceText);
        String primaryConcept = selected == null
                ? clean(CatalogMapSupport.firstNonBlank(
                        raw.get("primary_concept"), raw.get("concept_id"), raw.get("measure_type")))
                : selected.id();
        List<String> abbreviations = distinct(concat(
                stringList(raw.get("abbreviations")),
                selected == null ? List.of() : selected.abbreviations()));
        List<String> negativeConcepts = distinct(concat(
                stringList(raw.get("negative_keywords")),
                stringList(raw.get("negative_concepts")),
                selected == null ? List.of() : selected.negativeConcepts()));
        if (selected != null) {
            aliasesCs = distinct(concat(aliasesCs, selected.aliasesCs()));
            aliasesEn = distinct(concat(aliasesEn, selected.aliasesEn()));
        }
        List<String> secondaryConcepts = distinct(concat(
                selected == null ? List.of() : selected.secondaryConcepts(), existingTags));
        String geo = firstNonBlank(
                CatalogGeoIntent.extractRowCountryCode(raw),
                CatalogMapSupport.firstNonBlank(
                        raw.get("geo"), raw.get("geo_code"), raw.get("REF_AREA"), raw.get("country"), raw.get("territory")));
        String frequency = clean(CatalogMapSupport.firstNonBlank(
                raw.get("frequency"), raw.get("freq"), raw.get("FREQ"), raw.get("period"), raw.get("frequency_name")));
        raw = SearchSeriesLifecycleClassifier.enrich(raw, frequency);
        String unit = clean(CatalogMapSupport.firstNonBlank(
                raw.get("unit"), raw.get("unit_label"), raw.get("UNIT_MEASURE"), raw.get("measure")));
        String measureType = firstNonBlank(raw, selected, "measure_type");
        String economicObject = firstNonBlank(raw, selected, "economic_object");
        String institutionalSector = resolveInstitutionalSector(raw, selected, title, description, evidenceText);
        String counterpartSector = firstNonBlank(raw, selected, "counterpart_sector");
        String instrument = firstNonBlank(raw, selected, "instrument");
        String priceType = firstNonBlank(raw, selected, "price_type");
        String flowStock = firstNonBlank(raw, selected, "flow_stock");
        String industrySector = firstNonBlank(raw, selected, "industry_sector");
        String nominalReal = firstNonBlank(raw, selected, "nominal_real");
        String scope = firstNonBlank(raw, selected, "scope");
        String datasetFamily = firstNonBlank(raw, selected, "dataset_family");
        String catalogFamily = firstNonBlank(raw, selected, "catalog_family");
        List<String> structured = structuredMetadata(
                measureType,
                economicObject,
                institutionalSector,
                counterpartSector,
                instrument,
                priceType,
                flowStock,
                industrySector,
                nominalReal,
                scope,
                datasetFamily,
                catalogFamily);
        String searchTextCs = searchText(
                source,
                dataset,
                title,
                "",
                title,
                description,
                primaryConcept,
                secondaryConcepts,
                aliasesCs,
                abbreviations,
                structured,
                geo,
                unit,
                frequency);
        String searchTextEn = searchText(
                source,
                dataset,
                title,
                "",
                title,
                description,
                primaryConcept,
                secondaryConcepts,
                aliasesEn,
                abbreviations,
                structured,
                geo,
                unit,
                frequency);
        double quality = qualityScore(title, title, description, geo, unit, frequency, primaryConcept);
        return new SearchCatalogSidecarDocument(
                seriesId,
                source,
                dataset,
                title,
                description,
                title,
                title,
                "",
                "",
                primaryConcept,
                secondaryConcepts,
                measureType,
                economicObject,
                institutionalSector,
                counterpartSector,
                instrument,
                priceType,
                flowStock,
                industrySector,
                nominalReal,
                scope,
                geo,
                frequency,
                unit,
                clean(CatalogMapSupport.firstNonBlank(raw.get("seasonal_adjustment"), raw.get("adjustment"))),
                clean(CatalogMapSupport.firstNonBlank(raw.get("price_basis"), raw.get("price_base"))),
                datasetFamily,
                catalogFamily,
                aliasesCs,
                aliasesEn,
                abbreviations,
                negativeConcepts,
                quality,
                ENRICHMENT_VERSION,
                "raw_catalog+taxonomy",
                Instant.now().toString(),
                searchTextCs,
                searchTextEn,
                raw);
    }

    private static String resolveSeriesId(Map<String, Object> raw, String source) {
        String sourceSpecificSeriesId = "arad".equals(source) ? AradSeriesIdentity.fromRow(raw) : "";
        return CatalogMapSupport.firstNonBlank(
                sourceSpecificSeriesId,
                raw.get("series_id"),
                raw.get("set_id"),
                raw.get("id"),
                raw.get("key"),
                raw.get("dataset_id"));
    }

    public List<SearchCatalogSidecarDocument> buildAll(List<Map<String, Object>> rows) {
        return (rows == null ? List.<Map<String, Object>>of() : rows).stream()
                .map(this::build)
                .filter(doc -> !doc.seriesId().isBlank() && !doc.source().isBlank())
                .toList();
    }

    private ConceptRule selectConcept(
            List<String> aliasesCs,
            List<String> aliasesEn,
            List<String> existingTags,
            String evidenceText) {
        List<String> parts = new ArrayList<>();
        parts.add(evidenceText);
        parts.addAll(aliasesCs);
        parts.addAll(aliasesEn);
        parts.addAll(existingTags);
        String text = folded(String.join(" ", parts));
        String evidence = folded(evidenceText);
        return conceptRules.stream()
                .map(rule -> new ConceptScore(rule, scoreConcept(rule, text, evidence, existingTags)))
                .filter(score -> score.score() > 0)
                .max(Comparator.comparingInt(ConceptScore::score))
                .map(ConceptScore::rule)
                .orElse(null);
    }

    private static int scoreConcept(ConceptRule rule, String foldedText, String evidenceText, List<String> existingTags) {
        int score = 0;
        boolean sourceDefault = rule.sourceDefaults().stream()
                .map(SearchCatalogSidecarBuilder::folded)
                .anyMatch(source -> !source.isBlank()
                        && (" " + evidenceText + " ").contains(" " + source + " "));
        if (sourceDefault) {
            score += 30;
        }
        if (containsIgnoreCase(existingTags, rule.id())) {
            score += 40;
        }
        for (String tag : existingTags) {
            if (rule.tags().stream().anyMatch(t -> t.equalsIgnoreCase(tag))) {
                score += 20;
            }
        }
        for (String foldedAlias : rule.foldedTextAliases()) {
            if (foldedAlias.length() < 2) {
                continue;
            }
            if ((" " + foldedText + " ").contains(" " + foldedAlias + " ")) {
                score += foldedAlias.contains(" ") ? 12 : 6;
            }
        }
        if (score == 0) {
            return 0;
        }
        for (String foldedAbbreviation : rule.foldedAbbreviations()) {
            if ((" " + foldedText + " ").contains(" " + foldedAbbreviation + " ")) {
                score += 3;
            }
        }
        if (sourceDefault) {
            return containsAny(evidenceText, rule.negativeConcepts().toArray(String[]::new)) ? 0 : score;
        }
        return conceptRuleEligible(rule, evidenceText, existingTags) ? score : 0;
    }

    private static boolean conceptRuleEligible(ConceptRule rule, String foldedText, List<String> existingTags) {
        if (containsAny(foldedText, rule.negativeConcepts().toArray(String[]::new))) {
            return false;
        }
        String id = rule.id();
        return switch (id) {
            case "core_inflation" -> inflationEvidence(foldedText) && coreInflationEvidence(foldedText, existingTags);
            case "central_bank_policy_rate" -> policyRateEvidence(foldedText);
            case "bank_net_profit" -> bankProfitEvidence(foldedText);
            case "equity_market_price" -> equityMarketEvidence(foldedText);
            case "automotive_production" -> automotiveProductionEvidence(foldedText);
            case "house_price_index" -> housePriceEvidence(foldedText);
            case "commodity_spot_price" -> commodityPriceEvidence(foldedText);
            case "exchange_rate" -> exchangeRateEvidence(foldedText);
            default -> true;
        };
    }

    private List<ConceptRule> readRules() {
        try (InputStream in = getClass().getResourceAsStream("/search_v2/sidecar_concept_taxonomy.json")) {
            if (in == null) {
                return List.of();
            }
            Map<String, Object> root = objectMapper.readValue(in, MAP_TYPE);
            Object raw = root.get("concepts");
            if (!(raw instanceof List<?> list)) {
                return List.of();
            }
            List<ConceptRule> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(ConceptRule.from(CatalogMapSupport.castMap(map)));
                }
            }
            return out;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, String> readEcbCbd2ItemLabels() {
        try (InputStream in = getClass().getResourceAsStream("/data/ecb_cbd2_item_labels.json")) {
            if (in == null) {
                return Map.of();
            }
            Map<String, Object> root = objectMapper.readValue(in, MAP_TYPE);
            if (!(root.get("labels") instanceof Map<?, ?> labels)) {
                return Map.of();
            }
            Map<String, String> out = new LinkedHashMap<>();
            labels.forEach((code, label) -> {
                String cleanCode = clean(CatalogMapSupport.str(code)).toUpperCase(Locale.ROOT);
                String cleanLabel = clean(CatalogMapSupport.str(label));
                if (!cleanCode.isBlank() && !cleanLabel.isBlank()) {
                    out.put(cleanCode, cleanLabel);
                }
            });
            return Map.copyOf(out);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    /**
     * Restores semantic labels that are encoded only as SDMX dimension codes in large ECB mirrors.
     * The mapping is generated from the official ECB data structure, not from query-specific rules.
     */
    private Map<String, Object> enrichEcbMetadata(Map<String, Object> rawRow) {
        Map<String, Object> raw = rawRow == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rawRow);
        String source = CatalogSourceRegistry.normalizeSearchSource(
                CatalogMapSupport.firstNonBlank(raw.get("source"), raw.get("source_type"), raw.get("catalog_id")));
        if (!"ecb2".equals(source)) {
            return raw;
        }

        String seriesId = resolveSeriesId(raw, source);
        String flow = clean(CatalogMapSupport.firstNonBlank(raw.get("ecb_flow"), flowFromSeriesId(seriesId)))
                .toUpperCase(Locale.ROOT);
        if ("CBD".equals(flow)) {
            raw.putIfAbsent("dataset", "CBD");
            raw.put("description", appendText(
                    CatalogMapSupport.firstNonBlank(raw.get("description"), raw.get("full_path")),
                    "Historical ECB consolidated banking dataflow, discontinued in 2014; successor: CBD2."));
            raw.put("dataset_lifecycle", "discontinued");
            return raw;
        }
        if (!"CBD2".equals(flow)) {
            return raw;
        }

        raw.putIfAbsent("dataset", "CBD2");
        raw.put("dataset_lifecycle", "current");
        String itemCode = cbd2ItemCode(raw, seriesId);
        String itemLabel = ecbCbd2ItemLabels.getOrDefault(itemCode, "");
        if (itemLabel.isBlank()) {
            return raw;
        }

        String originalTitle = clean(CatalogMapSupport.firstNonBlank(
                raw.get("title_original"), raw.get("title"), raw.get("name"), seriesId));
        String displayTitle = containsPhrase(originalTitle, itemLabel)
                ? originalTitle
                : itemLabel + (originalTitle.isBlank() ? "" : " · " + originalTitle);
        raw.put("title_original", displayTitle);
        raw.put("human_label_en", displayTitle);
        raw.put("human_label_cs", displayTitle);
        raw.put("ecb_cb_item_code", itemCode);
        raw.put("ecb_cb_item_label", itemLabel);
        raw.put("search_keywords_en", mergeStringValues(raw.get("search_keywords_en"), itemLabel));
        raw.put("aliases_en", mergeStringValues(raw.get("aliases_en"), itemLabel));
        raw.put("description", appendText(
                CatalogMapSupport.firstNonBlank(raw.get("description"), raw.get("full_path")),
                "Current ECB CBD2 framework. CB_ITEM " + itemCode + ": " + itemLabel + "."));
        return raw;
    }

    private static String flowFromSeriesId(String seriesId) {
        int separator = seriesId == null ? -1 : seriesId.indexOf('/');
        return separator > 0 ? seriesId.substring(0, separator) : "";
    }

    private static String cbd2ItemCode(Map<String, Object> raw, String seriesId) {
        String explicit = clean(CatalogMapSupport.firstNonBlank(raw.get("ecb_cb_item_code"), raw.get("CB_ITEM")));
        if (!explicit.isBlank()) {
            return explicit.toUpperCase(Locale.ROOT);
        }
        String seriesKey = clean(CatalogMapSupport.firstNonBlank(raw.get("ecb_series_key"), seriesId));
        int separator = seriesKey.indexOf('/');
        if (separator >= 0) {
            seriesKey = seriesKey.substring(separator + 1);
        }
        String[] dimensions = seriesKey.split("\\.");
        return dimensions.length > 8 ? dimensions[8].trim().toUpperCase(Locale.ROOT) : "";
    }

    private static List<String> mergeStringValues(Object existing, String extra) {
        List<String> values = new ArrayList<>(stringList(existing));
        addClean(values, extra);
        return distinct(values);
    }

    private static boolean containsPhrase(String text, String phrase) {
        String normalizedText = CatalogTextUtils.normalizeTokenBoundaries(text);
        String normalizedPhrase = CatalogTextUtils.normalizeTokenBoundaries(phrase);
        return !normalizedPhrase.isBlank() && (" " + normalizedText + " ").contains(" " + normalizedPhrase + " ");
    }

    private static String appendText(String existing, String addition) {
        String left = clean(existing);
        String right = clean(addition);
        if (left.isBlank()) {
            return right;
        }
        return containsPhrase(left, right) ? left : left + " " + right;
    }

    private static String searchText(
            String source,
            String dataset,
            String title,
            String description,
            String originalTitle,
            String originalDescription,
            String primaryConcept,
            List<String> secondaryConcepts,
            List<String> aliases,
            List<String> abbreviations,
            List<String> structuredMetadata,
            String geo,
            String unit,
            String frequency) {
        List<String> weighted = new ArrayList<>();
        addRepeated(weighted, title, 5);
        addRepeated(weighted, primaryConcept, 4);
        addRepeated(weighted, structuredMetadata, 4);
        addRepeated(weighted, aliases, 3);
        addRepeated(weighted, abbreviations, 3);
        addRepeated(weighted, originalTitle, 2);
        addRepeated(weighted, secondaryConcepts, 2);
        addRepeated(weighted, description, 1);
        addRepeated(weighted, originalDescription, 1);
        addRepeated(weighted, dataset, 1);
        addRepeated(weighted, source, 1);
        addRepeated(weighted, geo, 1);
        addRepeated(weighted, unit, 1);
        addRepeated(weighted, frequency, 1);
        return clean(String.join(" ", weighted));
    }

    private static List<String> structuredMetadata(String... values) {
        List<String> out = new ArrayList<>();
        for (String value : values == null ? new String[0] : values) {
            addClean(out, value);
        }
        return distinct(out);
    }

    private static String metadataEvidenceText(String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values == null ? new String[0] : values) {
            addClean(parts, value);
        }
        return folded(String.join(" ", parts));
    }

    private static String deriveMeasureType(String source, String text) {
        if (containsAny(text, "core inflation", "underlying inflation", "jadrova inflace")) {
            return "core_inflation";
        }
        if (containsAny(text, "policy rate", "repo rate", "discount rate", "lombard rate", "central bank rate",
                "official interest rate", "two week repo", "2w repo", "monetary policy rate")) {
            return "central_bank_policy_rate";
        }
        if (returnOnAssetsEvidence(text)) {
            return "roa";
        }
        if (returnOnEquityEvidence(text)) {
            return "roe";
        }
        if (containsAny(text, "net profit", "profit or loss", "profit after tax", "bank net profit", "cisty zisk")) {
            return "net_profit";
        }
        if (containsAny(text, "industrial production index", "production in industry", "prumyslova vyroba")) {
            return "industrial_production_index";
        }
        if (containsAny(text, "house price index", "residential property prices", "dwelling prices", "ceny nemovitosti",
                "ceny bytu", "ceny domu", "nakup obydli", "nakupu obydli", "index cen nakupu obydli", "ceny obydli")) {
            return "house_price_index";
        }
        if (containsAny(text, "spot price", "reference price", "market price", "commodity price", "pink sheet")
                || "commodities".equals(source)) {
            return "market_price";
        }
        if (containsAny(text, "share price", "stock price", "equity price", "quote price")) {
            return "market_price";
        }
        return "";
    }

    private static String deriveEconomicObject(String text) {
        if (containsAny(text, "official reserve assets") && containsAny(text, "gold", "zlato")) {
            return "central_bank_gold_reserves";
        }
        if (containsAny(text, "gold", "zlato")) {
            return "gold";
        }
        if (containsAny(text,
                "wage",
                "wages",
                "salary",
                "salaries",
                "average earnings",
                "hourly earnings",
                "employee earnings",
                "earnings of employees",
                "mzda",
                "mzdy")) {
            return "wages";
        }
        if (containsAny(text, "consumer price", "hicp", "cpi", "inflation", "inflace")) {
            return "consumer_prices";
        }
        if (containsAny(text, "bank", "banks") && containsAny(text, "profit", "zisk")) {
            return "bank_profit";
        }
        return "";
    }

    private static String deriveInstitutionalSector(String text) {
        if (containsAny(text, "government sector", "public sector", "general government")) {
            return "government";
        }
        if (containsAny(text, "all economy", "total economy", "national economy", "entire economy")) {
            return "total_economy";
        }
        if (containsAny(text, "pension fund", "pension funds", "retirement fund")) {
            return "pension_funds";
        }
        if (containsAny(text,
                "insurance company",
                "insurance companies",
                "insurance corporation",
                "insurance corporations",
                "insurer",
                "insurers")) {
            return "insurance";
        }
        if (containsAny(text, "other financial corporation", "other financial corporations")) {
            return "other_financial_corporations";
        }
        if (containsAny(text,
                "banks",
                "banking",
                "banking sector",
                "credit institutions",
                "mfi",
                "mfis",
                "monetary financial institution",
                "monetary financial institutions",
                "deposit taker",
                "deposit takers",
                "deposit-taking corporation",
                "deposit-taking corporations")) {
            return "banks";
        }
        if (containsAny(text, "central bank")) {
            return "central_bank";
        }
        return "";
    }

    private static String resolveInstitutionalSector(
            Map<String, Object> raw,
            ConceptRule selected,
            String title,
            String description,
            String fallbackEvidence) {
        String explicit = clean(CatalogMapSupport.str(raw.get("institutional_sector")));
        if (!explicit.isBlank()) {
            return explicit;
        }

        String ecbDimensionEvidence = firstNonBlankText(
                labeledDimensionValue(CatalogMapSupport.str(raw.get("ecb_series_explanation")), "REF SECTOR"),
                labeledDimensionValue(CatalogMapSupport.str(raw.get("ecb_series_explanation")), "BS REP SECTOR"),
                CatalogMapSupport.str(raw.get("ref_sector")),
                CatalogMapSupport.str(raw.get("REF_SECTOR")));
        String derivedEcbDimension = deriveInstitutionalSector(ecbDimensionEvidence);
        if (!derivedEcbDimension.isBlank()) {
            return derivedEcbDimension;
        }

        String specificEvidence = metadataEvidenceText(
                title,
                CatalogMapSupport.str(raw.get("ecb_series_explanation")),
                CatalogMapSupport.str(raw.get("ecb_value_descriptor")),
                CatalogMapSupport.str(raw.get("sector")),
                CatalogMapSupport.str(raw.get("sector_label")));
        String derivedSpecific = deriveInstitutionalSector(specificEvidence);
        if (!derivedSpecific.isBlank()) {
            return derivedSpecific;
        }

        String registryValue = selected == null ? "" : clean(selected.attributes().get("institutional_sector"));
        if (!registryValue.isBlank()) {
            return registryValue;
        }

        String contextualEvidence = metadataEvidenceText(
                fallbackEvidence,
                description,
                CatalogMapSupport.str(raw.get("ecb_flow_label")),
                CatalogMapSupport.str(raw.get("ecb_dataflow_title")),
                CatalogMapSupport.str(raw.get("ecb_code_labels")));
        return deriveInstitutionalSector(contextualEvidence);
    }

    private static String labeledDimensionValue(String explanation, String label) {
        if (explanation == null || explanation.isBlank() || label == null || label.isBlank()) {
            return "";
        }
        for (String part : explanation.split("\\s*[·|;]\\s*")) {
            int separator = part.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            if (part.substring(0, separator).trim().equalsIgnoreCase(label)) {
                return part.substring(separator + 1).trim();
            }
        }
        return "";
    }

    private static String firstNonBlankText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String deriveInstrument(String source, String text) {
        if ("stocks".equals(source) || "yahoo_finance".equals(source)
                || containsAny(text, "share price", "stock price", "equity market", "market quote", "ticker",
                        "cena akcie", "burzovni cena akcie", "akciovy trh")) {
            return "equity";
        }
        if (containsAny(text, "mortgage", "loan", "uvery", "uver")) {
            return "loan";
        }
        if (containsAny(text, "interest rate", "urokova sazba", "sazba")) {
            return "interest_rate";
        }
        return "";
    }

    private static String derivePriceType(String source, String text) {
        if ("commodities".equals(source) || containsAny(text, "spot price", "reference price", "pink sheet", "commodity price")) {
            return "commodity_market_price";
        }
        if (containsAny(text, "producer price", "ppi")) {
            return "producer";
        }
        if (containsAny(text, "consumer price", "hicp", "cpi")) {
            return "consumer";
        }
        if (containsAny(text, "current prices", "beznych cenach")) {
            return "current_prices";
        }
        if (containsAny(text, "constant prices", "stalych cenach", "deflated")) {
            return "constant_prices";
        }
        return "";
    }

    private static String deriveFlowStock(String text) {
        if (containsAny(text, "stock", "balance sheet", "assets", "liabilities", "reserves")) {
            return "stock";
        }
        if (containsAny(text, "flow", "production", "output", "income", "profit", "expenditure", "revenue")) {
            return "flow";
        }
        return "";
    }

    private static String deriveIndustrySector(String text) {
        if (automotiveProductionEvidence(text)) {
            return "automotive_manufacturing";
        }
        if (containsAny(text, "industry", "industrial", "manufacturing", "prumysl")) {
            return "industry";
        }
        if (containsAny(text, "construction", "stavebnictvi")) {
            return "construction";
        }
        return "";
    }

    private static String deriveNominalReal(String text) {
        if (containsAny(text, "real ", "constant prices", "deflated", "inflation adjusted", "volume", "stalych cenach",
                "realne", "realna")) {
            return "real";
        }
        if (containsAny(text, "nominal", "current prices", "beznych cenach", "current price")) {
            return "nominal";
        }
        return "";
    }

    private static String deriveDatasetFamily(String source, String dataset, String text) {
        String combined = folded(source + " " + dataset + " " + text);
        if (containsAny(combined, "hicp", "cpi", "inflation", "consumer prices")) {
            return "prices";
        }
        if (containsAny(combined, "national accounts", "gdp", "hdp")) {
            return "national_accounts";
        }
        if (containsAny(combined, "bank", "banking", "credit institution")) {
            return "banking";
        }
        if (containsAny(combined, "industrial production", "production in industry", "nace")) {
            return "industry";
        }
        if (containsAny(combined, "house price", "residential property", "real estate", "housing")) {
            return "housing";
        }
        if (containsAny(combined, "commodity", "pink sheet")) {
            return "commodities";
        }
        if ("stocks".equals(source)
                || containsAny(combined, "stock price", "share price", "equity market", "yahoo finance")) {
            return "markets";
        }
        return "";
    }

    private static String deriveCatalogFamily(
            String source,
            String primaryConcept,
            String measureType,
            String economicObject,
            String instrument,
            String industrySector,
            String text) {
        if ("commodities".equals(source)
                || containsAny(text, "commodity", "pink sheet")
                || "commodity_market_price".equals(measureType)
                || "commodity_market_price".equals(economicObject)) {
            return "commodities";
        }
        if ("central_bank_policy_rate".equals(measureType)) {
            return "macro";
        }
        if ("stocks".equals(source)
                || "yahoo_finance".equals(source)
                || "equity".equals(instrument)
                || containsAny(text, "share price", "stock price", "ticker", "equity market",
                        "cena akcie", "burzovni cena akcie", "akciovy trh")) {
            return "markets_equities";
        }
        if (containsAny(primaryConcept, "bank") || containsAny(text, "bank", "credit institution")) {
            return "banking";
        }
        if ("house_price_index".equals(primaryConcept)
                || "house_price_index".equals(measureType)
                || containsAny(text, "house price", "real estate", "residential property", "ceny nemovitosti",
                        "nakup obydli", "nakupu obydli")) {
            return "real_estate";
        }
        if (!industrySector.isBlank()
                || "industrial_production_index".equals(measureType)
                || containsAny(text, "industrial production", "nace", "manufacturing")) {
            return "sectoral";
        }
        if (containsAny(text, "gdp", "inflation", "wage", "employment", "exchange rate", "interest rate")) {
            return "macro";
        }
        return "other";
    }

    private static String refinePrimaryConcept(
            String primaryConcept,
            String measureType,
            String economicObject,
            String industrySector,
            String instrument,
            String catalogFamily) {
        if ("core_inflation".equals(measureType)) {
            return "core_inflation";
        }
        if ("central_bank_policy_rate".equals(measureType)) {
            return "central_bank_policy_rate";
        }
        if ("industrial_production_index".equals(measureType)) {
            return "industrial_production";
        }
        if ("automotive_manufacturing".equals(industrySector)) {
            return "automotive_production";
        }
        if ("house_price_index".equals(measureType)) {
            return "house_price_index";
        }
        if ("equity".equals(instrument) || "markets_equities".equals(catalogFamily)) {
            return "equity_market_price";
        }
        if ("central_bank_gold_reserves".equals(economicObject)) {
            return "central_bank_gold_reserves";
        }
        if ("market_price".equals(measureType) && "gold".equals(economicObject)) {
            return "commodity_spot_price";
        }
        return primaryConcept;
    }

    private static MetadataSanity sanitizeMetadata(
            String source,
            String text,
            String primaryConcept,
            String measureType,
            String economicObject,
            String instrument,
            String catalogFamily) {
        String safePrimaryConcept = clean(primaryConcept);
        String safeMeasureType = clean(measureType);
        String safeEconomicObject = clean(economicObject);
        String safeInstrument = clean(instrument);
        String safeCatalogFamily = clean(catalogFamily);

        if ("core_inflation".equals(safeMeasureType)
                && !(inflationEvidence(text) && coreInflationEvidence(text, List.of()))) {
            safeMeasureType = inflationEvidence(text) ? "headline_inflation" : "";
            if ("core_inflation".equals(safePrimaryConcept)) {
                safePrimaryConcept = inflationEvidence(text) ? "consumer_price_inflation" : "";
            }
        }
        if ("central_bank_policy_rate".equals(safeMeasureType) && !policyRateEvidence(text)) {
            safeMeasureType = containsAny(text, "interest rate", "urokova sazba", "urokove sazby", "sazba", "sazby")
                    ? "interest_rate"
                    : "";
            if ("central_bank_policy_rate".equals(safePrimaryConcept)) {
                safePrimaryConcept = "";
            }
        }
        if ("net_profit".equals(safeMeasureType) && !bankProfitEvidence(text)) {
            safeMeasureType = "";
            if ("bank_net_profit".equals(safePrimaryConcept)) {
                safePrimaryConcept = "";
            }
        }
        if ("equity".equals(safeInstrument) && !equityMarketEvidence(text) && !"stocks".equals(source)) {
            safeInstrument = "";
        }
        if ("equity_market_price".equals(safePrimaryConcept) && !equityMarketEvidence(text) && !"stocks".equals(source)) {
            safePrimaryConcept = "";
        }
        if ("markets_equities".equals(safeCatalogFamily) && safeInstrument.isBlank() && !"stocks".equals(source)) {
            safeCatalogFamily = "";
        }
        if (safeCatalogFamily.isBlank()) {
            safeCatalogFamily = deriveCatalogFamily(
                    source,
                    safePrimaryConcept,
                    safeMeasureType,
                    safeEconomicObject,
                    safeInstrument,
                    "",
                    text);
        }
        if (safePrimaryConcept.isBlank()) {
            safePrimaryConcept = normalizeConcept(firstNonBlank(safeMeasureType, safeEconomicObject, safeCatalogFamily));
        }
        return new MetadataSanity(safePrimaryConcept, safeMeasureType, safeEconomicObject, safeInstrument, safeCatalogFamily);
    }

    private List<String> sanitizeAliasesForEvidence(List<String> aliases, String evidenceText) {
        List<String> out = new ArrayList<>();
        for (String alias : aliases == null ? List.<String>of() : aliases) {
            String foldedAlias = folded(alias);
            if (foldedAlias.isBlank()) {
                continue;
            }
            boolean contradictsRegistryEvidence = conceptRulesByAlias
                    .getOrDefault(foldedAlias, List.of())
                    .stream()
                    .anyMatch(rule -> !conceptRuleEligible(rule, evidenceText, List.of()));
            if (contradictsRegistryEvidence) {
                continue;
            }
            if (containsAny(foldedAlias, "akciovy trh", "burza", "stock price", "share price", "equity market",
                    "market quote", "cena akcie", "burzovni cena akcie", "ticker")
                    && !equityMarketEvidence(evidenceText)) {
                continue;
            }
            if (containsAny(foldedAlias, "repo sazba", "sazby cnb", "central bank policy rate", "policy rate",
                    "official interest rate", "monetary policy rate", "menovepoliticka sazba")
                    && !policyRateEvidence(evidenceText)) {
                continue;
            }
            if (policyRateEvidence(evidenceText)
                    && containsAny(foldedAlias, "vynosy dluhopisu", "bond yield", "bond yields")
                    && !containsAny(evidenceText, "bond yield", "government bond", "vynos dluhopisu",
                            "vynosy dluhopisu")) {
                continue;
            }
            if (profitabilityRatioEvidence(evidenceText)
                    && containsAny(foldedAlias, "zisk bank", "bank profit", "profit of banks", "net profit")
                    && !containsAny(foldedAlias, "roe", "roa", "rentabilita", "return on equity",
                            "return on assets")) {
                continue;
            }
            if (containsAny(foldedAlias, "bank", "banks", "bankovni", "banking")
                    && !bankingSectorEvidence(evidenceText)) {
                continue;
            }
            if (containsAny(foldedAlias, "jadrova inflace", "core inflation", "underlying inflation")
                    && !(inflationEvidence(evidenceText) && coreInflationEvidence(evidenceText, List.of()))) {
                continue;
            }
            if (containsAny(foldedAlias, "roa", "return on assets", "rentabilita aktiv")
                    && !returnOnAssetsEvidence(evidenceText)) {
                continue;
            }
            if (containsAny(foldedAlias, "roe", "return on equity", "rentabilita kapitalu")
                    && !returnOnEquityEvidence(evidenceText)) {
                continue;
            }
            addClean(out, alias);
        }
        return distinct(out);
    }

    private static Set<String> ruleAliases(ConceptRule rule) {
        return new LinkedHashSet<>(rule.foldedAliases());
    }

    private static Map<String, List<ConceptRule>> indexRulesByAlias(List<ConceptRule> rules) {
        Map<String, List<ConceptRule>> mutable = new LinkedHashMap<>();
        for (ConceptRule rule : rules == null ? List.<ConceptRule>of() : rules) {
            for (String alias : ruleAliases(rule)) {
                mutable.computeIfAbsent(alias, ignored -> new ArrayList<>()).add(rule);
            }
        }
        Map<String, List<ConceptRule>> immutable = new LinkedHashMap<>();
        mutable.forEach((alias, matchingRules) -> immutable.put(alias, List.copyOf(matchingRules)));
        return Map.copyOf(immutable);
    }

    private static boolean inflationEvidence(String text) {
        return containsAny(text, "inflation", "inflace", "hicp", "cpi", "consumer price", "spotrebitelske ceny");
    }

    private static boolean coreInflationEvidence(String text, List<String> existingTags) {
        return containsIgnoreCase(existingTags, "core_inflation")
                || containsAny(text, "core inflation", "underlying inflation", "jadrova inflace", "zakladni inflace",
                        "excluding energy", "excluding food", "bez energii", "bez potravin");
    }

    private static boolean policyRateEvidence(String text) {
        return containsAny(text, "policy rate", "repo rate", "two week repo", "2w repo", "2-week repo",
                "discount rate", "lombard rate", "official interest rate", "monetary policy rate",
                "repo sazba", "dvoutydenni repo", "diskontni sazba", "lombardni sazba", "menovepoliticka sazba",
                "menove politicka sazba")
                || (containsAny(text, "central bank", "cnb", "ceska narodni banka")
                        && containsAny(text, "policy", "repo", "discount", "lombard"));
    }

    private static boolean bankingSectorEvidence(String text) {
        return containsAny(text,
                "banks",
                "banking sector",
                "credit institution",
                "credit institutions",
                "monetary financial institution",
                "monetary financial institutions",
                "deposit-taking corporation",
                "deposit-taking corporations",
                "bankovni sektor");
    }

    private static boolean bankProfitEvidence(String text) {
        boolean bank = containsAny(text, "bank", "banks", "banking", "bankovni", "monetary financial institutions",
                "mfi", "credit institutions", "uverove instituce");
        boolean profit = containsAny(text, "bank net profit", "net profit of banks", "profit of banks", "bank earnings",
                "profit after tax", "profit or loss", "cisty zisk bank", "zisk bank", "vysledek hospodareni bank",
                "net profit", "earnings")
                && !containsAny(text, "return on equity", "return on assets", "roe", "roa", "capital ratio");
        return bank && profit;
    }

    private static boolean profitabilityRatioEvidence(String text) {
        return returnOnAssetsEvidence(text) || returnOnEquityEvidence(text);
    }

    private static boolean returnOnAssetsEvidence(String text) {
        return containsAny(text, "return on assets", "rentabilita aktiv", "roa")
                || (containsAny(text, "average assets", "prumerna aktiva")
                        && containsAny(text, "profit", "profit or loss", "zisk", "ztrata", "hospodarsky vysledek"));
    }

    private static boolean returnOnEquityEvidence(String text) {
        return containsAny(text, "return on equity", "rentabilita kapitalu", "roe")
                || (containsAny(text, "equity", "average equity", "vlastni kapital", "tier 1")
                        && containsAny(text, "profit", "profit or loss", "zisk", "ztrata", "hospodarsky vysledek"));
    }

    private static boolean equityMarketEvidence(String text) {
        return containsAny(text, "share price", "stock price", "equity market price", "market quote", "ticker",
                "burzovni cena akcie", "cena akcie", "akciovy trh", "yahoo finance");
    }

    private static boolean automotiveProductionEvidence(String text) {
        boolean automotive = containsAny(text, "automotive", "automobile", "motor vehicles", "cars", "automobil",
                "motorova vozidla", "nace c29", "c29");
        boolean production = containsAny(text, "production", "industrial production", "manufacture", "manufacturing",
                "output", "vyroba", "produkce", "zpracovatelsky prumysl");
        boolean onlyTrade = containsAny(text, "turnover", "retail trade", "wholesale", "sales", "obchod", "trzby")
                && !containsAny(text, "production", "industrial production", "vyroba", "produkce");
        return automotive && production && !onlyTrade;
    }

    private static boolean housePriceEvidence(String text) {
        boolean housing = containsAny(text, "house", "housing", "dwelling", "residential property", "real estate",
                "byt", "bytu", "domu", "obydli", "nemovitosti");
        boolean price = containsAny(text, "price", "prices", "ceny", "index cen", "hpi", "nakup");
        boolean quantityOnly = containsAny(text, "completed dwellings", "housing completions", "construction permits",
                "pocet bytu", "dokoncene byty")
                && !price;
        return housing && price && !quantityOnly;
    }

    private static boolean commodityPriceEvidence(String text) {
        boolean commodity = containsAny(text, "commodity", "gold", "oil", "brent", "wti", "natural gas", "zlato",
                "ropa", "komodita");
        boolean price = containsAny(text, "price", "spot", "reference", "market", "usd per", "cena", "commodity price");
        boolean nonPrice = containsAny(text, "reserves", "reserve assets", "production", "output", "producer price index",
                "rezervy", "produkce");
        return commodity && price && !nonPrice;
    }

    private static boolean exchangeRateEvidence(String text) {
        boolean currency = containsAny(
                text, "currency", "foreign exchange", "exchange rate", "fx rate", "smenny kurz", "menovy kurz");
        boolean rate = containsAny(text, "rate", "kurz", "per usd", "per euro", "national currency");
        return currency && rate;
    }

    private static double qualityScore(
            String titleCs, String titleEn, String description, String geo, String unit, String frequency, String concept) {
        double score = 0.0;
        if (!titleCs.isBlank() || !titleEn.isBlank()) {
            score += 0.25;
        }
        if (!description.isBlank()) {
            score += 0.15;
        }
        if (!geo.isBlank()) {
            score += 0.15;
        }
        if (!unit.isBlank()) {
            score += 0.15;
        }
        if (!frequency.isBlank()) {
            score += 0.15;
        }
        if (!concept.isBlank()) {
            score += 0.15;
        }
        return Math.min(1.0, score);
    }

    private static List<String> abbreviationsFromText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String token : text.split("[^A-Za-z0-9]+")) {
            if (token.length() >= 2 && token.length() <= 8 && token.equals(token.toUpperCase(Locale.ROOT))) {
                out.add(token);
            }
        }
        return List.copyOf(out);
    }

    private static List<String> stringList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addClean(out, CatalogMapSupport.str(item));
            }
        } else {
            for (String part : CatalogMapSupport.str(raw).split("[,;|]")) {
                addClean(out, part);
            }
        }
        return distinct(out);
    }

    @SafeVarargs
    private static List<String> concat(List<String>... values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (List<String> list : values) {
                if (list != null) {
                    out.addAll(list);
                }
            }
        }
        return out;
    }

    private static List<String> concat(List<String> left, List<String> right, String extra) {
        List<String> out = new ArrayList<>();
        if (left != null) {
            out.addAll(left);
        }
        if (right != null) {
            out.addAll(right);
        }
        addClean(out, extra);
        return out;
    }

    private static List<String> concat(List<String> left, List<String> right, List<String> third) {
        List<String> out = new ArrayList<>();
        if (left != null) {
            out.addAll(left);
        }
        if (right != null) {
            out.addAll(right);
        }
        if (third != null) {
            out.addAll(third);
        }
        return out;
    }

    private static List<String> distinct(List<String> values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            addClean(out, value);
        }
        return List.copyOf(out);
    }

    private static void addClean(java.util.Collection<String> target, String value) {
        String clean = clean(value);
        if (!clean.isBlank()) {
            target.add(clean);
        }
    }

    private static void addRepeated(List<String> target, String value, int repeat) {
        String clean = clean(value);
        if (clean.isBlank()) {
            return;
        }
        for (int i = 0; i < repeat; i++) {
            target.add(clean);
        }
    }

    private static void addRepeated(List<String> target, List<String> values, int repeat) {
        for (String value : values == null ? List.<String>of() : values) {
            addRepeated(target, value, repeat);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String firstNonBlank(Map<String, Object> raw, ConceptRule rule, String key) {
        String direct = clean(CatalogMapSupport.str(raw.get(key)));
        if (!direct.isBlank()) {
            return direct;
        }
        return rule == null ? "" : clean(rule.attributes().get(key));
    }

    private static String repairContradictingGeneratedTitle(String source, String canonicalTitle, String originalTitle) {
        String sourceNorm = clean(source);
        String canonicalNorm = folded(canonicalTitle);
        String originalNorm = folded(originalTitle);
        if ("arad".equals(sourceNorm)
                && originalNorm.contains("urokove sazby cnb")
                && canonicalNorm.contains("vynosy dluhopisu")) {
            String officialIndicator = clean(originalTitle.split(":", 2)[0]);
            return officialIndicator.isBlank() ? originalTitle : officialIndicator;
        }
        if ("arad".equals(sourceNorm)
                && ((containsAny(canonicalNorm, "roa", "return on assets", "rentabilita aktiv")
                                && !returnOnAssetsEvidence(originalNorm))
                        || (containsAny(canonicalNorm, "roe", "return on equity", "rentabilita kapitalu")
                                && !returnOnEquityEvidence(originalNorm)))) {
            return originalTitle;
        }
        if ("arad".equals(sourceNorm)
                && !canonicalNorm.contains(" ")
                && canonicalNorm.length() >= 3
                && !CatalogTextUtils.containsWholeTokenOrPhrase(originalNorm, canonicalNorm)) {
            return originalTitle;
        }
        return canonicalTitle;
    }

    private static String normalizeConcept(String value) {
        String folded = folded(value);
        if (folded.isBlank()) {
            return "";
        }
        List<String> tokens = Arrays.stream(folded.split("\\s+"))
                .filter(token -> token.length() >= 3)
                .limit(4)
                .toList();
        return String.join("_", tokens);
    }

    private static String clean(Object raw) {
        String value = CatalogMapSupport.str(raw);
        if (value.isBlank()) {
            return "";
        }
        return repairMojibake(value).replaceAll("\\s+", " ").trim();
    }

    private static String folded(String value) {
        return CatalogTextUtils.normalizeTokenBoundaries(clean(value));
    }

    private static boolean containsIgnoreCase(List<String> values, String value) {
        return values != null && values.stream().anyMatch(v -> v != null && v.equalsIgnoreCase(value));
    }

    private static boolean containsAny(String value, String... needles) {
        String haystack = folded(value);
        if (haystack.isBlank()) {
            return false;
        }
        for (String needle : needles == null ? new String[0] : needles) {
            String n = folded(needle);
            if (!n.isBlank() && (" " + haystack + " ").contains(" " + n + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String repairMojibake(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String out = value;
        for (Map.Entry<String, String> entry : MOJIBAKE_REPAIRS.entrySet()) {
            out = out.replace(entry.getKey(), entry.getValue());
        }
        return out;
    }

    private static final Map<String, String> MOJIBAKE_REPAIRS = Map.ofEntries(
            Map.entry("\u00c4\u015a", "\u010c"),
            Map.entry("\u00c4\u0159", "\u010d"),
            Map.entry("\u00c4\u008c", "\u010c"),
            Map.entry("\u00c4\u008d", "\u010d"),
            Map.entry("\u00c4\u0179", "\u010f"),
            Map.entry("\u00c4\u0165", "\u011b"),
            Map.entry("\u00c4\u009b", "\u011b"),
            Map.entry("\u00c5\u0087", "\u0148"),
            Map.entry("\u00c5\u0099", "\u0159"),
            Map.entry("\u00c5\u02c7", "\u0159"),
            Map.entry("\u00c5\u00a1", "\u0161"),
            Map.entry("\u00c5\u0165", "\u0165"),
            Map.entry("\u00c5\u00ba", "\u017a"),
            Map.entry("\u00c5\u00be", "\u017e"),
            Map.entry("\u00c5\u016f", "\u016f"),
            Map.entry("\u00c3\u00a1", "\u00e1"),
            Map.entry("\u00c3\u00a9", "\u00e9"),
            Map.entry("\u00c3\u00ad", "\u00ed"),
            Map.entry("\u00c3\u00b3", "\u00f3"),
            Map.entry("\u00c3\u00ba", "\u00fa"),
            Map.entry("\u00c3\u00bd", "\u00fd"),
            Map.entry("\u00c3\u201d", "\u00f3"),
            Map.entry("\u00c3\u0161", "\u00fa"),
            Map.entry("\u00c3\u00a4", "\u00e4"),
            Map.entry("\u00c3\u00b6", "\u00f6"),
            Map.entry("\u00c3\u00bc", "\u00fc"));

    private record MetadataSanity(
            String primaryConcept,
            String measureType,
            String economicObject,
            String instrument,
            String catalogFamily) {}

    private record ConceptScore(ConceptRule rule, int score) {}

    private record ConceptRule(
            String id,
            List<String> aliasesCs,
            List<String> aliasesEn,
            List<String> abbreviations,
            List<String> foldedAliases,
            List<String> foldedTextAliases,
            List<String> foldedAbbreviations,
            List<String> tags,
            List<String> sourceDefaults,
            List<String> secondaryConcepts,
            List<String> negativeConcepts,
            Map<String, String> attributes) {

        static ConceptRule from(Map<String, Object> row) {
            Map<String, String> attributes = new LinkedHashMap<>();
            for (String key : List.of(
                    "measure_type",
                    "economic_object",
                    "institutional_sector",
                    "counterpart_sector",
                    "instrument",
                    "price_type",
                    "flow_stock",
                    "industry_sector",
                    "nominal_real",
                    "scope",
                    "dataset_family",
                    "catalog_family")) {
                String value = CatalogMapSupport.str(row.get(key));
                if (!value.isBlank()) {
                    attributes.put(key, value);
                }
            }
            List<String> aliasesCs = stringList(row.get("aliases_cs"));
            List<String> aliasesEn = stringList(row.get("aliases_en"));
            List<String> abbreviations = stringList(row.get("abbreviations"));
            List<String> foldedTextAliases = distinct(concat(aliasesCs, aliasesEn)).stream()
                    .map(SearchCatalogSidecarBuilder::folded)
                    .filter(alias -> !alias.isBlank())
                    .toList();
            List<String> foldedAbbreviations = abbreviations.stream()
                    .map(SearchCatalogSidecarBuilder::folded)
                    .filter(alias -> !alias.isBlank())
                    .toList();
            List<String> foldedAliases = distinct(concat(foldedTextAliases, foldedAbbreviations));
            return new ConceptRule(
                    CatalogMapSupport.str(row.get("id")),
                    aliasesCs,
                    aliasesEn,
                    abbreviations,
                    foldedAliases,
                    foldedTextAliases,
                    foldedAbbreviations,
                    stringList(row.get("tags")),
                    stringList(row.get("source_defaults")),
                    stringList(row.get("secondary_concepts")),
                    stringList(row.get("negative_concepts")),
                    attributes);
        }
    }
}
