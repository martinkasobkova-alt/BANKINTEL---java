package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

class CatalogCountryAliasRegistryTest {

    @Test
    void titleIndexFindsExplicitCountryName() {
        assertEquals(
                "CZ",
                CatalogCountryAliasRegistry.countryCodeInTitle("US Dollar Exchange Rate for Czech Republic")
                        .orElseThrow());
    }

    @Test
    void titleIndexDoesNotTreatShortIsoCodeAsOrdinaryWord() {
        assertTrue(CatalogCountryAliasRegistry.countryCodeInTitle("Interest rate at commercial banks").isEmpty());
    }

    static Stream<Arguments> countryQueries() {
        return Stream.of(
                Arguments.of("inflace Francie", "FR"),
                Arguments.of("gdp Italy", "IT"),
                Arguments.of("nezamestnanost Japonsko", "JP"),
                Arguments.of("data China", "CN"),
                Arguments.of("statistiky Spanelsko", "ES"),
                Arguments.of("hdp Nemecko", "DE"),
                Arguments.of("inflace Madarsko", "HU"),
                Arguments.of("unemployment Sweden", "SE"),
                Arguments.of("inflation Portugal", "PT"),
                Arguments.of("ekonomika Rakousko", "AT"),
                Arguments.of("indicators Norway", "NO"),
                Arguments.of("hicp Belgie", "BE"),
                Arguments.of("cpi Netherlands", "NL"),
                Arguments.of("data Romania", "RO"),
                Arguments.of("statistiky Bulharsko", "BG"),
                Arguments.of("gdp Hungary", "HU"),
                Arguments.of("inflation Greece", "GR"),
                Arguments.of("data Denmark", "DK"),
                Arguments.of("hdp Finland", "FI"),
                Arguments.of("unemployment Ireland", "IE"),
                Arguments.of("inflace Slovensko", "SK"));
    }

    @ParameterizedTest
    @MethodSource("countryQueries")
    void detectsCountryFromQuery(String query, String expectedCode) {
        var geo = CatalogGeoIntent.detectGeoIntent(query);
        List<String> codes = CatalogGeoIntent.requestedGeoCodes(geo);
        assertFalse(codes.isEmpty(), "no geo for: " + query);
        assertTrue(codes.contains(expectedCode), query + " -> " + codes);
    }

    @ParameterizedTest
    @MethodSource("countryQueries")
    void registryHasAliasesForCode(String query, String expectedCode) {
        List<String> aliases = CatalogCountryAliasRegistry.aliasesFor(expectedCode);
        assertFalse(aliases.isEmpty(), expectedCode);
    }
}
