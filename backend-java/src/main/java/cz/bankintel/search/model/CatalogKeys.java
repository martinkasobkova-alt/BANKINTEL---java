package cz.bankintel.search.model;

/** Stable JSON / row field names for catalog search — avoids magic strings across the package. */
public final class CatalogKeys {

    public static final String SET_ID = "set_id";
    public static final String SOURCE = "source";
    public static final String SOURCE_TYPE = "source_type";
    public static final String CATALOG_ID = "catalog_id";
    public static final String CATALOG_LABEL = "catalog_label";
    public static final String NAME = "name";
    public static final String TITLE = "title";
    public static final String FULL_PATH = "full_path";
    public static final String ROW = "row";
    public static final String QUERY = "query";
    public static final String Q = "q";
    public static final String SOURCES = "sources";
    public static final String SEARCH_TERMS = "search_terms";
    public static final String INDEX_PROBE_TERMS = "index_probe_terms";
    public static final String SEMANTIC_PROFILE = "semantic_profile";
    public static final String NORMALIZED_QUERY_CZ = "normalized_query_cz";
    public static final String ENGLISH_QUERY = "english_query";
    public static final String INDICATORS = "indicators";
    public static final String QUERY_VARIANTS = "query_variants";
    public static final String QUERY_SHAPE = "query_shape";
    public static final String METRIC_TERMS = "metric_terms";
    public static final String DOMAIN_TERMS = "domain_terms";
    public static final String PRIMARY_CONCEPTS = "primary_concepts";
    public static final String INSTITUTIONAL_SECTORS = "institutional_sectors";
    public static final String METRIC_INTENTS = "metric_intents";
    public static final String REQUIRED_GEO_CODES = "required_geo_codes";
    public static final String STRUCTURED_SEMANTIC_STATUS = "_structured_semantic_status";
    public static final String STRUCTURED_SEMANTIC_EVIDENCE = "_structured_semantic_evidence";
    public static final String ACTIVE_GROUPS = "active_groups";
    public static final String LIKELY_SOURCES = "likely_sources";
    public static final String GEO_INTENT = "geo_intent";
    public static final String TOPIC = "topic";
    public static final String COUNTRY_HINT = "country_hint";
    public static final String PLANNER = "planner";
    public static final String SEARCH_SCORE = "_search_score";
    public static final String METADATA_SCORE = "_metadata_score";
    public static final String LEXICAL_SCORE = "_lexical_score";
    public static final String TITLE_SCORE = "_title_score";
    public static final String FTS_RANK = "_fts_rank";
    public static final String MATCH = "_match";
    public static final String GEO_ADJUSTMENT = "_geo_adjustment";
    public static final String SIDECAR_RESCUE = "_sidecar_rescue";
    public static final String PREVIEW_STATUS = "preview_status";
    public static final String PREVIEW_AVAILABLE = "preview_available";
    public static final String STATUS = "status";
    public static final String RESULT_TIER = "result_tier";
    public static final String WHY_RELEVANT = "why_relevant";
    public static final String VERIFIED = "verified";
    public static final String POSSIBLE = "possible";
    public static final String ANSWER = "answer";

    private CatalogKeys() {}
}
