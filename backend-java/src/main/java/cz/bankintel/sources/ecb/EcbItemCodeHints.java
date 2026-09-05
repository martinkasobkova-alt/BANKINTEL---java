package cz.bankintel.sources.ecb;

import java.util.Set;

/**
 * Sdilena logika pro {@link EcbSeriesAvailabilityService} (browse strom) i
 * {@link cz.bankintel.search.CatalogIndexStore} (katalogove hledani) - oboji cte tytez
 * radky z {@code ecb2.jsonl}, oboji potrebuje stejny dodatek pro 11 ICP polozkovych kodu
 * (vlastnicke bydleni), ktere v obohacenem indexu nemaji specificky lidsky nazev.
 *
 * <p>Zive zjisteno (sqlite): techto 11 ICP "item" kodu (1987 rad napric vsemi zememi) nema v
 * obohacenem indexu zadny specificky lidsky nazev - vzdy spadnou na obecny fallback podle typu
 * miry ("Annual rate of change · Rakousko" apod.), takze 10 ruzne ruznych polozek pod jednou
 * zemi vypada jako 10 duplicit. Predpona "OH" odpovida ECB experimentalnim radam k vlastnickemu
 * bydleni (owner-occupied housing) v ramci HICP - overeno webovym hledanim (ECB Economic
 * Bulletin, "Owner-occupied housing and inflation measurement"), presny cesky nazev KAZDEHO
 * z 11 kodu se ale nepodarilo dohledat (ECB SDMX API i Data Portal odmitly request s HTTP 503,
 * opakovane, na vice cestach). Dokud se nenajde spolehlivy zdroj presnych nazvu, alespon se
 * rady odlisi kodem - to je porad lepsi nez 10 vizualne nerozlisitelnych karet.
 */
public final class EcbItemCodeHints {

    private static final Set<String> ICP_ITEMS_WITHOUT_SPECIFIC_LABEL = Set.of(
            "OH1000", "OH1100", "OH1110", "OH1111", "OH1112", "OH1120", "OH1130",
            "OH1200", "OH1210", "OH1220", "OH1230");

    private EcbItemCodeHints() {
    }

    /**
     * ICP "item" segment je ctvrty teckovany segment serie (FREQ.REF_AREA.ADJUSTMENT.ITEM....) -
     * overeno na realnych datech ({@code ecb_series_explanation} obohaceneho radku pro "OH1000"
     * apod.). Plati jen pro flow ICP - u jinych flow muze mit klic jinou strukturu, proto se mimo
     * ICP vubec nezkousi.
     */
    public static String withUnresolvedItemHint(String name, String flow, String seriesKey) {
        if (name == null || seriesKey == null || !"ICP".equals(flow)) {
            return name;
        }
        String[] parts = seriesKey.split("\\.");
        if (parts.length < 4) {
            return name;
        }
        String item = parts[3];
        if (!ICP_ITEMS_WITHOUT_SPECIFIC_LABEL.contains(item)) {
            return name;
        }
        return name + " — vlastnické bydlení (" + item + ")";
    }
}
