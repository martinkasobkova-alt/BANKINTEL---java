package cz.bankintel.search.v2.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Cache klíč naplánovaného dotazu (planCacheKey) musí nést catalogVersion.
 *
 * Kontext: plán skládá jazykový model (SearchV2QueryPlanner); i s pevným seedem a teplotou 0
 * OpenAI nezaručuje bitově identický výstup napříč voláními. Klíč dřív catalogVersion neobsahoval
 * (na rozdíl od retrieval/final cache ve stejném souboru), takže po vypršení hodinového PLAN_TTL
 * mohl stejný dotaz vrátit jinou sadu výsledků, i když se katalog vůbec nezměnil. Teď nese
 * catalogVersion stejně jako ostatní cache klíče a PLAN_TTL je jen záložní limit (7 dní).
 */
class SearchV2ServicePlanCacheKeyTest {

    @Test
    void zmenaCatalogVersionZmeniKlic() {
        String key1 = SearchV2Service.planCacheKey(Map.of(), "mzdy", true, "v1");
        String key2 = SearchV2Service.planCacheKey(Map.of(), "mzdy", true, "v2");
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void stejnyVstupDavaStejnyKlic() {
        String key1 = SearchV2Service.planCacheKey(Map.of("geo", "CZ"), "mzdy", true, "v1");
        String key2 = SearchV2Service.planCacheKey(Map.of("geo", "CZ"), "mzdy", true, "v1");
        assertThat(key1).isEqualTo(key2);
    }

    @Test
    void ruznyDotazDavaRuznyKlic() {
        String key1 = SearchV2Service.planCacheKey(Map.of(), "mzdy", true, "v1");
        String key2 = SearchV2Service.planCacheKey(Map.of(), "nezaměstnanost", true, "v1");
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void ruznyUseAiDavaRuznyKlic() {
        String key1 = SearchV2Service.planCacheKey(Map.of(), "mzdy", true, "v1");
        String key2 = SearchV2Service.planCacheKey(Map.of(), "mzdy", false, "v1");
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void ruzneGeoDavaRuznyKlic() {
        String key1 = SearchV2Service.planCacheKey(Map.of("geo", "CZ"), "mzdy", true, "v1");
        String key2 = SearchV2Service.planCacheKey(Map.of("geo", "DE"), "mzdy", true, "v1");
        assertThat(key1).isNotEqualTo(key2);
    }
}
