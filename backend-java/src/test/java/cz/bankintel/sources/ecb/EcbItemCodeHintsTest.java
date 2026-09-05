package cz.bankintel.sources.ecb;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EcbItemCodeHintsTest {

    @Test
    void withUnresolvedItemHint_appendsCzechHintForKnownUnresolvedIcpItem() {
        String result = EcbItemCodeHints.withUnresolvedItemHint(
                "Annual rate of change · Austria", "ICP", "Q.AT.N.OH1000.4.ANR");

        assertThat(result).isEqualTo("Annual rate of change · Austria — vlastnické bydlení (OH1000)");
    }

    @Test
    void withUnresolvedItemHint_differentOhCodesGetDifferentHints() {
        String first = EcbItemCodeHints.withUnresolvedItemHint(
                "Annual rate of change · Austria", "ICP", "Q.AT.N.OH1112.4.ANR");
        String second = EcbItemCodeHints.withUnresolvedItemHint(
                "Annual rate of change · Austria", "ICP", "Q.AT.N.OH1220.4.ANR");

        assertThat(first).isNotEqualTo(second);
        assertThat(first).contains("OH1112");
        assertThat(second).contains("OH1220");
    }

    @Test
    void withUnresolvedItemHint_leavesResolvedIcpItemsUnchanged() {
        String result = EcbItemCodeHints.withUnresolvedItemHint(
                "Annual rate of change · HICP - Overall index · PL", "ICP", "M.PL.N.000000.4.ANR");

        assertThat(result).isEqualTo("Annual rate of change · HICP - Overall index · PL");
    }

    @Test
    void withUnresolvedItemHint_leavesNonIcpFlowsUnchanged() {
        String result = EcbItemCodeHints.withUnresolvedItemHint(
                "Some BSI series name", "BSI", "M.DE.N.OH1000.4.ANR");

        assertThat(result).isEqualTo("Some BSI series name");
    }

    @Test
    void withUnresolvedItemHint_toleratesShortSeriesKeys() {
        String result = EcbItemCodeHints.withUnresolvedItemHint("Some name", "ICP", "M.AT");

        assertThat(result).isEqualTo("Some name");
    }
}
