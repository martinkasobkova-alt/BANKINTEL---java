package cz.bankintel.sources.oecd4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.util.BankIntelDataPaths;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Živě ověřeno na REÁLNÝCH offline snímcích (`data/oecd4/*.json`), ne na syntetických datech -
 * různé OECD4 datasety mají úplně jiné SDMX dimenze a syntetická fixtura by snadno minula
 * skutečný tvar dat (přesně tahle třída chyb appku dřív dostala - viz komentář u
 * `Oecd4BrowseService.NON_DIMENSION_ROW_FIELDS`). Testy se samy přeskočí, pokud snímek v
 * prostředí chybí (offline mirror není nutná součást repa), aby na tom build nespadl.
 */
class Oecd4BrowseServiceTest {

    private final Oecd4BrowseService service = new Oecd4BrowseService(new ObjectMapper());

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> setsOf(Map<String, Object> countryNodeResponse) {
        Map<String, Object> countryNode = (Map<String, Object>) countryNodeResponse.get("country_node");
        return (List<Map<String, Object>>) countryNode.get("sets");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> queryParamsOf(Map<String, Object> set) {
        return (Map<String, Object>) set.get("query_params");
    }

    private static void assumeSnapshot(String key) {
        assumeTrue(
                Files.isRegularFile(BankIntelDataPaths.oecd4Dir().resolve(key + ".json")),
                key + ".json offline snímek není v tomhle prostředí stažen");
    }

    @Test
    void employmentLfs_czechia_noLongerCollapsesSexAndAgeIntoThreeSurvivors() {
        assumeSnapshot("employment_lfs");

        List<Map<String, Object>> sets = setsOf(service.getCountryNode("employment_lfs", "CZE"));

        // Živě zjištěno: dřív se 243 skutečně různých kombinací měřítko×pohlaví×věk smrsklo na 3
        // "přeživší" řádky, protože SEX/AGE/LABOUR_FORCE_STATUS nebyly v dedup podpisu vůbec.
        assertThat(sets).hasSize(243);

        Set<Object> setIds = sets.stream().map(s -> s.get("set_id")).collect(Collectors.toSet());
        assertThat(setIds).as("set_id musí být pro každou vrácenou řadu jedinečné").hasSameSizeAs(sets);

        long distinctNames = sets.stream().map(s -> s.get("name")).distinct().count();
        assertThat(distinctNames).as("názvy už nesmí být jeden opakovaný obecný řetězec").isGreaterThan(100);
    }

    @Test
    void industryServices_usaProductionVolume_getsSixDistinctlyNamedAndAddressableSeries() {
        assumeSnapshot("industry_services");

        List<Map<String, Object>> sets = setsOf(service.getCountryNode("industry_services", "USA"));
        List<Map<String, Object>> productionVolume = sets.stream()
                .filter(s -> "PRVM".equals(queryParamsOf(s).get("measure")))
                .toList();

        // Živě zjištěno: dřív bylo 6 řádků se jménem "Production volume" a VŠECHNY se stejným
        // set_id "OECD4|industry_services|USA|PRVM|A" - graf kterékoli karty by smíchal
        // Manufacturing/Industry(except construction)/Construction i sezónně (ne)očištěná data
        // dohromady, protože se `previewRows` nedalo poznat, kterou z 6 chce uživatel doopravdy.
        assertThat(productionVolume).hasSize(6);
        assertThat(productionVolume.stream().map(s -> s.get("set_id")).distinct().count()).isEqualTo(6);
        assertThat(productionVolume.stream().map(s -> s.get("name")).distinct().count()).isEqualTo(6);
        assertThat(productionVolume)
                .anySatisfy(s -> assertThat((String) s.get("name")).contains("Manufacturing"))
                .anySatisfy(s -> assertThat((String) s.get("name")).contains("Industry (except construction)"));
    }

    @Test
    void previewRows_filtersByExtraDimensionsInsteadOfBlendingThem() {
        assumeSnapshot("industry_services");

        List<Map<String, Object>> sets = setsOf(service.getCountryNode("industry_services", "USA"));
        Map<String, Object> manufacturingAdjusted = sets.stream()
                .filter(s -> "PRVM".equals(queryParamsOf(s).get("measure")))
                .filter(s -> "C".equals(queryParamsOf(s).get("activity")))
                .filter(s -> "Y".equals(queryParamsOf(s).get("adjustment")))
                .findFirst()
                .orElseThrow();

        List<Map<String, Object>> rows = service.previewRows(queryParamsOf(manufacturingAdjusted));

        assertThat(rows).isNotEmpty();
        assertThat(rows)
                .as("previewRows nesmí vrátit i jiné ACTIVITY/ADJUSTMENT, než na jaké karta ukazuje")
                .allSatisfy(r -> {
                    assertThat(r.get("ACTIVITY")).isEqualTo("C");
                    assertThat(r.get("ADJUSTMENT")).isEqualTo("Y");
                });
    }

    @Test
    void previewRows_withoutExtraDimensionParams_staysBackwardCompatibleForAlreadySavedSources() {
        assumeSnapshot("industry_services");
        // Zdroj uložený PŘED touhle opravou má v query_params jen measure/freq/unit_measure -
        // previewRows na něj nesmí spadnout, ani začít vyžadovat nová pole.
        Map<String, Object> legacyParams = Map.of(
                "oecd4_key", "industry_services",
                "oecd4_ref_area", "USA",
                "measure", "PRVM",
                "freq", "A");

        List<Map<String, Object>> rows = service.previewRows(legacyParams);

        assertThat(rows).isNotEmpty();
    }
}
