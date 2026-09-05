package cz.bankintel.sources.ecb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.search.CatalogIndexStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Zive overeno primo v sqlite: appka ma pro naprostou vetsinu ECB2 rad uz davno lidsky nazev
 * indexovany v {@code catalog_rows_lookup} (stejna data, co pouziva klasicke hledani) - browse
 * strom ho ale nikdy nepouzival, takze leaf rady ve stromu koncily na holem SDMX klici (napr.
 * "M.PL.N.000000.4.ANR") misto lidskeho nazvu. Tenhle test pokryva opravu (join na
 * {@link CatalogIndexStore#lookupRowsBySetIds}) a navazujici opravu kbelikoveho kolapsu v
 * letter-bucket stromu (viz AUDIT_2026-09-03.md, osmnacta vlna - "P (974)" kbelik pod
 * Polsko/ICP): {@code ecb_value_descriptor}'s prvni segment je dobry, rozlisujici klic jen pro
 * cast z 28 ECB flow, overeno na realnych datech z {@code data/catalog_search_indexes/ecb2.jsonl}
 * - {@link #resolveGrouping} rozhoduje mereno na skutecnem rozlozeni bucketu, ne podle rucniho
 * seznamu flow.
 */
@ExtendWith(MockitoExtension.class)
class EcbSeriesAvailabilityServiceTest {

    @Mock
    private CatalogIndexStore catalogIndexStore;

    private EcbSeriesAvailabilityService service() {
        return new EcbSeriesAvailabilityService(new ObjectMapper(), catalogIndexStore);
    }

    // --- rowsFromSetIds / rowsToSets: human leaf names -------------------------------------

    @Test
    void rowsFromSetIds_usesEnrichedHumanNameWhenAvailable() {
        when(catalogIndexStore.lookupRowsBySetIds(eq("ecb2"), anyList(), anyInt())).thenReturn(List.of(
                Map.of(
                        "set_id", "ICP/M.PL.N.000000.4.ANR",
                        "name", "Annual rate of change · HICP - Overall index · PL",
                        "ecb_subtitle", "PL · Monthly")));

        List<Map<String, Object>> rows =
                service().rowsFromSetIds(List.of("ICP/M.PL.N.000000.4.ANR"), "PL", "ICP", "P");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("name")).isEqualTo("Annual rate of change · HICP - Overall index · PL");
        assertThat(rows.get(0).get("ecb_subtitle")).isEqualTo("PL · Monthly");
    }

    @Test
    void rowsFromSetIds_fallsBackToRawSeriesKeyWhenNoEnrichedMatch() {
        when(catalogIndexStore.lookupRowsBySetIds(eq("ecb2"), anyList(), anyInt())).thenReturn(List.of());

        List<Map<String, Object>> rows =
                service().rowsFromSetIds(List.of("ICP/M.PL.N.000000.4.ANR"), "PL", "ICP", "P");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("name")).isEqualTo("M.PL.N.000000.4.ANR");
        assertThat(rows.get(0)).doesNotContainKey("ecb_subtitle");
    }

    @Test
    void rowsToSets_propagatesEnrichedNameIntoBrowseRowTitle() {
        when(catalogIndexStore.lookupRowsBySetIds(eq("ecb2"), anyList(), anyInt())).thenReturn(List.of(
                Map.of("set_id", "ICP/M.PL.N.000000.4.ANR", "name", "Annual rate of change · HICP - Overall index · PL")));

        EcbSeriesAvailabilityService svc = service();
        List<Map<String, Object>> rows =
                svc.rowsFromSetIds(List.of("ICP/M.PL.N.000000.4.ANR"), "PL", "ICP", "P");
        List<Map<String, Object>> sets = svc.rowsToSets(rows);

        assertThat(sets).hasSize(1);
        assertThat(sets.get(0).get("ecb_browse_row_title"))
                .isEqualTo("Annual rate of change · HICP - Overall index · PL");
    }

    @Test
    void rowsFromSetIds_callsLookupExactlyOnceRegardlessOfRowCount() {
        when(catalogIndexStore.lookupRowsBySetIds(eq("ecb2"), anyList(), anyInt())).thenReturn(List.of());

        service().rowsFromSetIds(
                List.of("ICP/M.PL.N.000000.4.ANR", "ICP/M.PL.N.010000.4.ANR", "ICP/M.PL.N.010000.4.INX"),
                "PL",
                "ICP",
                "P");

        verify(catalogIndexStore, times(1)).lookupRowsBySetIds(eq("ecb2"), anyList(), anyInt());
    }

    // --- withUnresolvedItemHint: ECB "OH*" owner-occupied-housing item codes ---------------

    @Test
    void rowsFromSetIds_appendsItemCodeHintForUnresolvedOwnerOccupiedHousingItems() {
        when(catalogIndexStore.lookupRowsBySetIds(eq("ecb2"), anyList(), anyInt())).thenReturn(List.of(
                Map.of("set_id", "ICP/Q.AT.N.OH1000.4.ANR", "name", "Annual rate of change · Austria"),
                Map.of("set_id", "ICP/Q.AT.N.OH1100.4.ANR", "name", "Annual rate of change · Austria")));

        List<Map<String, Object>> rows = service().rowsFromSetIds(
                List.of("ICP/Q.AT.N.OH1000.4.ANR", "ICP/Q.AT.N.OH1100.4.ANR"), "AT", "ICP", "O");

        assertThat(rows).extracting(r -> r.get("name")).containsExactlyInAnyOrder(
                "Annual rate of change · Austria — vlastnické bydlení (OH1000)",
                "Annual rate of change · Austria — vlastnické bydlení (OH1100)");
    }

    @Test
    void rowsFromSetIds_doesNotAppendItemCodeHintOutsideIcpFlow() {
        when(catalogIndexStore.lookupRowsBySetIds(eq("ecb2"), anyList(), anyInt()))
                .thenReturn(List.of(Map.of("set_id", "BSI/M.AT.N.OH1000.4.ANR", "name", "Some BSI series")));

        List<Map<String, Object>> rows =
                service().rowsFromSetIds(List.of("BSI/M.AT.N.OH1000.4.ANR"), "AT", "BSI", "O");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("name")).isEqualTo("Some BSI series");
    }

    // --- descriptorCandidateLabel / looksLikeRawCode: real per-flow descriptor shapes ------

    @Test
    void descriptorCandidateLabel_acceptsRealIcpHicpCategory() {
        String descriptor = "HICP - Overall index · Monthly · PL · Neither seasonally nor working day "
                + "adjusted · Eurostat · Annual rate of change";

        assertThat(EcbSeriesAvailabilityService.descriptorCandidateLabel(descriptor))
                .isEqualTo("HICP - Overall index");
    }

    @Test
    void descriptorCandidateLabel_rejectsRealCbd2RawCounterpartAreaCode() {
        String descriptor = "W0 · Domestic banking groups and stand-alone banks · Euro · Annual · Austria · "
                + "Other sectors than MFIs · Not applicable";

        assertThat(EcbSeriesAvailabilityService.descriptorCandidateLabel(descriptor)).isNull();
    }

    @Test
    void descriptorCandidateLabel_rejectsRealRasDescriptorThatIsJustTheRawSeriesKey() {
        String descriptor = "M.N.AT.1C.S121.S121.FC.FI.RT1.RT.F41A.TM13.EUR.X1.N.N.ALL · Monthly · N · AT · 1C";

        assertThat(EcbSeriesAvailabilityService.descriptorCandidateLabel(descriptor)).isNull();
    }

    @Test
    void descriptorCandidateLabel_acceptsRealMirTextEvenThoughItContainsADot() {
        // "(S.122)" has a literal dot but is genuine human text, not a raw SDMX code - looksLikeRawCode
        // must key off case, not punctuation, or this legitimate MIR label would be wrongly rejected.
        String descriptor = "Deposit-taking corporations except the central bank (S.122) · Monthly · Austria";

        assertThat(EcbSeriesAvailabilityService.descriptorCandidateLabel(descriptor))
                .isEqualTo("Deposit-taking corporations except the central bank (S.122)");
    }

    @Test
    void looksLikeRawCode_trueForRealRawSectorAndAreaCodes() {
        assertThat(EcbSeriesAvailabilityService.looksLikeRawCode("S1")).isTrue();
        assertThat(EcbSeriesAvailabilityService.looksLikeRawCode("W0")).isTrue();
        assertThat(EcbSeriesAvailabilityService.looksLikeRawCode("_Z")).isTrue();
    }

    @Test
    void looksLikeRawCode_falseForHumanTextWithADot() {
        assertThat(EcbSeriesAvailabilityService.looksLikeRawCode("General government (S.13)")).isFalse();
    }

    @Test
    void looksLikeRawCode_falseForRealAllCapsCoicopCategoryLabel() {
        // Some real ICP category labels are shouted in full caps (unlike sibling "HICP - Overall
        // index") - a lowercase-only check would wrongly treat this genuine multi-word category as
        // a raw code, so the space check is what saves it.
        assertThat(EcbSeriesAvailabilityService.looksLikeRawCode("HICP - FOOD AND NON-ALCOHOLIC BEVERAGES"))
                .isFalse();
    }

    // --- slugify -----------------------------------------------------------------------------

    @Test
    void slugify_lowercasesAndHyphenatesRealIcpLabel() {
        assertThat(EcbSeriesAvailabilityService.slugify("HICP - Overall index")).isEqualTo("hicp-overall-index");
        assertThat(EcbSeriesAvailabilityService.slugify("HICP - FOOD AND NON-ALCOHOLIC BEVERAGES"))
                .isEqualTo("hicp-food-and-non-alcoholic-beverages");
    }

    @Test
    void slugify_truncatesTo64CharsWithoutTrailingHyphen() {
        String longLabel = "Loans and securities (credit), total maturity, all currencies combined";

        String slug = EcbSeriesAvailabilityService.slugify(longLabel);

        assertThat(slug).hasSizeLessThanOrEqualTo(64);
        assertThat(slug).doesNotEndWith("-");
    }

    // --- wellDiscriminated: boundary behaviour ------------------------------------------------

    @Test
    void wellDiscriminated_requiresAtLeastTwoBuckets() {
        Map<String, List<String>> singleBucket = Map.of("x", List.of("a", "b", "c"));

        assertThat(EcbSeriesAvailabilityService.wellDiscriminated(singleBucket, 3)).isFalse();
    }

    @Test
    void wellDiscriminated_acceptsExactlyAtTheNinetyPercentThreshold() {
        Map<String, List<String>> buckets = Map.of(
                "x", List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"),
                "y", List.of("10"));

        assertThat(EcbSeriesAvailabilityService.wellDiscriminated(buckets, 10)).isTrue();
    }

    @Test
    void wellDiscriminated_rejectsJustOverTheNinetyPercentThreshold() {
        Map<String, List<String>> buckets = Map.of(
                "x", List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16",
                        "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31",
                        "32", "33", "34", "35", "36", "37", "38", "39", "40", "41", "42", "43", "44", "45", "46",
                        "47", "48", "49", "50", "51", "52", "53", "54", "55", "56", "57", "58", "59", "60", "61",
                        "62", "63", "64", "65", "66", "67", "68", "69", "70", "71", "72", "73", "74", "75", "76",
                        "77", "78", "79", "80", "81", "82", "83", "84", "85", "86", "87", "88", "89", "90", "91"),
                "y", List.of("92", "93", "94", "95", "96", "97", "98", "99", "100"));

        assertThat(EcbSeriesAvailabilityService.wellDiscriminated(buckets, 100)).isFalse();
    }

    // --- resolveGrouping: the actual bucket-collapse fix, on real per-flow data ------------

    @Test
    void resolveGrouping_splitsRealPolandIcpSeriesByHicpCategoryInsteadOfCollapsingToOneCountryBucket() {
        EcbSeriesAvailabilityService svc = service();
        List<String> ids = List.of(
                "ICP/M.PL.N.000000.4.ANR",
                "ICP/M.PL.N.000000.4.INX",
                "ICP/M.PL.N.010000.4.ANR",
                "ICP/M.PL.N.010000.4.INX");
        Map<String, String> descriptors = Map.of(
                "ICP/M.PL.N.000000.4.ANR",
                        "HICP - Overall index · Monthly · PL · Neither seasonally nor working day adjusted · "
                                + "Eurostat · Annual rate of change",
                "ICP/M.PL.N.000000.4.INX",
                        "HICP - Overall index · Monthly · PL · Neither seasonally nor working day adjusted · "
                                + "Eurostat · Index",
                "ICP/M.PL.N.010000.4.ANR",
                        "HICP - FOOD AND NON-ALCOHOLIC BEVERAGES · Monthly · PL · Neither seasonally nor working "
                                + "day adjusted · Eurostat · Annual rate of change",
                "ICP/M.PL.N.010000.4.INX",
                        "HICP - FOOD AND NON-ALCOHOLIC BEVERAGES · Monthly · PL · Neither seasonally nor working "
                                + "day adjusted · Eurostat · Index");

        EcbSeriesAvailabilityService.Grouping grouping = svc.resolveGrouping(ids, "PL", descriptors);

        // Not the old single "P" bucket holding every Poland/ICP series regardless of category.
        assertThat(grouping.buckets()).containsOnlyKeys("hicp-overall-index", "hicp-food-and-non-alcoholic-beverages");
        assertThat(grouping.buckets().get("hicp-overall-index"))
                .containsExactlyInAnyOrder("ICP/M.PL.N.000000.4.ANR", "ICP/M.PL.N.000000.4.INX");
        assertThat(grouping.buckets().get("hicp-food-and-non-alcoholic-beverages"))
                .containsExactlyInAnyOrder("ICP/M.PL.N.010000.4.ANR", "ICP/M.PL.N.010000.4.INX");
        assertThat(grouping.labels())
                .containsEntry("hicp-overall-index", "HICP - Overall index")
                .containsEntry("hicp-food-and-non-alcoholic-beverages", "HICP - FOOD AND NON-ALCOHOLIC BEVERAGES");
    }

    @Test
    void resolveGrouping_splitsRealAustriaBsiSeriesByReportingSectorEvenThoughOneBucketDominates() {
        EcbSeriesAvailabilityService svc = service();
        List<String> ids = List.of(
                "BSI/M.AT.N.A.A20.G.1.U6.2240.EUR.E",
                "BSI/M.AT.N.A.A20.G.4.U2.2240.EUR.E",
                "BSI/M.AT.N.R.LRAE.X.1.A1.3000.Z01.E",
                "BSI/M.AT.N.R.LRAU.X.1.A1.3000.Z01.E");
        Map<String, String> descriptors = Map.of(
                "BSI/M.AT.N.A.A20.G.1.U6.2240.EUR.E",
                        "MFIs excluding ESCB · Monthly · Austria · Neither seasonally nor working day adjusted · "
                                + "Loans · Over 1 and up to 2 years · Outstanding amounts at the end of the period "
                                + "(stocks) · U6 · Non-Financial corporations (S.11) · Euro",
                "BSI/M.AT.N.A.A20.G.4.U2.2240.EUR.E",
                        "MFIs excluding ESCB · Monthly · Austria · Neither seasonally nor working day adjusted · "
                                + "Loans · Over 1 and up to 2 years · Financial transactions (flows) · U2 · "
                                + "Non-Financial corporations (S.11) · Euro",
                "BSI/M.AT.N.R.LRAE.X.1.A1.3000.Z01.E",
                        "Credit institutions · Monthly · Austria · Neither seasonally nor working day adjusted · "
                                + "Reserve maintenance - Exemption allowance · Not applicable",
                "BSI/M.AT.N.R.LRAU.X.1.A1.3000.Z01.E",
                        "Credit institutions · Monthly · Austria · Neither seasonally nor working day adjusted · "
                                + "Reserve maintenance - Unused allowance · Not applicable");

        EcbSeriesAvailabilityService.Grouping grouping = svc.resolveGrouping(ids, "AT", descriptors);

        assertThat(grouping.buckets()).containsOnlyKeys("mfis-excluding-escb", "credit-institutions");
        assertThat(grouping.buckets().get("mfis-excluding-escb")).hasSize(2);
        assertThat(grouping.buckets().get("credit-institutions")).hasSize(2);
    }

    @Test
    void resolveGrouping_fallsBackToLegacyLetterBucketForRealCbd2RawCounterpartAreaCodes() {
        EcbSeriesAvailabilityService svc = service();
        List<String> ids = List.of(
                "CBD2/A.AT.W0.11.S1Q._Z.A.F.L1110._X.ALL.CA._Z.LE._T.EUR",
                "CBD2/A.AT.W0.11.S1Q._Z.A.F.L1120._X.ALL.CA._Z.LE._T.EUR",
                "CBD2/A.AT.W0.11.S1Q._Z.A.F.L1130._X.ALL.CA._Z.LE._T.EUR",
                "CBD2/A.AT.W0.11.S1Q._Z.A.F.L1140._X.ALL.CA._Z.LE._T.EUR");
        String rawCodeDescriptor = "W0 · Domestic banking groups and stand-alone banks · Euro · Annual · Austria · "
                + "Other sectors than MFIs · Not applicable · All institutions · FINREP (IFRS and GAAP)";
        Map<String, String> descriptors = Map.of(
                ids.get(0), rawCodeDescriptor,
                ids.get(1), rawCodeDescriptor,
                ids.get(2), rawCodeDescriptor,
                ids.get(3), rawCodeDescriptor);

        EcbSeriesAvailabilityService.Grouping grouping = svc.resolveGrouping(ids, "AT", descriptors);
        EcbSeriesAvailabilityService.Grouping legacyOnly = svc.resolveGrouping(ids, "AT", Map.of());

        assertThat(grouping.buckets()).isEqualTo(legacyOnly.buckets());
        assertThat(grouping.buckets()).containsOnlyKeys("A");
        assertThat(grouping.buckets().get("A")).containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void resolveGrouping_fallsBackToLegacyLetterBucketWhenRealMirDescriptorIsConstantAcrossTheGroup() {
        EcbSeriesAvailabilityService svc = service();
        List<String> ids = List.of(
                "MIR/M.AT.B.A20.A.R.A.2240.EUR.O",
                "MIR/M.AT.B.A20.A.R.A.2250.EUR.O",
                "MIR/M.AT.B.A20.F.R.A.2240.EUR.O",
                "MIR/M.AT.B.A20.H.R.A.2240.EUR.O");
        // Real MIR data: this exact text is the descriptor's first segment for every single AT/MIR
        // row (distinct=1 measured across 1,505 real rows) - human, passes looksLikeRawCode, but
        // gives zero discrimination, so this must be caught by resolveGrouping's distribution check,
        // not the per-segment one.
        String constantDescriptor = "Deposit-taking corporations except the central bank (S.122) · Monthly · Austria";
        Map<String, String> descriptors = Map.of(
                ids.get(0), constantDescriptor,
                ids.get(1), constantDescriptor,
                ids.get(2), constantDescriptor,
                ids.get(3), constantDescriptor);

        EcbSeriesAvailabilityService.Grouping grouping = svc.resolveGrouping(ids, "AT", descriptors);

        assertThat(grouping.buckets()).containsOnlyKeys("A");
        assertThat(grouping.buckets().get("A")).containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void resolveGrouping_matchesLegacyExactlyWhenNoEnrichedDescriptorsAreAvailable() {
        EcbSeriesAvailabilityService svc = service();
        List<String> ids = List.of("ICP/M.PL.N.000000.4.ANR", "ICP/M.PL.N.010000.4.ANR");

        EcbSeriesAvailabilityService.Grouping grouping = svc.resolveGrouping(ids, "PL", Map.of());

        assertThat(grouping.buckets()).containsOnlyKeys("P");
        assertThat(grouping.buckets().get("P")).containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void resolveGrouping_fallsBackWhenSlugifiedCandidateWouldCollapseToASingleCharacter() {
        EcbSeriesAvailabilityService svc = service();
        List<String> ids = List.of("ICP/M.AT.N.111.4.X", "ICP/M.AT.N.222.4.Y");
        // Both slugify down to one character ("a"/"b") - normalizeLetterBucket would then treat
        // them like the legacy single-uppercase-letter scheme (uppercasing on lookup), which would
        // desync from how they're actually stored here, so browseGroupForSeries must reject them
        // up front rather than mint a bucket that 404s on click-through.
        Map<String, String> descriptors = Map.of(
                "ICP/M.AT.N.111.4.X", "(a)",
                "ICP/M.AT.N.222.4.Y", "(b)");

        EcbSeriesAvailabilityService.Grouping grouping = svc.resolveGrouping(ids, "AT", descriptors);

        assertThat(grouping.buckets()).containsOnlyKeys("A");
        assertThat(grouping.buckets().get("A")).containsExactlyInAnyOrderElementsOf(ids);
    }
}
