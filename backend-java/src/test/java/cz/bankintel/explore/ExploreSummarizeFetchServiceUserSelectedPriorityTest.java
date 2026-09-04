package cz.bankintel.explore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cz.bankintel.connector.ConnectorFactory;
import cz.bankintel.explore.ExploreDtos.ExploreSummarizeSeriesItem;
import cz.bankintel.explore.manager.ManagerSeriesCacheReader;
import cz.bankintel.explore.manager.fetch.ManagerFetchRegistry;
import cz.bankintel.search.CatalogIndexStore;
import cz.bankintel.search.CatalogPreviewOrchestrator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Živě zjištěno v Manager Exploreru: Krok 1 slibuje „vámi vybrané řady vždy vstoupí do analýzy
 * (AI je neodfiltruje)", ale ručně vynucená katalogová řada („Monthly minimum wages") se do
 * finálního reportu nikdy nedostala. Příčina: appka posílá vynucené řady AŽ ZA automaticky
 * objevenými, a {@code fetchBatch} se zastaví, jakmile najde {@code cap} (14) úspěšných fetchů -
 * když auto-objevené řady samy daly 14 úspěchů dřív, na vynucenou řadu na konci seznamu nikdy
 * nedošlo, bez chyby, beze zmínky. Tenhle test ověřuje opravu: {@code user_selected} řady se
 * stabilně přeřadí na začátek, takže se vždy aspoň zkusí jako první.
 */
class ExploreSummarizeFetchServiceUserSelectedPriorityTest {

    private static ExploreSummarizeSeriesItem item(String setId, boolean userSelected) {
        return new ExploreSummarizeSeriesItem(
                "arad", setId, "Title " + setId, Map.of(), userSelected, null, false, null, null, null, null, null,
                null);
    }

    private static List<Map<String, Object>> observations(int n) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(Map.of("period", "2024-0" + (i + 1), "date", "2024-0" + (i + 1), "value", (double) i));
        }
        return out;
    }

    private static ExploreSummarizeFetchService newService(
            CatalogIndexStore indexStore, ManagerSeriesCacheReader cacheReader, ManagerFetchRegistry fetchRegistry) {
        when(indexStore.ftsDbAvailable()).thenReturn(true);
        when(indexStore.lookupRow(anyString(), anyString())).thenReturn(Optional.empty());
        when(fetchRegistry.tryFetch(any(), any())).thenReturn(Optional.empty());
        return new ExploreSummarizeFetchService(
                indexStore,
                mock(CatalogPreviewOrchestrator.class),
                mock(ConnectorFactory.class),
                cacheReader,
                fetchRegistry,
                mock(cz.bankintel.repository.UserUploadRepository.class),
                mock(cz.bankintel.service.myseries.SavedSeriesResolverService.class));
    }

    @Test
    void userSelectedSeriesIsAlwaysAttemptedEvenWhenAutoDiscoveredSeriesAloneFillTheCap() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        ManagerSeriesCacheReader cacheReader = mock(ManagerSeriesCacheReader.class);
        ManagerFetchRegistry fetchRegistry = mock(ManagerFetchRegistry.class);
        ExploreSummarizeFetchService service = newService(indexStore, cacheReader, fetchRegistry);
        when(cacheReader.readObservations(any(), any())).thenReturn(Optional.of(observations(3)));

        // 14 auto-objevených (userSelected=false) řad samo o sobě naplní cap (14) - vynucená
        // řada je appkou vždy odeslaná AŽ ZA nimi, přesně jak to dělá skutečný request payload.
        List<ExploreSummarizeSeriesItem> items = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            items.add(item("auto_" + i, false));
        }
        items.add(item("forced_wages", true));

        ExploreSummarizeFetchService.BatchResult result = service.fetchBatch(items, "CZ", 14);

        assertTrue(
                result.loaded().stream().anyMatch(row -> "forced_wages".equals(row.get("set_id"))),
                "user_selected řada 'forced_wages' se do výsledku vůbec nedostala: "
                        + result.loaded().stream().map(row -> row.get("set_id")).toList());
    }

    @Test
    void withoutAnyUserSelectedItemsOrderIsUnchanged() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        ManagerSeriesCacheReader cacheReader = mock(ManagerSeriesCacheReader.class);
        ManagerFetchRegistry fetchRegistry = mock(ManagerFetchRegistry.class);
        ExploreSummarizeFetchService service = newService(indexStore, cacheReader, fetchRegistry);
        when(cacheReader.readObservations(any(), any())).thenReturn(Optional.of(observations(3)));

        ExploreSummarizeFetchService.BatchResult result = service.fetchBatch(
                List.of(item("a", false), item("b", false), item("c", false)), "CZ", 14);

        assertEquals(
                List.of("a", "b", "c"),
                result.loaded().stream().map(row -> row.get("set_id")).toList());
    }

    @Test
    void multipleUserSelectedItemsKeepTheirRelativeOrderAtTheFront() {
        CatalogIndexStore indexStore = mock(CatalogIndexStore.class);
        ManagerSeriesCacheReader cacheReader = mock(ManagerSeriesCacheReader.class);
        ManagerFetchRegistry fetchRegistry = mock(ManagerFetchRegistry.class);
        ExploreSummarizeFetchService service = newService(indexStore, cacheReader, fetchRegistry);
        when(cacheReader.readObservations(any(), any())).thenReturn(Optional.of(observations(3)));

        ExploreSummarizeFetchService.BatchResult result = service.fetchBatch(
                List.of(item("auto_1", false), item("forced_1", true), item("auto_2", false), item("forced_2", true)),
                "CZ",
                14);

        assertEquals(
                List.of("forced_1", "forced_2", "auto_1", "auto_2"),
                result.loaded().stream().map(row -> row.get("set_id")).toList());
    }
}
