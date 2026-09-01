package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parita s referenčním Pythonem.
 *
 * <p>Očekávané hodnoty nejsou vymyšlené — jsou to doslovné výstupy
 * {@code services/classic_catalog_relevance_scoring.py:dimension_member_terms_for_title}
 * spuštěného nad stejnými tituly. Když se tenhle test rozbije, znamená to, že Javou postavený
 * {@code search_blob} přestal odpovídat tomu Pythonímu — tedy že se změní výsledky hledání.
 */
class CatalogDimensionMemberTermsTest {

    @Test
    void anglickyTitulSDimenziPohlaviDaStejneClenyJakoPython() {
        assertEquals(
                List.of("zeny", "muzi", "pohlavi", "female", "male", "women", "men", "females", "males", "gender"),
                CatalogDimensionMemberTerms.forTitle("Unemployment rate by sex and age"));
    }

    @Test
    void ceskyTitulSDiakritikouSePriradiStejneJakoAnglicky() {
        // Ověřuje foldování: "podle pohlaví" -> "podle pohlavi" musí spustit stejné pravidlo.
        assertEquals(
                List.of("zeny", "muzi", "pohlavi", "female", "male", "women", "men", "females", "males", "gender"),
                CatalogDimensionMemberTerms.forTitle("Nezaměstnanost podle pohlaví"));
    }

    @Test
    void titulBezDimenzeNedostaneNic() {
        // Cílenost: obecné řady se nesmí zašumět, jinak by se index nafoukl o nesouvisející termy.
        assertTrue(CatalogDimensionMemberTerms.forTitle("HDP").isEmpty());
        assertTrue(CatalogDimensionMemberTerms.forTitle("").isEmpty());
        assertTrue(CatalogDimensionMemberTerms.forTitle(null).isEmpty());
    }

    @Test
    void vzdelaniANaceDavajiPythonovyVystup() {
        assertEquals(
                List.of("vzdelani", "vzdelanostni uroven", "zakladni", "stredni", "vysokoskolske", "terciarni",
                        "primary", "secondary", "tertiary", "isced", "educational attainment", "education level"),
                CatalogDimensionMemberTerms.forTitle("Population by educational attainment level"));
        assertEquals(
                List.of("odvetvi", "ekonomicka cinnost", "sektor", "prumysl", "sluzby", "zemedelstvi",
                        "stavebnictvi", "nace", "industry", "services", "agriculture", "manufacturing",
                        "economic activity", "sector"),
                CatalogDimensionMemberTerms.forTitle("Employment by NACE Rev. 2 activity"));
        assertEquals(
                List.of("na obyvatele", "na hlavu", "per capita", "per inhabitant"),
                CatalogDimensionMemberTerms.forTitle("GDP per capita"));
    }

    @Test
    void viceDimenziZachovavaPoradiPravidelAZahazujeDuplicity() {
        // Titul spouští region i household. Pořadí musí být podle pořadí pravidel v mapě (region
        // dřív než household), ne podle pořadí výskytu v titulu - jinak vyjde jiný search_blob.
        assertEquals(
                List.of("region", "kraj", "kraje", "regiony", "nuts", "regional", "by region",
                        "domacnosti", "domacnost", "typ domacnosti", "slozeni domacnosti", "sektor domacnosti",
                        "households", "household composition", "household type", "household sector"),
                CatalogDimensionMemberTerms.forTitle("Households by type of household and by region"));
    }

    @Test
    void foldujeSePresNfdStejneJakoPython() {
        assertEquals("prijmy zeny vek", CatalogDimensionMemberTerms.foldNfd("Příjmy Ženy věk"));
        assertEquals("", CatalogDimensionMemberTerms.foldNfd(null));
    }
}
