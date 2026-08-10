package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.security.AdminAccess;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogAvailabilityServiceTest {

    @TempDir
    Path tempDir;

    private CatalogAvailabilityService service;

    @BeforeEach
    void setUp() {
        CatalogSearchProperties props = mock(CatalogSearchProperties.class);
        when(props.indexDir()).thenReturn(tempDir);
        AdminAccess adminAccess = mock(AdminAccess.class);
        when(adminAccess.requireAdmin()).thenReturn(new UserEntity());
        service = new CatalogAvailabilityService(props, new ObjectMapper(), adminAccess);
    }

    @Test
    void statusReturnsEmptyCountsForMissingIndex() {
        Map<String, Object> status = service.status("fred");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Integer>> sources = (Map<String, Map<String, Integer>>) status.get("sources");
        assertTrue(sources.containsKey("fred"));
        assertEquals(0, sources.get("fred").get("alive"));
    }

    @Test
    void buildRejectsUnknownSource() {
        Map<String, Object> out = service.build("unknown-src", 10, 0, 0, "");
        assertFalse((Boolean) out.get("ok"));
    }

    @Test
    void buildDocumentsStubForKnownSource() {
        Map<String, Object> out = service.build("fred", 10, 0, 0, "");
        assertFalse((Boolean) out.get("ok"));
        assertTrue(String.valueOf(out.get("limits")).contains("Python"));
    }

    private static void assertEquals(int expected, Integer actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual != null ? actual : 0);
    }
}
