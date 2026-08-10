package cz.bankintel.sources.alphavantage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import cz.bankintel.repository.SourceRepository;
import cz.bankintel.security.AdminAccess;
import cz.bankintel.service.sources.SourceService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Regression test for the ported {@code POST /api/alphavantage/catalog/cache/invalidate} behaviour
 * (alphavantage_catalog_routes.py, ř. 160): admin-only, defaults symbol to {@code "*"}.
 */
@ExtendWith(MockitoExtension.class)
class AlphaVantageCatalogServiceInvalidateCacheTest {

    @Mock
    private SourceRepository sourceRepository;

    @Mock
    private SourceService sourceService;

    @Mock
    private AdminAccess adminAccess;

    private AlphaVantageCatalogService service;

    @BeforeEach
    void setUp() {
        service = new AlphaVantageCatalogService(sourceRepository, sourceService, adminAccess);
    }

    @Test
    void invalidateCacheDefaultsToWildcardWhenNoSymbolGiven() {
        Map<String, Object> result = service.invalidateCache(Map.of());

        assertEquals(true, result.get("ok"));
        assertEquals("*", result.get("symbol"));
        assertNull(result.get("function"));
        verify(adminAccess).requireAdmin();
    }

    @Test
    void invalidateCacheEchoesUppercasedSymbolAndFunction() {
        Map<String, Object> result = service.invalidateCache(Map.of("symbol", "aapl", "function", "time_series_daily"));

        assertEquals(true, result.get("ok"));
        assertEquals("AAPL", result.get("symbol"));
        assertEquals("TIME_SERIES_DAILY", result.get("function"));
    }

    @Test
    void invalidateCacheHandlesNullPayload() {
        Map<String, Object> result = service.invalidateCache(null);

        assertEquals(true, result.get("ok"));
        assertEquals("*", result.get("symbol"));
    }

    @Test
    void invalidateCacheRequiresAdmin() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required"))
                .when(adminAccess)
                .requireAdmin();

        assertThrows(ResponseStatusException.class, () -> service.invalidateCache(Map.of()));
    }
}
