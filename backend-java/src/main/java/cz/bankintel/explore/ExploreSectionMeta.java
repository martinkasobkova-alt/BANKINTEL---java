package cz.bankintel.explore;

import java.util.List;
import java.util.Map;

public final class ExploreSectionMeta {

    public record SectionDef(String id, String title, String analysisKey, String instruction) {}

    public static final List<SectionDef> SYNTHETIC_SECTIONS = List.of(
            new SectionDef(
                    "executive_verdict",
                    "Executive verdict",
                    "executive_verdict_analysis",
                    "Shrň verdikt pro manažera z nejdůležitějších dostupných řad a skórovacích signálů."),
            new SectionDef(
                    "limitations_data_quality",
                    "Limitace a kvalita dat",
                    "limitations_data_quality_analysis",
                    "Vyhodnoť kvalitu dat, proxy/fallback omezení, chybějící oblasti a důvěryhodnost výstupu."));

    public static final List<SectionDef> REPORT_SECTIONS = List.of(
            new SectionDef(
                    "sector",
                    "Hlavní segment",
                    "sector_analysis",
                    "Vyhodnoť hlavní cílový segment — poptávka, výkon, marže, objednávky a signály z odvětvových dat."),
            new SectionDef(
                    "related_sectors",
                    "Přidružené segmenty",
                    "related_sectors_analysis",
                    "Vyhodnoť související a závislá odvětví a jejich dopad na hlavní segment."),
            new SectionDef(
                    "macro",
                    "Lokální ekonomika",
                    "macro_analysis",
                    "Vyhodnoť makro prostředí primární země — inflace, HDP, nezaměstnanost, sazby a poptávka."),
            new SectionDef(
                    "political_situation",
                    "Politická situace",
                    "political_situation_analysis",
                    "Shrň politickou stabilitu primární země a zda je vhodné prostředí pro rozhodování z uživatelského dotazu. Uveď nejistotu a zdroje, pokud jsou dostupné."),
            new SectionDef(
                    "regional_economy",
                    "Oblast a přidružené země",
                    "regional_economy_analysis",
                    "Vyhodnoť regionální kontext: sousedé, partneři a EU prostředí."),
            new SectionDef(
                    "global",
                    "Světová ekonomika",
                    "global_analysis",
                    "Vyhodnoť globální kotvy a světové trendy relevantní pro sektor."),
            new SectionDef(
                    "financial_markets",
                    "Trhy a sentiment",
                    "financial_markets_analysis",
                    "Vyhodnoť finanční trhy — sazby, výnosy, volatilitu a sentiment."),
            new SectionDef(
                    "commodities",
                    "Komodity a energie",
                    "commodities_analysis",
                    "Vyhodnoť komoditní a energetické vstupy a přenos do marží."),
            new SectionDef(
                    "demographics",
                    "Demografie",
                    "demographics_analysis",
                    "Vyhodnoť demografické a strukturální poptávkové faktory."));

    public static List<SectionDef> activeSections() {
        // Order: executive → sector → related → macro → political → regional → global → markets → commodities → demographics → limitations
        return List.of(
                SYNTHETIC_SECTIONS.get(0),
                REPORT_SECTIONS.get(0),
                REPORT_SECTIONS.get(1),
                REPORT_SECTIONS.get(2),
                REPORT_SECTIONS.get(3),
                REPORT_SECTIONS.get(4),
                REPORT_SECTIONS.get(5),
                REPORT_SECTIONS.get(6),
                REPORT_SECTIONS.get(7),
                REPORT_SECTIONS.get(8),
                SYNTHETIC_SECTIONS.get(1));
    }

    private ExploreSectionMeta() {}
}
