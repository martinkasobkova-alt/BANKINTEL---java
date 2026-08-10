package cz.bankintel.search;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CatalogSuggestServiceTest {

    private final CatalogIndexStore indexStore = Mockito.mock(CatalogIndexStore.class);
    private final CatalogSuggestService service = new CatalogSuggestService(indexStore);

    @AfterEach
    void shutdown() {
        service.shutdown();
    }

    @Test
    void allSourceAutocompleteUsesOnlyLocalFtsLanes() {
        when(indexStore.ftsSuggest(anyString(), anyInt(), anyList())).thenReturn(List.of());

        service.suggest("bank profitability", 12, null);

        verify(indexStore, times(CatalogSourceRegistry.SUGGEST_FTS_SOURCES.size()))
                .ftsSuggest(anyString(), anyInt(), anyList());
        verify(indexStore, never()).searchSource(anyString(), anyString(), anyInt());
    }

    @Test
    void oecdScopeUsesNormalizedFtsSource() {
        when(indexStore.ftsSuggest(anyString(), anyInt(), anyList())).thenReturn(List.of());

        service.suggest("inflation", 12, "oecd");

        verify(indexStore).ftsSuggest("inflation", 12, List.of("oecd4"));
        verify(indexStore, never()).searchSource(anyString(), anyString(), anyInt());
    }
}
