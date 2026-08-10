package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the "Evropa" continent-macro fix: the AI query-understanding step
 * sometimes collapses a continent-level question to {@code country=EU, geo_mode=countries}
 * instead of {@code geo_mode=continent, continent=europe} - live testing showed this made
 * {@link ExploreGeoResolver#resolve} treat "EU" as an unknown 2-letter country code and fall
 * into the generic "countries" branch, bypassing the continent member-list mechanism entirely.
 */
class ExploreGeoResolverTest {

    private static ExploreGeoResolver newResolver() {
        return new ExploreGeoResolver(new ExploreGeoCatalog(new ObjectMapper()));
    }

    @Test
    void nonBlankCountryOutranksAContradictoryNoneGeoMode() {
        // Confirmed live: "Jakým významem se Praha podílí na HDP Česka?" - the LLM understanding
        // returned country="CZ" AND geo_mode="none" together (Prague is a sub-national region with
        // no slot in the none|countries|continent schema, so the LLM fell back to "none" while
        // still naming CZ as an FYI). Trusting geo_mode over the named country silently discarded
        // all Czech context - geo.mode ended up "none", country_codes=[].
        ExploreGeoResolver resolver = newResolver();

        Map<String, Object> geo = resolver.resolve("CZ", "none", null);

        assertEquals("countries", geo.get("mode"));
        assertEquals(List.of("CZ"), geo.get("country_codes"));
    }

    @Test
    void blankCountryWithNoneGeoModeStillResolvesToGlobalContext() {
        // The fix must not affect the genuinely-no-country case - "none" still means "none" when
        // there is truly no country to fall back on.
        ExploreGeoResolver resolver = newResolver();

        Map<String, Object> geo = resolver.resolve("", "none", null);

        assertEquals("none", geo.get("mode"));
        assertEquals(List.of(), geo.get("country_codes"));
    }

    @Test
    void bareEuCountryCodeResolvesAsEuropeContinentNotAnUnknownCountry() {
        ExploreGeoResolver resolver = newResolver();

        Map<String, Object> geo = resolver.resolve("EU", "countries", null);

        assertEquals("continent", geo.get("mode"));
        assertEquals("europe", geo.get("continent_id"));
        assertEquals(
                List.of("CZ", "DE", "AT", "PL", "SK", "FR", "IT", "ES", "NL", "BE"), geo.get("country_codes"));
    }

    @Test
    void lowercaseEuCountryCodeIsAlsoTreatedAsEuropeContinent() {
        ExploreGeoResolver resolver = newResolver();

        Map<String, Object> geo = resolver.resolve("eu", "countries", null);

        assertEquals("continent", geo.get("mode"));
        assertEquals("europe", geo.get("continent_id"));
    }

    @Test
    void realTwoLetterCountryCodesAreUnaffectedByTheEuSafetyNet() {
        ExploreGeoResolver resolver = newResolver();

        Map<String, Object> geo = resolver.resolve("DE", "countries", null);

        assertEquals("countries", geo.get("mode"));
        assertNull(geo.get("continent_id"));
        assertEquals(List.of("DE"), geo.get("country_codes"));
    }

    @Test
    void euAsPartOfADelimitedListIsNotRewrittenToContinent() {
        // Only a BARE, non-delimited "EU" token triggers the safety net - a multi-country list
        // that happens to include the literal (invalid) token "EU" should not be silently upgraded
        // to the whole continent; it just keeps falling through as before (unknown token dropped).
        ExploreGeoResolver resolver = newResolver();

        Map<String, Object> geo = resolver.resolve("EU,DE", "countries", null);

        assertEquals("countries", geo.get("mode"));
        assertTrue(((List<?>) geo.get("country_codes")).contains("DE"));
    }

    @Test
    void explicitContinentModeStillWorksUnchanged() {
        ExploreGeoResolver resolver = newResolver();

        Map<String, Object> geo = resolver.resolve(null, "continent", "europe");

        assertEquals("continent", geo.get("mode"));
        assertEquals("europe", geo.get("continent_id"));
    }

    @Test
    void countryCodesFromExtractsListFromAResolvedGeoMap() {
        Map<String, Object> geo = Map.of("mode", "continent", "country_codes", List.of("cz", "de"));

        assertEquals(List.of("CZ", "DE"), ExploreGeoResolver.countryCodesFrom(geo));
    }

    @Test
    void countryCodesFromReturnsEmptyForNonMapOrMissingKey() {
        assertEquals(List.of(), ExploreGeoResolver.countryCodesFrom(null));
        assertEquals(List.of(), ExploreGeoResolver.countryCodesFrom("not a map"));
        assertEquals(List.of(), ExploreGeoResolver.countryCodesFrom(Map.of("mode", "none")));
    }

    @Test
    void isKnownDifferentContinentRecognizesCountriesOutsideTheSmallContinentModeAnchorList() {
        // UAE is nowhere in CONTINENTS' own ~30-country "major economies" member lists (those are
        // continent-mode anchors/defaults, not a classification table) - confirmed live, UAE
        // consumer-price data slipped through unfiltered for a France/Spain question because the
        // old reverse lookup (derived only from those lists) had no idea UAE existed, let alone
        // that it's on a different continent. The comprehensive geo-catalog.json country list
        // (~195 countries) must catch this.
        assertTrue(ExploreGeoResolver.isKnownDifferentContinent("AE", "FR"));
        assertTrue(ExploreGeoResolver.isKnownDifferentContinent("AE", "ES"));
    }

    @Test
    void isKnownDifferentContinentStillTreatsSameContinentCountriesOutsideTheAnchorListAsNotDifferent() {
        // Portugal is also outside CONTINENTS' small europe anchor list, but IS Europe - must not
        // be misclassified as "different continent" just because it's outside that short list.
        assertEquals(false, ExploreGeoResolver.isKnownDifferentContinent("PT", "FR"));
    }

    @Test
    void isKnownDifferentContinentStillCatchesTheOriginalUsVsEuropeCase() {
        assertTrue(ExploreGeoResolver.isKnownDifferentContinent("US", "DE"));
    }
}
