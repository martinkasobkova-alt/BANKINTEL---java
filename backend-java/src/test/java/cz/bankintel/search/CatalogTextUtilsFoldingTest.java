package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for the unified text-normalization behind {@link CatalogTextUtils#foldAscii}
 * and {@link CatalogSearchSynonyms#foldCs}. {@code foldCs} used to run its own NFD-based fold
 * (catalog_search_synonyms.py port); it now delegates to {@code foldAscii}'s NFKD-based fold plus
 * its own whitespace-collapsing. These tests confirm the diacritic-stripping result is unchanged
 * for the Czech alphabet (NFD and NFKD canonical decomposition are equivalent for plain Latin
 * letters with diacritics, so consolidating on foldAscii's NFKD implementation is safe).
 */
class CatalogTextUtilsFoldingTest {

    @Test
    void foldAsciiStripsAllCzechDiacritics() {
        assertEquals("acdeeinorstuuyz", CatalogTextUtils.foldAscii("áčděéíňořšťuůýž"));
        assertEquals("acdeeinorstuuyz", CatalogTextUtils.foldAscii("ÁČDĚÉÍŇOŘŠŤUŮÝŽ"));
    }

    @Test
    void foldAsciiHandlesEachRequestedDiacriticLetter() {
        assertEquals("e", CatalogTextUtils.foldAscii("ě"));
        assertEquals("s", CatalogTextUtils.foldAscii("š"));
        assertEquals("c", CatalogTextUtils.foldAscii("č"));
        assertEquals("r", CatalogTextUtils.foldAscii("ř"));
        assertEquals("z", CatalogTextUtils.foldAscii("ž"));
        assertEquals("y", CatalogTextUtils.foldAscii("ý"));
        assertEquals("a", CatalogTextUtils.foldAscii("á"));
        assertEquals("i", CatalogTextUtils.foldAscii("í"));
        assertEquals("e", CatalogTextUtils.foldAscii("é"));
    }

    @Test
    void foldCsMatchesFoldAsciiForCzechWords() {
        String[] samples = {
            "inflace Česko", "nezaměstnanost Slovensko", "cena ropy", "úrokové sazby ČNB", "ěščřžýáíé"
        };
        for (String sample : samples) {
            String viaFoldAscii = CatalogTextUtils.foldAscii(sample).replaceAll("\\s+", " ").trim();
            assertEquals(
                    viaFoldAscii,
                    CatalogSearchSynonyms.foldCs(sample),
                    "foldCs must stay equivalent to foldAscii + whitespace collapse for: " + sample);
        }
    }

    @Test
    void foldCsCollapsesWhitespaceLikeBefore() {
        assertEquals("inflace cesko", CatalogSearchSynonyms.foldCs("  inflace   Česko  "));
    }

    @Test
    void foldCsStripsDiacriticsForAllRequestedLetters() {
        String folded = CatalogSearchSynonyms.foldCs("ěščřžýáíé");
        assertEquals("escrzyaie", folded);
        assertFalse(folded.matches(".*[ěščřžýáíé].*"));
    }
}
