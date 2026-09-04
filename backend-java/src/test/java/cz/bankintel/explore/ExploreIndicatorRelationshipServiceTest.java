package cz.bankintel.explore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Korelace v Manager Exploreru.
 *
 * Kontext: Pearson se dřív počítal na hladinách řad, ne na jejich mezidobních změnách. Dvě
 * makroukazatelové řady, které obě v čase rostou (mzdy, tržby, inflace...), tak vyšly jako silně
 * korelované, i když jejich skutečné výkyvy spolu vůbec nesouvisí — obě jen táhne stejný trend
 * ("spurious correlation"). Číslo navíc nikdy neprocházelo testem významnosti, takže korelace
 * spočtená z hrstky pozorování dostala nálepku "silná vazba" stejně sebejistě jako korelace z
 * desítek bodů.
 */
class ExploreIndicatorRelationshipServiceTest {

    private static Map<String, Object> item(String setId, String periodPattern, double... levels) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("set_id", setId);
        out.put("title", setId);
        List<Map<String, Object>> observations = new ArrayList<>();
        for (int i = 0; i < levels.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", String.format(periodPattern, i));
            row.put("value", levels[i]);
            observations.add(row);
        }
        out.put("observations", observations);
        return out;
    }

    @Test
    void mezidobniZmenyOdstraniSpolecnyTrend() {
        // Obě řady v čase rostou skoro lineárně (na hladinách by tedy vyšly jako silně pozitivně
        // korelované), ale jejich mezidobní přírůstky jsou přesně opačné (dB = 4 - dA) — skutečná
        // spoluproměnlivost je tedy dokonale NEGATIVNÍ, ne pozitivní.
        Map<String, Object> a = item("a", "2020-%02d", 0, 1, 4, 5, 8, 9, 12, 13, 16, 17);
        Map<String, Object> b = item("b", "2020-%02d", 0, 3, 4, 7, 8, 11, 12, 15, 16, 19);

        Map<String, Object> result = ExploreIndicatorRelationshipService.computeCorrelation(a, b, "test");

        assertThat(result).isNotNull();
        assertThat((Double) result.get("value")).isCloseTo(-1.0, within(1e-9));
        assertThat((Boolean) result.get("significant")).isTrue();
        assertThat((Integer) result.get("sample_points")).isEqualTo(9);
        assertThat((String) result.get("description")).contains("mezidobních změn").contains("statisticky průkazné");
    }

    @Test
    void maloPozorovaniOznaciNeprukaznost() {
        // r = 0.8 na pouhých 5 mezidobních změnách (n=6 pozorování) — dost na nálepku "silná",
        // ale s df=3 je kritická hodnota r přes 0,87. Bez testu významnosti by to vypadalo jistě.
        Map<String, Object> a = item("a", "2021-%02d", 100, 101, 103, 106, 110, 115);
        Map<String, Object> b = item("b", "2021-%02d", 50, 51, 54, 56, 61, 65);

        Map<String, Object> result = ExploreIndicatorRelationshipService.computeCorrelation(a, b, "test");

        assertThat(result).isNotNull();
        assertThat((Double) result.get("value")).isCloseTo(0.8, within(0.01));
        assertThat((Boolean) result.get("significant")).isFalse();
        assertThat((Integer) result.get("sample_points")).isEqualTo(5);
        assertThat((String) result.get("description")).contains("statisticky neprůkazné");
    }

    @Test
    void dostatecnyVzorekOznaciPrukaznost() {
        // Deset pozorování s čistě lineárním vztahem mezidobních změn (dB = 2*dA + 5) — r = 1,0
        // a při df=7 jasně nad kritickou hodnotou.
        Map<String, Object> a = item("a", "2022-%02d", 0, 2, 6, 7, 12, 15, 21, 23, 27, 30);
        Map<String, Object> b = item("b", "2022-%02d", 0, 9, 22, 29, 44, 55, 72, 81, 94, 105);

        Map<String, Object> result = ExploreIndicatorRelationshipService.computeCorrelation(a, b, "test");

        assertThat(result).isNotNull();
        assertThat((Double) result.get("value")).isEqualTo(1.0);
        assertThat((Boolean) result.get("significant")).isTrue();
        assertThat((String) result.get("description")).contains("statisticky průkazné");
    }

    @Test
    void nedostatekSpolecnychObdobiVratiNull() {
        // Pod MIN_OVERLAP_FOR_CORRELATION (6 společných období) se korelace vůbec nepočítá.
        Map<String, Object> a = item("a", "2023-%02d", 1, 2, 3, 4, 5);
        Map<String, Object> b = item("b", "2023-%02d", 5, 4, 3, 2, 1);

        assertThat(ExploreIndicatorRelationshipService.computeCorrelation(a, b, "test")).isNull();
    }

    // Živě potvrzeno: tři různé cesty načtení si period string berou z jiných syrových polí, žádná
    // ho nekanonizuje - stejné období tak mohlo mít "2024-01" na jedné straně a "2024-01-15" na
    // druhé, a přesná shoda klíče je nespárovala.

    @Test
    void canonicalPeriodTruncatesFullDateToYearMonth() {
        assertThat(ExploreIndicatorRelationshipService.canonicalPeriod("2024-01-15")).isEqualTo("2024-01");
        assertThat(ExploreIndicatorRelationshipService.canonicalPeriod("2024-01-15T00:00:00"))
                .isEqualTo("2024-01");
    }

    @Test
    void canonicalPeriodLeavesAlreadyCanonicalFormsUnchanged() {
        assertThat(ExploreIndicatorRelationshipService.canonicalPeriod("2024-01")).isEqualTo("2024-01");
        assertThat(ExploreIndicatorRelationshipService.canonicalPeriod("2024-Q1")).isEqualTo("2024-Q1");
        assertThat(ExploreIndicatorRelationshipService.canonicalPeriod("2024")).isEqualTo("2024");
    }

    @Test
    void correlationMatchesPeriodsWrittenAsFullDatesAgainstYearMonth() {
        // Stejná dvojice jako dostatecnyVzorekOznaciPrukaznost, jen řada "b" má období jako plné
        // datum (jak by přišla např. z manager-series-cache) místo YYYY-MM - bez kanonizace by
        // žádné období nebylo společné a výsledek by byl null.
        Map<String, Object> a = item("a", "2022-%02d", 0, 2, 6, 7, 12, 15, 21, 23, 27, 30);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("set_id", "b");
        b.put("title", "b");
        List<Map<String, Object>> observations = new ArrayList<>();
        double[] levels = {0, 9, 22, 29, 44, 55, 72, 81, 94, 105};
        for (int i = 0; i < levels.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            // "a" (přes item()'s "2022-%02d") má období 2022-00..2022-09 - "-15" dnem navíc musí
            // kanonizovat na PŘESNĚ tyhle stejné roky-měsíce, jinak se nic nespáruje.
            row.put("period", String.format("2022-%02d-15", i));
            row.put("value", levels[i]);
            observations.add(row);
        }
        b.put("observations", observations);

        Map<String, Object> result = ExploreIndicatorRelationshipService.computeCorrelation(a, b, "test");

        assertThat(result).isNotNull();
        assertThat((Double) result.get("value")).isEqualTo(1.0);
    }
}
