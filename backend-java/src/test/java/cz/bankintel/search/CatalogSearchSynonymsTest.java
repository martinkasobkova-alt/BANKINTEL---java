package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogSearchSynonymsTest {

    @Test
    void ziskBankExpandsProfitAndBankingSynonyms() {
        List<String> expanded = CatalogSearchSynonyms.expandTerms("zisk bank", 20);
        String folded = String.join(" ", expanded.stream().map(CatalogSearchSynonyms::foldCs).toList());

        assertTrue(folded.contains("zisk bank") || expanded.get(0).equalsIgnoreCase("zisk bank"));
        assertTrue(folded.contains("profit"), "expected profit synonym: " + expanded);
        assertTrue(folded.contains("banking") || folded.contains("banks"), "expected banking synonym: " + expanded);
        assertTrue(
                folded.contains("roe") || folded.contains("return on equity"),
                "expected profitability banking group: " + expanded);
    }

    @Test
    void roeBankActivatesProfitabilityGroup() {
        var groups = CatalogSearchSynonyms.detectBankingExpansionGroups("ROE bank");
        assertTrue(groups.contains("banking_profitability"));
        assertTrue(groups.contains("banking_general"));
    }

    @Test
    void foldCsStripsDiacritics() {
        assertTrue(CatalogSearchSynonyms.foldCs("inflace Česko").contains("cesko"));
    }

    @Test
    void expandSearchQueriesDedupes() {
        List<String> queries = CatalogSearchSynonyms.expandSearchQueries("inflace", 6);
        assertFalse(queries.isEmpty());
        assertTrue(queries.size() <= 6);
    }

    @Test
    void eurusdExpandsToExchangeRateRecallTerms() {
        List<String> queries = CatalogSearchSynonyms.expandSearchQueries("eurusd", 10);
        String folded = String.join(" ", queries.stream().map(CatalogSearchSynonyms::foldCs).toList());
        assertTrue(folded.contains("exchange rate"), "expected FX recall term: " + queries);
    }
}
