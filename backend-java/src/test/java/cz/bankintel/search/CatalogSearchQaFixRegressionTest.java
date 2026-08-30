package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regrese k nálezům z QA reportu 2026-08-30 (V3, V4, S4 a „neznámý zdroj").
 *
 * <p>Každý test drží jednu konkrétní chybu, která se v testu projeví přesně tak, jak ji viděl
 * uživatel: prázdný FTS výraz u čistě zeměpisného dotazu, chybějící prefix v došeptávači,
 * neomezená délka dotazu a neznámý zdroj, který se tvářil jako platný.
 */
class CatalogSearchQaFixRegressionTest {

    /** V3: „Germany" vracelo 0 výsledků, protože všechny tokeny vypadly jako geo a MATCH byl prázdný. */
    @Test
    void geoOnlyQueryStillProducesNonEmptyMatchExpression() {
        List<String> needles = CatalogTextUtils.needlesFromQuery("Germany");
        String matchExpr = CatalogTextUtils.buildFtsMatch(needles, "Germany");

        assertFalse("\"\"".equals(matchExpr), "geo-only query must not collapse to an empty MATCH");
        assertTrue(matchExpr.toLowerCase(java.util.Locale.ROOT).contains("germany"), matchExpr);
    }

    /** V3: geo se dřív uplatňovalo až po vytažení kandidátů; teď jde zatlačit rovnou do FTS dotazu. */
    @Test
    void geoAnchoredMatchAddsCountryTermsAsRequiredGroup() {
        List<String> needles = CatalogTextUtils.needlesFromQuery("GDP Germany");
        String base = CatalogTextUtils.buildFtsMatch(needles, "GDP Germany");
        String anchored = CatalogTextUtils.buildGeoAnchoredFtsMatch(base, "GDP Germany");

        assertNotNull(anchored, "a query naming a country must produce a geo-anchored expression");
        assertTrue(anchored.startsWith("(" + base + ") AND ("), anchored);
        assertTrue(anchored.toLowerCase(java.util.Locale.ROOT).contains("germany"), anchored);
    }

    @Test
    void geoAnchoredMatchIsNullWhenQueryNamesNoCountry() {
        List<String> needles = CatalogTextUtils.needlesFromQuery("unemployment rate");
        String base = CatalogTextUtils.buildFtsMatch(needles, "unemployment rate");

        assertNull(CatalogTextUtils.buildGeoAnchoredFtsMatch(base, "unemployment rate"));
    }

    /** V4: došeptávač na „infl" nevracel nic a napovídal až po dopsání celého slova. */
    @Test
    void suggestMatchMakesTheLastWordAPrefix() {
        String expr = CatalogTextUtils.buildFtsSuggestMatch("infl", List.of());

        assertTrue(expr.contains("\"infl\"*"), expr);
    }

    @Test
    void suggestMatchKeepsLeadingWordsExactAndPrefixesOnlyTheLastOne() {
        String expr = CatalogTextUtils.buildFtsSuggestMatch("mira inflac", List.of());

        assertTrue(expr.contains("\"mira\" "), expr);
        assertFalse(expr.contains("\"mira\"*"), expr);
        assertTrue(expr.contains("\"inflac\"*"), expr);
    }

    /** Příliš krátké poslední slovo se na prefix nerozšiřuje — „in*" by zabralo půlku indexu. */
    @Test
    void suggestMatchDoesNotPrefixVeryShortWords() {
        String expr = CatalogTextUtils.buildFtsSuggestMatch("in", List.of());

        assertFalse(expr.contains("*"), expr);
    }

    /** „Neznámý zdroj…" se nikdy nezobrazil — normalizeSearchSource neznámý řetězec nechává být. */
    @Test
    void unknownSourceIsRecognizedAsUnknown() {
        assertTrue(CatalogSourceRegistry.isKnownSearchSource(CatalogSourceRegistry.normalizeSearchSource("arad")));
        assertTrue(CatalogSourceRegistry.isKnownSearchSource(CatalogSourceRegistry.normalizeSearchSource("ecb")));
        assertTrue(CatalogSourceRegistry.isKnownSearchSource(CatalogSourceRegistry.normalizeSearchSource("oecd")));
        assertTrue(CatalogSourceRegistry.isKnownSearchSource(
                CatalogSourceRegistry.normalizeSearchSource("world_bank_data360")));

        assertFalse(CatalogSourceRegistry.isKnownSearchSource(
                CatalogSourceRegistry.normalizeSearchSource("neexistujici_zdroj")));
        assertFalse(CatalogSourceRegistry.isKnownSearchSource(""));
        assertFalse(CatalogSourceRegistry.isKnownSearchSource(null));
    }

    /**
     * V3 (skutečná příčina): řádek, jehož země PŘESNĚ odpovídá dotazu, se zahazoval jako
     * {@code hardReject}, takže dotaz na konkrétní zemi vracel z FRED/ECB/Eurostatu nulu.
     */
    @Test
    void rowMatchingTheRequestedCountryIsBoostedNotRejected() {
        java.util.Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent("GDP Germany");
        java.util.Map<String, Object> germanRow = java.util.Map.of(
                "source_type", "fred",
                "set_id", "CLVMNACSCAB1GQDE",
                "name", "Real Gross Domestic Product for Germany");

        CatalogGeoIntent.GeoRowAdjustment adj = CatalogGeoIntent.rowCountryGeoAdjustment(germanRow, geo);

        assertEquals("row_requested_country_match", adj.reason(), adj.toString());
        assertFalse(adj.hardReject(), "a row from the requested country must not be dropped: " + adj);
        assertTrue(adj.multiplier() > 1.0, adj.toString());
    }

    @Test
    void rowFromADifferentCountryIsStillRejected() {
        java.util.Map<String, Object> geo = CatalogGeoIntent.detectGeoIntent("GDP Germany");
        java.util.Map<String, Object> frenchRow = java.util.Map.of(
                "source_type", "fred",
                "set_id", "CLVMNACSCAB1GQFR",
                "name", "Real Gross Domestic Product for France");

        CatalogGeoIntent.GeoRowAdjustment adj = CatalogGeoIntent.rowCountryGeoAdjustment(frenchRow, geo);

        assertTrue(adj.hardReject(), adj.toString());
    }

    /**
     * QA kolo 2: FRED byl u dotazů bez výsledku nejpomalejší ze všech (`DEURHARMMDSMEI` 9,6 s).
     * Za to mohl `row_json LIKE '%…%'` fallback — plný sken 262 tis. řádků, naměřeno 8,4 s.
     * Velké zdroje ho už neplatí; sanity check, že se ta sada nezúžila na prázdno.
     */
    @Test
    void bigFtsSourcesAreKnownSoTheLikeFullScanCanBeSkipped() {
        assertTrue(CatalogSourceRegistry.BIG_FTS_SOURCES.contains("fred"), "fred must count as a big source");
        assertTrue(CatalogSourceRegistry.BIG_FTS_SOURCES.contains("ecb2"), "ecb2 must count as a big source");
        assertFalse(CatalogSourceRegistry.BIG_FTS_SOURCES.contains("arad"), "arad is small — keep its LIKE rescue");
        assertFalse(CatalogSourceRegistry.BIG_FTS_SOURCES.contains("csu"), "csu is small — keep its LIKE rescue");
    }

    /** S4: 30× zopakovaná fráze (390 znaků) trvala 15,4 s proti 10–530 ms u běžných dotazů. */
    @Test
    void repeatedTermsAreCollapsedAndLengthIsCapped() {
        String repeated = "míra inflace ".repeat(30).trim();

        String normalized = CatalogClassicSearchService.normalizeQuery(repeated);

        assertEquals("míra inflace", normalized);
        assertTrue(normalized.length() <= 200);
    }

    @Test
    void ordinaryQueryPassesThroughNormalizationUnchanged() {
        assertEquals("míra inflace", CatalogClassicSearchService.normalizeQuery("  míra   inflace  "));
        assertEquals("HDP", CatalogClassicSearchService.normalizeQuery("HDP"));
    }

    @Test
    void veryLongDistinctQueryIsTruncated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("term").append(i).append(' ');
        }

        String normalized = CatalogClassicSearchService.normalizeQuery(sb.toString());

        assertTrue(normalized.length() <= 200, normalized);
        assertTrue(normalized.startsWith("term0 term1 "), normalized);
    }
}
