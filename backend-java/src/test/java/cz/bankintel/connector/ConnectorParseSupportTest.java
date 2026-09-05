package cz.bankintel.connector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link ConnectorParseSupport} turns upstream SDMX/CSV payloads into the rows that end up on a
 * chart, and is shared by the BIS, IMF, OECD and Data360 connectors — a bug here silently produces
 * a wrong number rather than an error, which is the worst failure mode for a data platform.
 *
 * <p>The class had no test coverage at all; these fixtures pin the conversions that matter
 * (numbers, dates, country codes) and the malformed-input paths that must degrade to an empty
 * result instead of throwing.
 */
class ConnectorParseSupportTest {

    @Nested
    class BisGenericSdmxXml {

        private static final String SERIES_XML =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <GenericData xmlns="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/message"
                             xmlns:g="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/data/generic">
                  <g:DataSet>
                    <g:Series>
                      <g:SeriesKey>
                        <g:Value id="FREQ" value="A"/>
                        <g:Value id="REF_AREA" value="CZ"/>
                      </g:SeriesKey>
                      <g:Obs>
                        <g:ObsDimension id="TIME_PERIOD" value="2020"/>
                        <g:ObsValue value="3.2"/>
                      </g:Obs>
                      <g:Obs>
                        <g:ObsDimension id="TIME_PERIOD" value="2021"/>
                        <g:ObsValue value="4.5"/>
                      </g:Obs>
                    </g:Series>
                  </g:DataSet>
                </GenericData>
                """;

        @Test
        void carriesSeriesDimensionsOntoEveryObservation() {
            List<Map<String, Object>> rows = ConnectorParseSupport.parseBisGenericDataXml(SERIES_XML);

            assertThat(rows).hasSize(2);
            assertThat(rows).allSatisfy(row -> {
                assertThat(row).containsEntry("FREQ", "A");
                assertThat(row).containsEntry("REF_AREA", "CZ");
            });
        }

        @Test
        void parsesObservationValuesAsNumbersUnderEveryAlias() {
            List<Map<String, Object>> rows = ConnectorParseSupport.parseBisGenericDataXml(SERIES_XML);

            Map<String, Object> first = rows.get(0);
            assertThat(first).containsEntry("TIME_PERIOD", "2020");
            assertThat(first).containsEntry("date", "2020");
            // The chart layer reads whichever of these aliases it was written against.
            assertThat(first).containsEntry("OBS_VALUE", 3.2);
            assertThat(first).containsEntry("value", 3.2);
            assertThat(first).containsEntry("amount", 3.2);
            assertThat(rows.get(1)).containsEntry("value", 4.5);
        }

        @Test
        void treatsTheSdmxMissingValueMarkerAsNoNumber() {
            String xml = SERIES_XML.replace("value=\"3.2\"", "value=\".\"");

            List<Map<String, Object>> rows = ConnectorParseSupport.parseBisGenericDataXml(xml);

            // "." is SDMX for "not available" — it must not become 0 or a parse failure.
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).containsEntry("TIME_PERIOD", "2020");
            assertThat(rows.get(0)).doesNotContainKey("value");
            assertThat(rows.get(1)).containsEntry("value", 4.5);
        }

        @Test
        void keepsANonNumericObservationAsRawTextRatherThanDroppingIt() {
            String xml = SERIES_XML.replace("value=\"3.2\"", "value=\"n/a\"");

            List<Map<String, Object>> rows = ConnectorParseSupport.parseBisGenericDataXml(xml);

            assertThat(rows.get(0)).containsEntry("OBS_VALUE", "n/a");
            assertThat(rows.get(0)).doesNotContainKey("value");
        }

        @Test
        void degradesToAnEmptyResultInsteadOfThrowing() {
            assertThat(ConnectorParseSupport.parseBisGenericDataXml(null)).isEmpty();
            assertThat(ConnectorParseSupport.parseBisGenericDataXml("")).isEmpty();
            assertThat(ConnectorParseSupport.parseBisGenericDataXml("   ")).isEmpty();
            assertThat(ConnectorParseSupport.parseBisGenericDataXml("<GenericData><unclosed>")).isEmpty();
            assertThat(ConnectorParseSupport.parseBisGenericDataXml("{\"not\":\"xml\"}")).isEmpty();
        }
    }

    @Nested
    class ImfSdmxJson {

        /** Minimal SDMX-JSON: two periods, two countries, observations addressed by index. */
        private static Map<String, Object> imfBody() {
            Map<String, Object> timeDim = Map.of(
                    "id", "TIME_PERIOD",
                    "values", List.of(Map.of("value", "2020"), Map.of("value", "2021")));
            Map<String, Object> freqDim = Map.of("id", "FREQ", "values", List.of(Map.of("id", "A")));
            Map<String, Object> areaDim = Map.of(
                    "id", "REF_AREA",
                    "values", List.of(
                            Map.of("id", "SK", "name", "Slovensko"),
                            Map.of("id", "CZ", "name", "Česko")));

            Map<String, Object> structure = Map.of(
                    "dimensions",
                    Map.of("observation", List.of(timeDim), "series", List.of(freqDim, areaDim)));

            // Series key "0:1" = FREQ index 0, REF_AREA index 1 → CZ.
            Map<String, Object> series = Map.of(
                    "0:1", Map.of("observations", Map.of("0", List.of(3.2), "1", List.of(4.5))));

            return Map.of(
                    "data",
                    Map.of("structures", List.of(structure), "dataSets", List.of(Map.of("series", series))));
        }

        @Test
        void resolvesObservationIndexesToRealTimePeriods() {
            List<Map<String, Object>> rows = ConnectorParseSupport.parseImfSdmxDataJson(imfBody());

            assertThat(rows).hasSize(2);
            // Index 0/1 are meaningless without the structures block; mapping them to the actual
            // periods is the whole job of this parser.
            assertThat(rows.get(0)).containsEntry("TIME_PERIOD", "2020");
            assertThat(rows.get(0)).containsEntry("value", 3.2);
            assertThat(rows.get(1)).containsEntry("TIME_PERIOD", "2021");
            assertThat(rows.get(1)).containsEntry("value", 4.5);
        }

        @Test
        void enrichesRowsWithTheCountryFromTheSeriesKey() {
            List<Map<String, Object>> rows = ConnectorParseSupport.parseImfSdmxDataJson(imfBody());

            assertThat(rows).allSatisfy(row -> {
                assertThat(row).containsEntry("COUNTRY", "CZ");
                assertThat(row).containsEntry("COUNTRY_label", "Česko");
            });
        }

        @Test
        void keepsEveryDimensionFromTheSeriesKeyNotJustCountry() {
            // Zivy nalez: driv se ze "seriesDims" cetla jen dimenze zeme, zbytek (tady FREQ) se
            // zahodil jeste pred vznikem radku - ruzne serie se stejnou zemi se tak tise slily
            // do jedne, i kdyz predstavovaly jinou realnou radu (napr. jinou frekvenci, protistranu
            // nebo sektor).
            List<Map<String, Object>> rows = ConnectorParseSupport.parseImfSdmxDataJson(imfBody());

            assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("FREQ", "A"));
        }

        @Test
        void distinguishesTwoSeriesThatShareACountryButDifferInAnotherDimension() {
            // "0:1" a "1:1" sdilej REF_AREA index 1 (CZ), ale maji jiny FREQ index (0=A, 1=Q) -
            // driv by oba proudy pozorovani skoncily ve "stejnem" (jen podle zeme rozlisenem)
            // radku a tise se smichaly; ted musi zustat rozlisitelne podle FREQ.
            Map<String, Object> body = new LinkedHashMap<>(imfBody());
            Map<String, Object> data = new LinkedHashMap<>(castMap(body.get("data")));
            Map<String, Object> structure = castMap(((List<?>) data.get("structures")).get(0));
            Map<String, Object> dims = castMap(structure.get("dimensions"));
            Map<String, Object> freqDim = new LinkedHashMap<>(castMap(((List<?>) dims.get("series")).get(0)));
            freqDim.put("values", List.of(Map.of("id", "A"), Map.of("id", "Q")));
            List<Object> newSeriesDims = List.of(freqDim, ((List<?>) dims.get("series")).get(1));
            Map<String, Object> newDims = new LinkedHashMap<>(dims);
            newDims.put("series", newSeriesDims);
            Map<String, Object> newStructure = new LinkedHashMap<>(structure);
            newStructure.put("dimensions", newDims);
            data.put("structures", List.of(newStructure));
            data.put(
                    "dataSets",
                    List.of(Map.of(
                            "series",
                            Map.of(
                                    "0:1", Map.of("observations", Map.of("0", List.of(3.2))),
                                    "1:1", Map.of("observations", Map.of("0", List.of(9.9)))))));
            body.put("data", data);

            List<Map<String, Object>> rows = ConnectorParseSupport.parseImfSdmxDataJson(body);

            assertThat(rows).hasSize(2);
            assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("COUNTRY", "CZ"));
            assertThat(rows).extracting(row -> row.get("FREQ")).containsExactlyInAnyOrder("A", "Q");
            assertThat(rows).extracting(row -> row.get("value")).containsExactlyInAnyOrder(3.2, 9.9);
        }

        @Test
        void returnsRowsSortedByPeriod() {
            List<Map<String, Object>> rows = ConnectorParseSupport.parseImfSdmxDataJson(imfBody());

            assertThat(rows).extracting(row -> row.get("date")).containsExactly("2020", "2021");
        }

        @Test
        void skipsObservationsThatAreNotNumbers() {
            Map<String, Object> body = new LinkedHashMap<>(imfBody());
            Map<String, Object> data = new LinkedHashMap<>(castMap(body.get("data")));
            data.put(
                    "dataSets",
                    List.of(Map.of(
                            "series",
                            Map.of("0:1", Map.of("observations", Map.of("0", List.of("n/a"), "1", List.of(4.5)))))));
            body.put("data", data);

            List<Map<String, Object>> rows = ConnectorParseSupport.parseImfSdmxDataJson(body);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).containsEntry("value", 4.5);
        }

        @Test
        void degradesToAnEmptyResultOnMissingOrForeignPayloads() {
            assertThat(ConnectorParseSupport.parseImfSdmxDataJson(null)).isEmpty();
            assertThat(ConnectorParseSupport.parseImfSdmxDataJson(Map.of())).isEmpty();
            assertThat(ConnectorParseSupport.parseImfSdmxDataJson(Map.of("data", "not-a-map"))).isEmpty();
            assertThat(ConnectorParseSupport.parseImfSdmxDataJson(Map.of("data", Map.of()))).isEmpty();
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> castMap(Object value) {
            return (Map<String, Object>) value;
        }
    }

    @Nested
    class CsvPreviewRows {

        @Test
        void detectsTimeAndValueColumnsRegardlessOfCase() {
            List<Map<String, Object>> rows = ConnectorParseSupport.parseCsvPreviewRows(
                    List.of(row("TIME_PERIOD", "2020-01", "OBS_VALUE", "1.5")));

            assertThat(rows.get(0)).containsEntry("date", "2020-01");
            assertThat(rows.get(0)).containsEntry("value", 1.5);
            assertThat(rows.get(0)).containsEntry("amount", 1.5);
        }

        @Test
        void readsCzechDecimalCommas() {
            List<Map<String, Object>> rows =
                    ConnectorParseSupport.parseCsvPreviewRows(List.of(row("Period", "2020", "Value", "3,14")));

            // Czech sources export "3,14"; parsing it as 3 (or failing) would corrupt the series.
            assertThat(rows.get(0)).containsEntry("value", 3.14);
        }

        @Test
        void trimsWhitespaceFromHeaderNames() {
            List<Map<String, Object>> rows = ConnectorParseSupport.parseCsvPreviewRows(
                    List.of(row("  TIME  ", "2020", "  Value  ", "7")));

            assertThat(rows.get(0)).containsKey("TIME");
            assertThat(rows.get(0)).containsEntry("value", 7.0);
        }

        @Test
        void keepsTheRawRowWhenTheValueIsNotNumeric() {
            List<Map<String, Object>> rows =
                    ConnectorParseSupport.parseCsvPreviewRows(List.of(row("TIME", "2020", "Value", ":")));

            // Eurostat uses ":" for missing data — the row survives, just without a number.
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).containsEntry("Value", ":");
            assertThat(rows.get(0)).doesNotContainKey("value");
        }

        @Test
        void leavesRowsAloneWhenNoTimeOrValueColumnIsPresent() {
            List<Map<String, Object>> rows =
                    ConnectorParseSupport.parseCsvPreviewRows(List.of(row("label", "HDP", "note", "x")));

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).doesNotContainKey("date");
            assertThat(rows.get(0)).doesNotContainKey("value");
        }

        private static Map<String, Object> row(String k1, Object v1, String k2, Object v2) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(k1, v1);
            map.put(k2, v2);
            return map;
        }
    }

    @Nested
    class Data360Rows {

        @Test
        void convertsObservationValuesAndNormalisesTheCountryCode() {
            Map<String, Object> raw = Map.of(
                    "value",
                    List.of(Map.of(
                            "OBS_VALUE", "2.75",
                            "REF_AREA", " cz ",
                            "TIME_PERIOD", "2021",
                            "INDICATOR", "GDP",
                            "FREQ", "A")));

            List<Map<String, Object>> rows = ConnectorParseSupport.parseData360Rows(raw);

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).containsEntry("value_num", 2.75);
            assertThat(rows.get(0)).containsEntry("amount", 2.75);
            assertThat(rows.get(0)).containsEntry("observation_value_raw", "2.75");
            // Geo codes arrive inconsistently cased/padded and are matched downstream.
            assertThat(rows.get(0)).containsEntry("geo", "CZ");
            assertThat(rows.get(0)).containsEntry("TIME_PERIOD", "2021");
        }

        @Test
        void keepsANonNumericObservationWithoutInventingANumber() {
            Map<String, Object> raw =
                    Map.of("value", List.of(Map.of("OBS_VALUE", "n/a", "REF_AREA", "CZ", "TIME_PERIOD", "2021")));

            List<Map<String, Object>> rows = ConnectorParseSupport.parseData360Rows(raw);

            assertThat(rows.get(0)).containsEntry("value_num", null);
            assertThat(rows.get(0)).containsEntry("amount", "n/a");
        }

        @Test
        void acceptsBothTheLowercaseAndCapitalisedValueEnvelope() {
            Map<String, Object> lower = Map.of("value", List.of(Map.of("OBS_VALUE", "1", "TIME_PERIOD", "2020")));
            Map<String, Object> upper = Map.of("Value", List.of(Map.of("OBS_VALUE", "1", "TIME_PERIOD", "2020")));

            assertThat(ConnectorParseSupport.parseData360Rows(lower)).hasSize(1);
            assertThat(ConnectorParseSupport.parseData360Rows(upper)).hasSize(1);
        }

        @Test
        void degradesToAnEmptyResultWhenTheEnvelopeIsMissing() {
            assertThat(ConnectorParseSupport.parseData360Rows(Map.of())).isEmpty();
            assertThat(ConnectorParseSupport.parseData360Rows(Map.of("value", "not-a-list"))).isEmpty();
        }
    }
}
