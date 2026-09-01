package cz.bankintel.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Členové dimenzí (CZ+EN) pro řádek, jehož TITUL dimenzi jmenuje.
 *
 * <p>Port {@code services/classic_catalog_relevance_scoring.py:dimension_member_terms_for_title}
 * z referenčního Python repa. Zapéká se do {@code search_blob} při buildu classic FTS indexu
 * ({@link ClassicCatalogFtsIndexBuilder}), takže členové jsou pak i HLEDATELNÍ, ne jen skórovaní
 * za běhu: dotaz „nezaměstnanost žen" retrievne dataset „…by sex…".
 *
 * <p>Cílené: vrací neprázdný seznam jen když titul obsahuje název dimenze, takže obecné řady
 * žádné členy nedostanou a index se nezašumí.
 *
 * <p><b>Pozor na foldování.</b> Schválně se tu nepoužívá {@link CatalogTextUtils#foldAscii},
 * protože ta normalizuje přes <b>NFKD</b>, kdežto Python {@code fold_ascii} přes <b>NFD</b>.
 * Rozdíl se projeví u kompatibilitních znaků (ligatury, zlomky, plná šířka) a znamenal by, že
 * Javou postavený index má jiný {@code search_blob} než ten Pythonem — tedy jiné výsledky
 * hledání. Parita s referenčním buildem je tu důležitější než sdílení jedné utility.
 */
public final class CatalogDimensionMemberTerms {

    private CatalogDimensionMemberTerms() {
    }

    private record DimensionRule(List<String> titleTriggers, List<String> memberTerms) {
    }

    private static final List<DimensionRule> DIMENSION_MAP = List.of(
            new DimensionRule(
                    List.of("by sex", "podle pohlavi", "by gender"),
                    List.of("zeny", "muzi", "pohlavi", "female", "male", "women", "men", "females",
                            "males", "gender")),
            new DimensionRule(
                    List.of("by age", "age group", "age class", "podle veku", "by age class"),
                    List.of("vek", "vekove skupiny", "mladi", "mladez", "seniori", "youth", "young",
                            "elderly", "age group")),
            new DimensionRule(
                    List.of("nace", "economic activity", "by activity", "economic sector", "branch"),
                    List.of("odvetvi", "ekonomicka cinnost", "sektor", "prumysl", "sluzby",
                            "zemedelstvi", "stavebnictvi", "nace", "industry", "services",
                            "agriculture", "manufacturing", "economic activity", "sector")),
            new DimensionRule(
                    List.of("educational attainment", "education level", "by education", "isced",
                            "level of education"),
                    List.of("vzdelani", "vzdelanostni uroven", "zakladni", "stredni",
                            "vysokoskolske", "terciarni", "primary", "secondary", "tertiary",
                            "isced", "educational attainment", "education level")),
            new DimensionRule(
                    List.of("nuts 2 region", "nuts 3 region", "by region", "metropolitan region",
                            "by nuts", "regional"),
                    List.of("region", "kraj", "kraje", "regiony", "nuts", "regional", "by region")),
            new DimensionRule(
                    List.of("citizenship", "by citizenship", "group of citizenship"),
                    List.of("obcanstvi", "statni prislusnost", "cizinci", "citizenship", "nationals",
                            "foreigners", "nationality")),
            new DimensionRule(
                    List.of("degree of urbanisation", "by urbanisation", "urbanization"),
                    List.of("urbanizace", "mesta", "obce", "venkov", "cities", "towns", "rural",
                            "urban", "degree of urbanisation")),
            new DimensionRule(
                    List.of("disability", "level of disability", "type of disability"),
                    List.of("postizeni", "zdravotni postizeni", "invalidita", "disability",
                            "disabled")),
            new DimensionRule(
                    List.of("country of birth", "group of country of birth", "by birth"),
                    List.of("zeme narozeni", "rodaci", "native", "foreign born",
                            "country of birth")),
            new DimensionRule(
                    List.of("professional status", "labour status", "by status",
                            "employment status"),
                    List.of("postaveni v zamestnani", "pracovni status", "zamestnanci", "osvc",
                            "sebezamestnani", "employees", "self employed", "professional status",
                            "labour status")),
            new DimensionRule(
                    List.of("occupation", "by occupation", "isco"),
                    List.of("povolani", "zamestnani", "occupation", "isco")),
            new DimensionRule(
                    List.of("income quintile", "income quantile", "income decile", "by income"),
                    List.of("prijmove kvintily", "prijmove decily", "prijem", "income quintile",
                            "income decile", "by income")),
            new DimensionRule(
                    List.of("size class", "size of enterprise", "enterprise size",
                            "employment size class"),
                    List.of("velikostni trida", "velikost podniku", "male podniky", "sme",
                            "enterprise size", "size class")),
            new DimensionRule(
                    List.of("size of farm", "farm size", "utilised agricultural area"),
                    List.of("velikost farmy", "velikost hospodarstvi", "farm size")),
            new DimensionRule(
                    List.of("migration", "migratory status", "migration status"),
                    List.of("migrace", "migracni status", "migranti", "migration", "migrant")),
            new DimensionRule(
                    List.of("household", "households", "by household", "type of household"),
                    List.of("domacnosti", "domacnost", "typ domacnosti", "slozeni domacnosti",
                            "sektor domacnosti", "households", "household composition",
                            "household type", "household sector")),
            new DimensionRule(
                    List.of("tenure status", "by tenure"),
                    List.of("pravni duvod uzivani bytu", "vlastnicke bydleni", "najem", "vlastnik",
                            "najemnik", "tenure status", "owner occupied", "tenant")),
            new DimensionRule(
                    List.of("coicop", "consumption purpose", "purpose"),
                    List.of("ucel spotreby", "coicop", "consumption purpose")),
            new DimensionRule(
                    List.of("motor energy", "type of fuel", "by fuel", "type of motor energy"),
                    List.of("typ paliva", "palivo", "benzin", "nafta", "elektromobil", "petrol",
                            "diesel", "electric", "motor energy", "fuel")),
            new DimensionRule(
                    List.of("type of transport", "by transport", "transport mode",
                            "mode of transport"),
                    List.of("druh dopravy", "doprava", "silnicni", "zeleznicni", "letecka", "road",
                            "rail", "air", "transport mode")),
            new DimensionRule(
                    List.of("working time", "full-time", "part-time", "full time", "part time"),
                    List.of("pracovni doba", "plny uvazek", "castecny uvazek", "full time",
                            "part time", "working time")),
            new DimensionRule(
                    List.of("sector of performance", "by sector", "institutional sector"),
                    List.of("sektor", "podnikatelsky sektor", "vladni sektor", "business enterprise",
                            "government sector", "sector of performance")),
            new DimensionRule(
                    List.of("type of goods", "by product", "product group", "by commodity"),
                    List.of("druh zbozi", "produkt", "vyrobek", "komodita", "goods", "product",
                            "commodity")),
            new DimensionRule(
                    List.of("diagnosis", "cause of", "by disease"),
                    List.of("diagnoza", "pricina", "nemoc", "diagnosis", "cause", "disease")),
            new DimensionRule(
                    List.of("by country", "by partner", "partner country", "reporting country"),
                    List.of("zeme", "partnerska zeme", "country", "partner country")),
            new DimensionRule(
                    List.of("by currency", "currency"),
                    List.of("mena", "currency", "denomination")),
            new DimensionRule(
                    List.of("per capita", "per inhabitant", "per head"),
                    List.of("na obyvatele", "na hlavu", "per capita", "per inhabitant")));

    /** Minimální délka členu — Python {@code len(mf) >= 3}. */
    private static final int MIN_TERM_LENGTH = 3;

    /**
     * Foldování shodné s Python {@code fold_ascii}: NFD → zahodit non-spacing marks → lower.
     *
     * <p>Viz poznámka v javadocu třídy, proč ne {@link CatalogTextUtils#foldAscii} (NFKD).
     */
    public static String foldNfd(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.getType(ch) != Character.NON_SPACING_MARK) {
                sb.append(ch);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Členové dimenzí pro daný titul. Prázdný seznam, když titul žádnou dimenzi nejmenuje.
     *
     * <p>Pořadí odpovídá pořadí pravidel v {@code _DIMENSION_MAP} a duplicity se zahazují —
     * stejně jako v Pythonu, aby {@code search_blob} vyšel znak po znaku stejně.
     */
    public static List<String> forTitle(String titleText) {
        String folded = foldNfd(titleText);
        if (folded.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DimensionRule rule : DIMENSION_MAP) {
            boolean triggered = false;
            for (String trigger : rule.titleTriggers()) {
                if (folded.contains(trigger)) {
                    triggered = true;
                    break;
                }
            }
            if (!triggered) {
                continue;
            }
            for (String member : rule.memberTerms()) {
                String memberFolded = foldNfd(member);
                if (memberFolded.length() >= MIN_TERM_LENGTH && seen.add(memberFolded)) {
                    out.add(memberFolded);
                }
            }
        }
        return out;
    }
}
