package cz.bankintel.service.homepage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cz.bankintel.repository.HomepageConfigRepository;
import cz.bankintel.service.homepage.resolver.ComputedViewWidgetResolver;
import cz.bankintel.service.homepage.resolver.ExternalCatalogChartWidgetResolver;
import cz.bankintel.service.homepage.resolver.SourceRecordsWidgetResolver;
import cz.bankintel.service.homepage.resolver.UserUploadChartWidgetResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * KPI dlaždice z katalogové řady (typ {@code external_catalog_chart}) — dopočet přes
 * ExternalCatalogChartWidgetResolver místo dřívější jediné větve pro {@code arad_view}.
 */
@ExtendWith(MockitoExtension.class)
class HomepageHeadlineKpiCatalogTest {

    @Mock private HomepageConfigRepository configRepository;
    @Mock private SourceRecordsWidgetResolver sourceRecordsWidgetResolver;
    @Mock private ExternalCatalogChartWidgetResolver externalCatalogChartWidgetResolver;
    @Mock private ComputedViewWidgetResolver computedViewWidgetResolver;
    @Mock private UserUploadChartWidgetResolver userUploadChartWidgetResolver;

    private HomepageHeadlineKpiService service() {
        return new HomepageHeadlineKpiService(
                configRepository,
                sourceRecordsWidgetResolver,
                externalCatalogChartWidgetResolver,
                computedViewWidgetResolver,
                userUploadChartWidgetResolver);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstKpi(Map<String, Object> out) {
        return ((List<Map<String, Object>>) out.get("kpis")).get(0);
    }

    private Map<String, Object> catalogKpi() {
        return Map.of(
                "id", "k1",
                "title", "Spotřebitelské ceny",
                "type", "external_catalog_chart",
                "config", Map.of("catalog", "csu", "set_id", "CEN0101CT02"));
    }

    @Test
    void katalogovaDlazdiceVezmePosledniHodnotuIObdobi() {
        when(externalCatalogChartWidgetResolver.resolve(any(), any()))
                .thenReturn(Map.of(
                        "unit", "index 2015=100",
                        "rows", List.of(
                                Map.of("period", "listopad 2025", "value", 155.5),
                                Map.of("period", "prosinec 2025", "value", 155.0))));

        Map<String, Object> kpi = firstKpi(service().resolveList(List.of(catalogKpi()), null));

        assertEquals(155.0, kpi.get("value"));
        assertEquals("prosinec 2025", kpi.get("period"));
        assertEquals("index 2015=100", kpi.get("unit"));
        assertEquals(155.5, kpi.get("prev_value"));
        assertEquals("down", kpi.get("trend"));
    }

    @Test
    void rostouciRadaMaTrendUp() {
        when(externalCatalogChartWidgetResolver.resolve(any(), any()))
                .thenReturn(Map.of("rows", List.of(
                        Map.of("x", "2024", "y", 100.0),
                        Map.of("x", "2025", "y", 104.0))));

        Map<String, Object> kpi = firstKpi(service().resolveList(List.of(catalogKpi()), null));

        assertEquals(104.0, kpi.get("value"));
        assertEquals("2025", kpi.get("period"));
        assertEquals("up", kpi.get("trend"));
    }

    @Test
    void prazdnaRadaNespadneAleRekneProc() {
        when(externalCatalogChartWidgetResolver.resolve(any(), any()))
                .thenReturn(Map.of("rows", List.of()));

        Map<String, Object> kpi = firstKpi(service().resolveList(List.of(catalogKpi()), null));

        assertNull(kpi.get("value"));
        assertTrue(String.valueOf(kpi.get("error")).contains("data"));
    }

    @Test
    void chybaZResolveruSePropisePresDoDlazdice() {
        when(externalCatalogChartWidgetResolver.resolve(any(), any()))
                .thenReturn(Map.of("error", "Řada je nedostupná."));

        Map<String, Object> kpi = firstKpi(service().resolveList(List.of(catalogKpi()), null));

        assertEquals("Řada je nedostupná.", kpi.get("error"));
        assertNull(kpi.get("value"));
    }

    @Test
    void aradDlazdiceKatalogovyResolverNevola() {
        when(sourceRecordsWidgetResolver.resolveAradView(any(), eq(null)))
                .thenReturn(Map.of("rows", List.of(Map.of("period", "2025", "value", 7.0))));

        Map<String, Object> arad = Map.of(
                "id", "k2", "title", "ARAD", "type", "arad_view", "config", Map.of());
        Map<String, Object> kpi = firstKpi(service().resolveList(List.of(arad), null));

        assertEquals(7.0, kpi.get("value"));
        verify(externalCatalogChartWidgetResolver, never()).resolve(any(), any());
    }

    @Test
    void neznamyTypVratiPrazdnouDlazdiciBezPadu() {
        Map<String, Object> unknown = Map.of("id", "k3", "title", "X", "type", "necoJineho");
        Map<String, Object> kpi = firstKpi(service().resolveList(List.of(unknown), null));

        assertNull(kpi.get("value"));
        assertEquals("neutral", kpi.get("trend"));
    }

    @Test
    void dlazdiceZEurostatuUzNezustavaPrazdna() {
        // Driv `resolveOne` znala jen `arad_view`, takze KPI z jakehokoli jineho
        // registrovaneho zdroje (Eurostat, CSU, FRED…) se vykreslilo bez hodnoty.
        when(sourceRecordsWidgetResolver.resolveAradView(any(), eq(null)))
                .thenReturn(Map.of("unit", "%", "rows", List.of(Map.of("period", "2025-12", "value", 2.4))));

        Map<String, Object> eurostat = Map.of(
                "id", "k4", "title", "HICP", "type", "eurostat_view",
                "config", Map.of("source_id", "s1", "indicator_id", "i1"));
        Map<String, Object> kpi = firstKpi(service().resolveList(List.of(eurostat), null));

        assertEquals(2.4, kpi.get("value"));
        assertEquals("%", kpi.get("unit"));
    }

    @Test
    void dlazdiceZVypoctuAZVlastnichDatSeDopocitaji() {
        when(computedViewWidgetResolver.resolve(any(), any()))
                .thenReturn(Map.of("rows", List.of(Map.of("period", "2025", "value", 42.0))));
        when(userUploadChartWidgetResolver.resolve(any()))
                .thenReturn(Map.of("rows", List.of(Map.of("period", "2025", "value", 9.0))));

        HomepageHeadlineKpiService s = service();
        Map<String, Object> comp = firstKpi(s.resolveList(
                List.of(Map.of("id", "c", "title", "V", "type", "computed_view", "config", Map.of())), null));
        Map<String, Object> upload = firstKpi(s.resolveList(
                List.of(Map.of("id", "u", "title", "D", "type", "user_upload_chart", "config", Map.of())), null));

        assertEquals(42.0, comp.get("value"));
        assertEquals(9.0, upload.get("value"));
    }

    @Test
    void aradRadaVracenaOdNejnovejsihoNevratiHodnotuZRoku1993() {
        // ARAD vraci radky od nejnovejsiho; drive se bral posledni radek, takze
        // dlazdice „2T repo sazba" ukazovala 14 % z ledna 1993 misto 3,75 %.
        when(externalCatalogChartWidgetResolver.resolve(any(), any()))
                .thenReturn(Map.of("rows", List.of(
                        Map.of("period", "20260831", "value", 3.75, "indicator_id", "SFTP01M11"),
                        Map.of("period", "20260731", "value", 3.75, "indicator_id", "SFTP01M11"),
                        Map.of("period", "20260630", "value", 4.0, "indicator_id", "SFTP01M11"),
                        Map.of("period", "19930131", "value", 14.0, "indicator_id", "SFTP01M11"))));

        Map<String, Object> kpi = firstKpi(service().resolveList(List.of(catalogKpi()), null));

        assertEquals(3.75, kpi.get("value"));
        assertEquals("20260831", kpi.get("period"));
        assertEquals(3.75, kpi.get("prev_value"));
    }

    @Test
    void predchoziHodnotaMusiPatritTezeRade() {
        // Sada 1169 mi vratila tri ruzne ukazatele za sebou — bez filtru by se
        // „predchozi hodnota" vzala z ciziho ukazatele a trend by byl nesmysl.
        when(externalCatalogChartWidgetResolver.resolve(any(), any()))
                .thenReturn(Map.of("rows", List.of(
                        Map.of("period", "20260831", "value", 3.75, "indicator_id", "SFTP01M11"),
                        Map.of("period", "20260731", "value", 3.50, "indicator_id", "SFTP01M11"),
                        Map.of("period", "20260830", "value", 99.0, "indicator_id", "SFTP03M11"))));

        Map<String, Object> kpi = firstKpi(service().resolveList(List.of(catalogKpi()), null));

        assertEquals(3.75, kpi.get("value"));
        assertEquals(3.50, kpi.get("prev_value"));
        assertEquals("up", kpi.get("trend"));
    }

    @Test
    void vzestupnePorizenaRadaFungujeDal() {
        when(externalCatalogChartWidgetResolver.resolve(any(), any()))
                .thenReturn(Map.of("rows", List.of(
                        Map.of("period", "2024", "value", 100.0),
                        Map.of("period", "2025", "value", 104.4))));

        Map<String, Object> kpi = firstKpi(service().resolveList(List.of(catalogKpi()), null));

        assertEquals(104.4, kpi.get("value"));
        assertEquals("2025", kpi.get("period"));
        assertEquals("up", kpi.get("trend"));
    }
}
