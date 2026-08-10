package cz.bankintel.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CzTextStemmerTest {

    @Test
    void householdNominativeAndDativeShareTheSameFtsPrefixStem() {
        assertThat(CzTextStemmer.ftsPrefixStem("domacnosti")).isEqualTo("domacnost");
        assertThat(CzTextStemmer.ftsPrefixStem("domacnostem")).isEqualTo("domacnost");
    }

    @Test
    void indebtednessDerivedFormSharesThePrefixOfTheBaseForm() {
        // zadluzeni ("indebting", the verbal noun) stems to "zadluzen"; zadluzenost ("indebtedness")
        // is a different derived word the stemmer does not shorten - but it still STARTS WITH the
        // other word's stem, which is exactly what an FTS5 prefix query needs, not stem equality.
        String stem = CzTextStemmer.ftsPrefixStem("zadluzeni");
        assertThat(stem).isEqualTo("zadluzen");
        assertThat("zadluzenost").startsWith(stem);
    }

    @Test
    void shortStemFallsBelowThePrefixFloorAndIsNotWidened() {
        // "uvery" (loans) stems to "uver" (4 chars) - below the 5-char floor, so callers must fall
        // through to an exact clause rather than a broad 4-char prefix.
        assertThat(CzTextStemmer.ftsPrefixStem("uvery")).isEmpty();
    }

    @Test
    void wordWithNoRecognizedCaseSuffixIsNotWidened() {
        assertThat(CzTextStemmer.ftsPrefixStem("banking")).isEmpty();
        assertThat(CzTextStemmer.ftsPrefixStem("credit")).isEmpty();
    }

    @Test
    void blankOrNullInputReturnsBlank() {
        assertThat(CzTextStemmer.ftsPrefixStem("")).isEmpty();
        assertThat(CzTextStemmer.ftsPrefixStem(null)).isEmpty();
        assertThat(CzTextStemmer.ftsPrefixStem("   ")).isEmpty();
    }

    @Test
    void customMinPrefixLenIsHonored() {
        assertThat(CzTextStemmer.ftsPrefixStem("uvery", 4)).isEqualTo("uver");
        assertThat(CzTextStemmer.ftsPrefixStem("uvery", 5)).isEmpty();
    }
}
