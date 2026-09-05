package cz.bankintel.explore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Živě zjištěno: appka slibovala 8 report sekcí, ale AI-vymýšlecí (fallback) cesta
 * ({@link ExploreSectorService#discoverIndicators}) vždy tagovala každou nemakro řadu natvrdo
 * jako "sector_indicators", i když prompt teď dovolí LLM navrhnout jemnější kategorii přímo -
 * spolehnout se na to je křehké. {@code refineReportSections} je záchranná síť: řádek, co pořád
 * nese jen obecné "sector_indicators", se zkusí dohledat jemněji podle vlastního obsahu.
 */
class ExploreSectorServiceReportSectionRefinementTest {

    private static Map<String, Object> row(String title, String managerCategory) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", title);
        out.put("indicator_name", title);
        out.put("manager_category", managerCategory);
        return out;
    }

    @Test
    void refinesAGenericSectorIndicatorRowByItsOwnContent() {
        List<Map<String, Object>> refined =
                ExploreSectorService.refineReportSections(List.of(row("Return on equity of banks", "sector_indicators")));

        assertThat(refined.getFirst().get("manager_category")).isEqualTo("financial_indicators");
    }

    @Test
    void leavesAnAlreadyFinerLlmChoiceUntouched() {
        // Prompt teď dovolí LLM navrhnout jemnější kategorii přímo - pokud to udělalo, refineReportSections
        // ji nepřepisuje na základě vlastního (možná jinak vyzařujícího) obsahu.
        List<Map<String, Object>> refined =
                ExploreSectorService.refineReportSections(List.of(row("Producer price index", "cost_indicators")));

        assertThat(refined.getFirst().get("manager_category")).isEqualTo("cost_indicators");
    }

    @Test
    void neverTouchesMacroRows() {
        List<Map<String, Object>> refined =
                ExploreSectorService.refineReportSections(List.of(row("GDP growth", "macro_indicators")));

        assertThat(refined.getFirst().get("manager_category")).isEqualTo("macro_indicators");
    }

    @Test
    void unmatchedContentStaysGenericSectorIndicators() {
        List<Map<String, Object>> refined = ExploreSectorService.refineReportSections(
                List.of(row("Industrial production of motor vehicles", "sector_indicators")));

        assertThat(refined.getFirst().get("manager_category")).isEqualTo("sector_indicators");
    }
}
