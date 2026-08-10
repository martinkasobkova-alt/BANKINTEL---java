package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code reconcileCountry} cross-checks the LLM's own country guess against {@code
 * CatalogGeoIntent}'s deterministic alias-based detection - confirmed live: "Jak se vyvíjí
 * cestovní ruch v Řecku nebo Turecku?" came back from the LLM with {@code country=["RE","TR"]}
 * ("RE" is Réunion, not Greece - the real code is "GR"). The deterministic alias registry (used
 * pervasively elsewhere in this codebase for the same Czech/English country-name detection)
 * correctly maps "recko"→GR, so it must win over the LLM's guess whenever it confidently finds a
 * specific country set directly in the question's own text.
 */
class ExploreQueryUnderstandingServiceReconcileCountryTest {

    @Test
    void overridesAHallucinatedCodeWithTheDeterministicallyDetectedCountries() {
        Object result = ExploreQueryUnderstandingService.reconcileCountry(
                List.of("RE", "TR"), "Jak se vyvíjí cestovní ruch v Řecku nebo Turecku?");

        assertEquals(List.of("GR", "TR"), result);
    }

    @Test
    void keepsTheLlmAnswerWhenTheTextNamesNoDetectableCountry() {
        Object result = ExploreQueryUnderstandingService.reconcileCountry(
                "US", "Jak se vyvíjí zdravotnictví v regionu, který jsem popsala nepřímo?");

        assertEquals("US", result);
    }

    @Test
    void agreesWithTheLlmWhenItsGuessWasAlreadyCorrect() {
        Object result = ExploreQueryUnderstandingService.reconcileCountry(
                "DE", "Jak se vyvíjí výroba v Německu?");

        assertEquals("DE", result);
    }
}
