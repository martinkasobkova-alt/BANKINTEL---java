package cz.bankintel.service.homepage.resolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cz.bankintel.repository.DatasetRepository;
import cz.bankintel.repository.RecordRepository;
import cz.bankintel.repository.SourceRepository;
import cz.bankintel.service.access.FeatureAccessService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Dělení grafu podle dimenze — výběr hodnot, popisky a srovnání hodnot.
 *
 * Kontext: uživatel si v katalogovém náhledu naklikal sloupec za každou zemi, uložil to na
 * dashboard a widget vykreslil 0 sloupců. Ukázalo se, že {@code chart_series_dim_values} se
 * sice ukládalo, ale nikdo ho nečetl, a že se na srovnání hodnot pouštěl pivot přes čas.
 */
class DatasetViewResolverPivotTest {

    private DatasetViewResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DatasetViewResolver(
                mock(SourceRepository.class),
                mock(DatasetRepository.class),
                mock(RecordRepository.class),
                mock(FeatureAccessService.class));
    }

    /** Eurostat ROE: kód země, čitelný název země vedle něj, roční hodnota. */
    private static List<Map<String, Object>> roeRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Object[][] data = {
            {"AT", "Rakousko", "2024", 9.1}, {"AT", "Rakousko", "2025", 9.6},
            {"BE", "Belgie", "2024", 10.2}, {"BE", "Belgie", "2025", 10.5},
            {"CZ", "Česko", "2024", 14.8}, {"CZ", "Česko", "2025", 15.3},
            {"DE", "Německo", "2024", 5.4}, {"DE", "Německo", "2025", 5.9},
        };
        for (Object[] d : data) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("geo", d[0]);
            row.put("geo_label", d[1]);
            row.put("period", d[2]);
            row.put("value", d[3]);
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> baseCfg() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("title", "Ziskovost bank");
        cfg.put("chart_series_dim", "geo");
        cfg.put("x_field", "period");
        cfg.put("y_field", "value");
        cfg.put("agg", "last");
        return cfg;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("rows");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> seriesOf(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("series");
    }

    @Test
    void pivotPouzijeNazvyZemiMistoKodu() {
        Map<String, Object> out = resolver.resolveFromRows(roeRows(), baseCfg(), "external_catalog_chart", null);

        assertThat(seriesOf(out)).extracting(s -> s.get("label"))
                .containsExactly("Rakousko", "Belgie", "Česko", "Německo");
        // Kód zůstává k dispozici — legenda je pro člověka, ale navázání na data musí přežít.
        assertThat(seriesOf(out)).extracting(s -> s.get("code")).containsExactly("AT", "BE", "CZ", "DE");
    }

    @Test
    void pivotRespektujeVybraneHodnoty() {
        Map<String, Object> cfg = baseCfg();
        cfg.put("chart_series_dim_values", List.of("CZ", "DE"));

        Map<String, Object> out = resolver.resolveFromRows(roeRows(), cfg, "external_catalog_chart", null);

        assertThat(seriesOf(out)).extracting(s -> s.get("label")).containsExactly("Česko", "Německo");
    }

    @Test
    void neznamyVyberNezpusobiPrazdnyGraf() {
        Map<String, Object> cfg = baseCfg();
        // Uložený výběr se v datech nenajde (zdroj přeznačil kódy). Prázdný graf je horší
        // než graf se všemi zeměmi — uživatel aspoň vidí, co v datech je.
        cfg.put("chart_series_dim_values", List.of("XX", "YY"));

        Map<String, Object> out = resolver.resolveFromRows(roeRows(), cfg, "external_catalog_chart", null);

        assertThat(seriesOf(out)).hasSize(4);
    }

    @Test
    void srovnaniHodnotDaSloupecZaKazdouZemiSPoslednimUdajem() {
        Map<String, Object> cfg = baseCfg();
        cfg.put("chart_data_mode", "latest");

        Map<String, Object> out = resolver.resolveFromRows(roeRows(), cfg, "external_catalog_chart", null);

        // Jedna řada, čtyři body — ne čtyři řady přes dva roky.
        assertThat(out.get("series")).isNull();
        assertThat(out.get("chart_data_mode")).isEqualTo("latest");
        assertThat(rowsOf(out)).extracting(r -> r.get("x"))
                .containsExactly("Rakousko", "Belgie", "Česko", "Německo");
        assertThat(rowsOf(out)).extracting(r -> r.get("y"))
                .containsExactly(9.6, 10.5, 15.3, 5.9);
        assertThat(rowsOf(out)).allSatisfy(r -> assertThat(r.get("period")).isEqualTo("2025"));
    }

    @Test
    void srovnaniHodnotUmiRaditPodleVelikosti() {
        Map<String, Object> cfg = baseCfg();
        cfg.put("chart_data_mode", "latest");
        cfg.put("chart_sort_order", "desc");

        Map<String, Object> out = resolver.resolveFromRows(roeRows(), cfg, "external_catalog_chart", null);

        assertThat(rowsOf(out)).extracting(r -> r.get("x"))
                .containsExactly("Česko", "Belgie", "Rakousko", "Německo");
    }

    @Test
    void srovnaniHodnotUnesePadesatZemiTamKdeCasovaRadaUrizneNaDvanacti() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 29; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("geo", String.format("G%02d", i));
            row.put("period", "2025");
            row.put("value", (double) i);
            rows.add(row);
        }
        Map<String, Object> cfg = baseCfg();
        cfg.put("chart_data_mode", "latest");

        Map<String, Object> out = resolver.resolveFromRows(rows, cfg, "external_catalog_chart", null);

        // 29 zemí ROE se dřív do stropu 12 pro časové řady nevešlo.
        assertThat(rowsOf(out)).hasSize(29);
    }

    @Test
    void filtrDimenzeUnesevicHodnot() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("title", "Ziskovost bank");
        cfg.put("x_field", "period");
        cfg.put("y_field", "value");
        cfg.put("agg", "last");
        // Seznam se dřív zřetězil na "[CZ, DE]", nerovnal se ničemu a vyfiltroval všechny řádky.
        cfg.put("dimension_filters", Map.of("geo", List.of("CZ", "DE")));

        Map<String, Object> out = resolver.resolveFromRows(roeRows(), cfg, "external_catalog_chart", null);

        assertThat(rowsOf(out)).isNotEmpty();
    }
}
