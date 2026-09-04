package cz.bankintel.sources.bis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Živě zjištěno: BIS strom ukazoval každou složku/řadu pod jménem holého SDMX kódu ("DE" místo
 * "Německo") - appka přitom měla funkční dekodér (`getRefAreas`, BIS vlastní ceník
 * CL_BIS_IF_REF_AREA) hotový, jen se nikdy nezavolal. Horší: u hustých dataflow (12dimenzní
 * klíč u WS_LBS_D_PUB) se tisíce různých řad ve stejné zemi jmenovaly úplně stejně (jen kódem
 * té země), protože se jméno řady vůbec nestavělo ze zbytku klíče.
 *
 * <p>{@code BisCatalogService} volá živé BIS API přímo v konstruktoru vytvořeným
 * {@code HttpClient}em (žádný injektovatelný seam pro mock) - tenhle test proto cílí jen na
 * čisté, bezstavové pomocné metody (uvolněné na package-private přesně kvůli tomu); end-to-end
 * zapojení (skutečné stažení + XML parsování) je ověřené živě v prohlížeči, ne tady.
 */
class BisCatalogServiceTest {

    @Test
    void areaDisplayName_usesHumanLabelWhenKnown() {
        assertThat(BisCatalogService.areaDisplayName("DE", Map.of("DE", "Německo"))).isEqualTo("Německo (DE)");
    }

    @Test
    void areaDisplayName_fallsBackToRawCodeWhenUnresolved() {
        assertThat(BisCatalogService.areaDisplayName("5J", Map.of("DE", "Německo"))).isEqualTo("5J");
    }

    @Test
    void areaDisplayName_othersBucketGetsCzechLabel() {
        assertThat(BisCatalogService.areaDisplayName("_OTHER_", Map.of())).isEqualTo("Ostatní");
    }

    @Test
    void decodeSeriesKeyToName_decodesEveryDimensionExceptRefAreaAndSkipsBlanks() {
        String[] header = {"FREQ", "L_MEASURE", "L_REP_CTY", "L_CP_COUNTRY"};
        String[] row = {"Q", "TO1", "DE", ""};
        Map<String, Map<String, String>> labels = Map.of(
                "FREQ", Map.of("Q", "Quarterly"),
                "L_MEASURE", Map.of("TO1", "Total claims"));
        // L_REP_CTY je na indexu 2 - to je "refAreaIdx", má se v názvu řady vynechat (zemi už
        // uživatel vidí jako složku ve stromu). L_CP_COUNTRY je prázdné, taky se přeskočí.

        String out = BisCatalogService.decodeSeriesKeyToName(header, row, 2, labels);

        assertThat(out).isEqualTo("Quarterly · Total claims");
    }

    @Test
    void decodeSeriesKeyToName_fallsBackToRawCodeForUnresolvedDimensions() {
        String[] header = {"FREQ", "L_MEASURE"};
        String[] row = {"Q", "TO1"};

        String out = BisCatalogService.decodeSeriesKeyToName(header, row, -1, Map.of());

        assertThat(out).isEqualTo("Q · TO1");
    }

    @Test
    void decodeSeriesKeyToName_twoDifferentSeriesInSameCountryNoLongerShareOneName() {
        // Přesně tenhle případ appka dřív dostala špatně: dvě GENUINELY různé řady (jiné
        // L_MEASURE) ve stejné zemi (Německo, přeskočené z názvu) se dřív obě jmenovaly "DE".
        String[] header = {"L_MEASURE", "L_REP_CTY"};
        Map<String, Map<String, String>> labels =
                Map.of("L_MEASURE", Map.of("TO1", "Total claims", "TO2", "Total liabilities"));

        String nameA = BisCatalogService.decodeSeriesKeyToName(header, new String[] {"TO1", "DE"}, 1, labels);
        String nameB = BisCatalogService.decodeSeriesKeyToName(header, new String[] {"TO2", "DE"}, 1, labels);

        assertThat(nameA).isNotEqualTo(nameB);
        assertThat(nameA).isEqualTo("Total claims");
        assertThat(nameB).isEqualTo("Total liabilities");
    }
}
