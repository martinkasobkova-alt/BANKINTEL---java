package cz.bankintel.search;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.bankintel.config.BankIntelProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogSearchMetadataSidecarTest {

    @TempDir
    Path tempDir;

    @Test
    void intentOverlapRowsAreReturnedBeforeBroadKeywordBankingRows() throws Exception {
        Files.writeString(
                tempDir.resolve("eurostat.jsonl"),
                """
                {"source":"eurostat","series_id":"sbs_cre_gfinsub","dataset_id":"sbs_cre_gfinsub","human_label_cs":"Financni dcerine spolecnosti","search_keywords_cs":["banky"],"intent_tags":["banking"]}
                {"source":"eurostat","series_id":"tipsbd40","dataset_id":"tipsbd40","human_label_cs":"Rentabilita vlastniho kapitalu bank","search_keywords_cs":["rentabilita vlastniho kapitalu","banky"],"search_keywords_en":["return on equity banks"],"intent_tags":["profitability","banking"]}
                {"source":"eurostat","series_id":"tipsbd30","dataset_id":"tipsbd30","human_label_cs":"Tier-1 kapitalova primerenost bank","search_keywords_cs":["bankovni sektor"],"intent_tags":["banking"]}
                """);
        CatalogSearchMetadataSidecar sidecar = new CatalogSearchMetadataSidecar(searchProperties(), new ObjectMapper());

        List<String> ids = sidecar.sidecarRetrievalSetIds("eurostat", "zisk bank cesko");

        assertTrue(ids.indexOf("tipsbd40") >= 0, "expected tipsbd40 in sidecar ids: " + ids);
        assertTrue(
                ids.indexOf("tipsbd40") < ids.indexOf("sbs_cre_gfinsub"),
                "profitability+banking row should outrank broad banking row: " + ids);
    }

    private CatalogSearchProperties searchProperties() {
        BankIntelProperties bankProps = new BankIntelProperties(
                new BankIntelProperties.Jwt("test-secret", 60, 7),
                new BankIntelProperties.Cors(""),
                new BankIntelProperties.Cookie(false, "Lax", ""),
                new BankIntelProperties.Dev(false, false),
                "",
                new BankIntelProperties.Catalog("", "", tempDir.toString()),
                new BankIntelProperties.Chat(""),
                new BankIntelProperties.Storage("", "", ""));
        return new CatalogSearchProperties(bankProps);
    }
}
