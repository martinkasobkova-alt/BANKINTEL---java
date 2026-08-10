package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ExploreSectionedSynthesisBulletTest {

    @Test
    void looksLikeBulletText_detectsDashBullets() {
        String text = "- Inflace 2,1 % v 2025\n- HDP roste mírně\n- Verdikt: smíšené";
        assertTrue(ExploreSectionedSynthesisService.looksLikeBulletText(text));
    }

    @Test
    void looksLikeBulletText_rejectsProse() {
        assertFalse(ExploreSectionedSynthesisService.looksLikeBulletText("Jeden souvislý odstavec bez odrážek."));
    }

    @Test
    void toBulletLines_splitsSentences() {
        String out = ExploreSectionedSynthesisService.toBulletLines("První věta. Druhá věta! Třetí?");
        assertTrue(out.startsWith("- "));
        assertEquals(3, out.lines().count());
    }

    @Test
    void activeSections_includesPoliticalAfterMacro() {
        List<String> ids =
                ExploreSectionMeta.activeSections().stream().map(s -> s.id()).collect(Collectors.toList());
        assertTrue(ids.contains("political_situation"));
        int macro = ids.indexOf("macro");
        int political = ids.indexOf("political_situation");
        assertTrue(macro >= 0);
        assertEquals(macro + 1, political);
    }

    @Test
    void politicalSection_isAlwaysRunEligible() {
        // Contract: political_situation must be in the always-run set used by synthesis (no series skip).
        assertTrue(
                ExploreSectionMeta.activeSections().stream()
                        .anyMatch(s -> "political_situation".equals(s.id())
                                && "political_situation_analysis".equals(s.analysisKey())));
    }
}
