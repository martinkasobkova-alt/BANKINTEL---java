package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatalogPreviewGeoValidatorTest {

    @Test
    void rejectsPreviewRowsForDifferentCountry() {
        Map<String, Object> geoIntent = Map.of("type", "country", "country_code", "ES", "country_codes", List.of("ES"));
        Map<String, Object> preview = Map.of("rows", List.of(Map.of("geo", "MD", "value", 204.1)));

        CatalogPreviewGeoValidator.GeoPreviewCheck check =
                CatalogPreviewGeoValidator.validate(Map.of(), preview, geoIntent);

        assertFalse(check.matches());
        assertTrue(check.observedCodes().contains("MD"));
    }

    @Test
    void acceptsPreviewRowsForRequestedCountry() {
        Map<String, Object> geoIntent = Map.of("type", "country", "country_code", "ES", "country_codes", List.of("ES"));
        Map<String, Object> preview = Map.of("rows", List.of(Map.of("REF_AREA", "ESP", "value", 3.2)));

        CatalogPreviewGeoValidator.GeoPreviewCheck check =
                CatalogPreviewGeoValidator.validate(Map.of(), preview, geoIntent);

        assertTrue(check.matches());
        assertTrue(check.observedCodes().contains("ES"));
    }

    @Test
    void doesNotRejectWhenPreviewHasNoExplicitGeo() {
        Map<String, Object> geoIntent = Map.of("type", "country", "country_code", "ES", "country_codes", List.of("ES"));
        Map<String, Object> preview = Map.of("rows", List.of(Map.of("date", "2025", "value", 3.2)));

        CatalogPreviewGeoValidator.GeoPreviewCheck check =
                CatalogPreviewGeoValidator.validate(Map.of(), preview, geoIntent);

        assertTrue(check.matches());
    }
}
