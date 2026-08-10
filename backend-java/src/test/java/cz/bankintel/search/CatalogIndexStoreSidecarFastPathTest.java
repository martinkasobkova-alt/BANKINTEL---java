package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.model.CatalogHit;
import cz.bankintel.search.scoring.CatalogScoringPipeline;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

@ExtendWith(MockitoExtension.class)
class CatalogIndexStoreSidecarFastPathTest {

    @Mock
    private CatalogSearchProperties properties;

    @Mock
    private CatalogSearchMetadataSidecar metadataSidecar;

    @Mock
    private CatalogSqliteReadPool sqlitePool;

    @Mock
    private Environment environment;

    @Mock
    private CatalogScoringPipeline scoringPipeline;

    private CatalogIndexStore indexStore;

    @BeforeEach
    void setUp() {
        indexStore = new CatalogIndexStore(
                properties,
                new ObjectMapper(),
                metadataSidecar,
                sqlitePool,
                environment,
                new CatalogSearchResultCache(),
                scoringPipeline);
    }

    @Test
    void sidecarSearchHitsUsesMetadataRowsWithoutSqliteLookup() {
        String setId = "CBD2/Q.CZ.W0.11._Z._Z.A.A.I2004._Z._Z._Z._Z._Z._Z.PC";
        when(metadataSidecar.enabledForClassic()).thenReturn(true);
        when(metadataSidecar.sidecarRetrievalSetIds("ecb2", List.of("roa bank"), 40)).thenReturn(List.of(setId));
        when(metadataSidecar.getSearchMetadata("ecb2", setId, setId))
                .thenReturn(Map.of(
                        "human_label_en", "Return on assets (ROA) - Czech Republic - banks",
                        "human_label_cs", "Rentabilita aktiv (ROA) - Ceska republika - banky"));
        when(scoringPipeline.scoreAndRankAsMaps(eq("ecb2"), eq("roa bank"), anyList(), eq(10)))
                .thenAnswer(inv -> {
                    List<Map<String, Object>> rows = inv.getArgument(2);
                    return rows.stream()
                            .map(row -> {
                                Map<String, Object> copy = new LinkedHashMap<>(row);
                                copy.put("search_score", 100);
                                return copy;
                            })
                            .toList();
                });

        List<CatalogHit> hits = indexStore.sidecarSearchHits("ecb2", "roa bank", 10);

        assertEquals(1, hits.size());
        assertEquals("ecb2", hits.getFirst().sourceType());
        assertEquals(setId, hits.getFirst().setId());
        assertTrue(hits.getFirst().title().contains("ROA"));
        verifyNoInteractions(sqlitePool);
    }

    @Test
    void equivalentVariantsUseOneBatchedMetadataLookup() {
        List<String> variants = List.of("vyvoj mezd", "average wages");
        String setId = "wages-series";
        when(metadataSidecar.enabledForClassic()).thenReturn(true);
        when(metadataSidecar.sidecarRetrievalSetIds("eurostat", variants, 80)).thenReturn(List.of(setId));
        when(metadataSidecar.getSearchMetadata("eurostat", setId, setId))
                .thenReturn(Map.of("human_label_en", "Average wages"));
        when(scoringPipeline.scoreAndRankAsMaps(eq("eurostat"), eq("vyvoj mezd average wages"), anyList(), eq(80)))
                .thenAnswer(inv -> inv.getArgument(2));

        List<CatalogHit> hits = indexStore.sidecarSearchHits("eurostat", variants, 40);

        assertEquals(1, hits.size());
        verify(metadataSidecar).sidecarRetrievalSetIds("eurostat", variants, 80);
        verifyNoInteractions(sqlitePool);
    }
}
