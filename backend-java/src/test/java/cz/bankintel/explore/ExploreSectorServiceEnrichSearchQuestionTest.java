package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@code enrichSearchQuestion} blends a resolved catalog segment label into the search text so a
 * generic/colloquial term ("továrna") that the LLM grounded to a real segment ("Zpracovatelský
 * průmysl") also benefits from the deterministic keyword-based intent detection that already
 * works for real segment names - confirmed live: "továrna" alone activated no production intent,
 * appending the resolved label fixed it.
 */
class ExploreSectorServiceEnrichSearchQuestionTest {

    @Test
    void appendsResolvedSectorLabelWhenNotAlreadyPresent() {
        String enriched = ExploreSectorService.enrichSearchQuestion(
                "Ma smysl investovat do tovarny v Nemecku nebo Italii?", "Zpracovatelský průmysl");

        assertEquals(
                "Ma smysl investovat do tovarny v Nemecku nebo Italii? Zpracovatelský průmysl", enriched);
    }

    @Test
    void doesNotDuplicateWhenQuestionAlreadyMentionsTheSectorLabel() {
        String enriched = ExploreSectorService.enrichSearchQuestion(
                "Jak se vyvíjí stavebnictví v Německu?", "stavebnictví");

        assertEquals("Jak se vyvíjí stavebnictví v Německu?", enriched);
    }

    @Test
    void isCaseInsensitiveWhenCheckingForDuplication() {
        String enriched = ExploreSectorService.enrichSearchQuestion(
                "Jak se vyvíjí STAVEBNICTVÍ v Německu?", "stavebnictví");

        assertEquals("Jak se vyvíjí STAVEBNICTVÍ v Německu?", enriched);
    }

    @Test
    void returnsQuestionUnchangedWhenSectorIsBlank() {
        assertEquals("Jaká je nezaměstnanost?", ExploreSectorService.enrichSearchQuestion("Jaká je nezaměstnanost?", ""));
        assertEquals("Jaká je nezaměstnanost?", ExploreSectorService.enrichSearchQuestion("Jaká je nezaměstnanost?", null));
    }

    @Test
    void returnsQuestionUnchangedWhenQuestionIsBlank() {
        assertEquals("", ExploreSectorService.enrichSearchQuestion("", "stavebnictví"));
        assertEquals(null, ExploreSectorService.enrichSearchQuestion(null, "stavebnictví"));
    }
}
