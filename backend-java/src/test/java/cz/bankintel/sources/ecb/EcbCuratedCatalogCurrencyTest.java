package cz.bankintel.sources.ecb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EcbCuratedCatalogCurrencyTest {

    private EcbCuratedCatalog catalog;
    private EcbAvailabilityService availability;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        catalog = new EcbCuratedCatalog(objectMapper);
        catalog.load();
        availability = new EcbAvailabilityService(objectMapper, catalog);
        availability.load();
    }

    @Test
    void mirKeysUseCountryCurrencyForEuroAndNonEuroEuMembers() {
        Map<String, String> countryCurrencies = Map.ofEntries(
                Map.entry("CZ", "CZK"),
                Map.entry("PL", "PLN"),
                Map.entry("HU", "HUF"),
                Map.entry("SE", "SEK"),
                Map.entry("DK", "DKK"),
                Map.entry("RO", "RON"),
                Map.entry("BG", "BGN"),
                Map.entry("AT", "EUR"),
                Map.entry("DE", "EUR"),
                Map.entry("FR", "EUR"));
        List<String> indicatorIds = List.of(
                "sazba_hypoteky",
                "sazba_uvery_podniky",
                "sazba_vklady_domacnosti",
                "sazba_vklady_podniky");

        for (Map.Entry<String, String> country : countryCurrencies.entrySet()) {
            for (String indicatorId : indicatorIds) {
                Map<String, Object> indicator = catalog.indicatorById(indicatorId);

                EcbCuratedCatalog.SdmxKey key = catalog.sdmxKeyForCountry(indicator, country.getKey());

                assertEquals("MIR", key.flow(), country.getKey() + " " + indicatorId);
                assertTrue(
                        key.key().contains("." + country.getValue() + "."),
                        () -> country.getKey() + " " + indicatorId + " should use " + country.getValue()
                                + " in " + key.key());
            }
        }
    }

    @Test
    void availabilityKeepsMirForEuNonEuroMembersAndRejectsUnsupportedExternalCountries() {
        List<String> supportedEuNonEuroCountries = List.of("CZ", "PL", "HU", "SE", "DK", "RO", "BG");
        List<String> indicatorIds = List.of(
                "sazba_hypoteky",
                "sazba_uvery_podniky",
                "sazba_vklady_domacnosti",
                "sazba_vklady_podniky");

        for (String country : supportedEuNonEuroCountries) {
            for (String indicatorId : indicatorIds) {
                assertTrue(
                        availability.isPairAvailable(country, indicatorId),
                        country + " " + indicatorId + " should stay available when the country currency is used");
            }
        }

        for (String country : List.of("NO", "US")) {
            for (String indicatorId : indicatorIds) {
                assertFalse(
                        availability.isPairAvailable(country, indicatorId),
                        country + " " + indicatorId + " should not be offered as verified ECB MIR data");
            }
        }
    }
}
