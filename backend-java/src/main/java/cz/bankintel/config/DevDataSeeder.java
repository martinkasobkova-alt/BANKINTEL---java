package cz.bankintel.config;

import cz.bankintel.domain.entity.ArticleEntity;
import cz.bankintel.domain.entity.MagazineEntity;
import cz.bankintel.domain.entity.MagazineIssueEntity;
import cz.bankintel.domain.entity.PodcastEpisodeEntity;
import cz.bankintel.domain.entity.PodcastShowEntity;
import cz.bankintel.domain.entity.SourceEntity;
import cz.bankintel.domain.entity.StoredFileEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.ArticleRepository;
import cz.bankintel.repository.MagazineIssueRepository;
import cz.bankintel.repository.MagazineRepository;
import cz.bankintel.repository.PodcastEpisodeRepository;
import cz.bankintel.repository.PodcastShowRepository;
import cz.bankintel.repository.SourceRepository;
import cz.bankintel.repository.UserRepository;
import cz.bankintel.service.sources.ConnectorRegistry;
import cz.bankintel.storage.MagazineStorageService;
import java.util.HashMap;
import java.util.List;
import cz.bankintel.util.IdGenerator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("!prod")
@RequiredArgsConstructor
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final BankIntelProperties properties;
    private final UserRepository userRepository;
    private final SourceRepository sourceRepository;
    private final PasswordEncoder passwordEncoder;
    private final MagazineRepository magazineRepository;
    private final MagazineIssueRepository magazineIssueRepository;
    private final ArticleRepository articleRepository;
    private final PodcastShowRepository podcastShowRepository;
    private final PodcastEpisodeRepository podcastEpisodeRepository;
    private final MagazineStorageService magazineStorageService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.dev().seed()) {
            return;
        }
        if (userRepository.findByEmailIgnoreCase("admin@bankintel.local").isEmpty()) {
            UserEntity admin = new UserEntity();
            admin.setId(IdGenerator.newId());
            admin.setEmail("admin@bankintel.local");
            admin.setName("Admin Dev");
            admin.setCompany("BankIntel");
            admin.setPhone("+420000000000");
            admin.setRole("admin");
            admin.setAccessTier("subscriber");
            admin.setHasPremiumAccess(true);
            admin.setPremiumAccessGrantedAt(Instant.now());
            admin.setPasswordHash(passwordEncoder.encode("admin123"));
            admin.setEmailVerified(true);
            admin.setEmailVerifiedAt(Instant.now());
            userRepository.save(admin);
            log.info("Dev seed user created: admin@bankintel.local / admin123");
        }
        seedDemoSource();
        seedMagazineArchive();
        seedNewsArticles();
        seedPodcasts();
    }

    private void seedDemoSource() {
        if (sourceRepository.findByName("Eurostat · HICP roční změna (CZ)").isPresent()) {
            return;
        }
        SourceEntity source = new SourceEntity();
        source.setId(IdGenerator.newId());
        source.setName("Eurostat · HICP roční změna (CZ)");
        source.setSourceType("eurostat");
        source.setBaseUrl("https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data");
        source.setEndpoint("/prc_hicp_manr");
        source.setMethod("GET");
        source.setAuthType("none");
        source.setRefreshIntervalMinutes(720);
        source.setActive(true);
        source.setDatasetName("eurostat_hicp_cz");
        source.setConnectorConfig(ConnectorRegistry.buildConnectorConfig(
                Map.of("Accept", "application/json"),
                Map.of("format", "JSON", "lang", "EN", "geo", "CZ", "coicop", "CP00"),
                Map.of()));
        sourceRepository.save(source);
        log.info("Dev seed demo source created: {}", source.getName());
    }

    /**
     * Základní přehled pro bankovnictví — makroukazatele, ziskovost bank, sazby a nemovitosti.
     *
     * Proč seedem a ne klikáním v adminu: dev databáze je efemérní (embedded Postgres se zakládá
     * při každém startu), takže ručně naklikané widgety by restart nepřežily. Takhle je přehled
     * po každém startu stejný a je vidět v kódu, z jakých řad vzniká.
     *
     * Všechny řady jsou ověřené, že vrací data. Idempotentní: seedujeme jen do prázdné tabulky.
     */
    /**
     * Dev-only ukázková data pro obsahové sekce (Archiv/Zprávy/Podcasty), aby šlo v dev prostředí
     * reálně otestovat procházení — v čisté (efemérní) dev DB jsou tabulky prázdné. Idempotentní:
     * seedujeme jen když je příslušná tabulka prázdná, takže restart / opětovné spuštění nic
     * neduplikuje. Běží pod {@code @Profile("!prod")}, do produkce se tedy nikdy nedostane.
     */
    private void seedMagazineArchive() {
        if (magazineRepository.count() > 0) {
            return;
        }
        MagazineEntity magazine = new MagazineEntity();
        magazine.setId(IdGenerator.newId());
        magazine.setTitle("Bankovnictví");
        magazine.setSlug("bankovnictvi");
        magazine.setDescription("Odborný měsíčník o bankovním a finančním sektoru — ukázková složka (dev).");
        magazineRepository.save(magazine);

        String[][] issues = {
            {"1/2026", "Bankovnictví 1/2026", "2026-01", "Leden 2026 — úvodní číslo ročníku."},
            {"12/2025", "Bankovnictví 12/2025", "2025-12", "Prosinec 2025 — roční přehled sektoru."},
        };
        int pages = 6;
        for (String[] i : issues) {
            String issueId = IdGenerator.newId();
            // Reálné (byť ukázkové) PDF, aby GET /issues/{id}/file vrátilo 200 a čtečka fungovala
            // (zoom, propojení). Bez tohohle měla čísla jen metadata a /file házelo 404.
            byte[] pdf = sampleIssuePdf("Bankovnictvi " + i[0], pages);
            String fileName = "bankovnictvi-" + i[2] + ".pdf";
            StoredFileEntity stored = magazineStorageService.storePdf(magazine.getId(), issueId, fileName, pdf);
            MagazineIssueEntity issue = new MagazineIssueEntity();
            issue.setId(issueId);
            issue.setMagazineId(magazine.getId());
            issue.setIssueLabel(i[0]);
            issue.setTitle(i[1]);
            issue.setDescription(i[3]);
            issue.setPublishedAt(i[2]);
            issue.setOriginalName(stored.getOriginalName());
            issue.setStoredFileId(stored.getId());
            issue.setSizeBytes(pdf.length);
            issue.setIngestStatus("ready");
            issue.setPageCount(pages);
            issue.setChunkCount(0);
            magazineIssueRepository.save(issue);
        }
        log.info("Dev seed: magazine '{}' + {} issue(s) created (with sample PDF)", magazine.getTitle(), issues.length);
    }

    /**
     * Generates a small, valid multi-page sample PDF for dev seed issues via PDFBox 2.x. Text is
     * deliberately ASCII-only: the built-in Helvetica (WinAnsi) cannot encode Czech diacritics and
     * would otherwise throw during {@code showText}. The content only needs to be readable so the
     * reader / zoom / links can be exercised.
     */
    private static byte[] sampleIssuePdf(String coverLine, int pages) {
        try (PDDocument doc = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int p = 1; p <= pages; p++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 22);
                    cs.newLineAtOffset(72, 720);
                    cs.showText(coverLine);
                    cs.endText();
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA, 13);
                    cs.newLineAtOffset(72, 690);
                    cs.showText("Strana " + p + " / " + pages + " - ukazkove PDF (dev seed).");
                    cs.endText();
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA, 11);
                    cs.newLineAtOffset(72, 660);
                    cs.showText("Testovaci obsah pro overeni ctecky, zoomu a propojeni odkazu.");
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Sample PDF generation failed", e);
        }
    }

    private void seedNewsArticles() {
        if (articleRepository.count() > 0) {
            return;
        }
        Instant now = Instant.now();
        String[][] articles = {
            {
                "cnb-ponechala-sazby-beze-zmeny",
                "ČNB ponechala úrokové sazby beze změny",
                "Bankovní rada ponechala dvoutýdenní repo sazbu na stávající úrovni a signalizuje opatrný postup.",
                "Bankovní rada České národní banky na svém zasedání rozhodla ponechat úrokové sazby "
                    + "beze změny. Guvernér zdůraznil, že další kroky budou záviset na vývoji inflace a "
                    + "kurzu koruny. (Ukázkový článek pro dev prostředí.)"
            },
            {
                "inflace-v-cesku-zpomalila",
                "Inflace v Česku dále zpomalila",
                "Meziroční růst spotřebitelských cen se přiblížil k cíli centrální banky.",
                "Podle nejnovějších dat meziroční inflace v České republice dále zpomalila a přiblížila "
                    + "se dvouprocentnímu cíli ČNB. Analytici očekávají stabilizaci v následujících měsících. "
                    + "(Ukázkový článek pro dev prostředí.)"
            },
        };
        for (String[] a : articles) {
            ArticleEntity article = new ArticleEntity();
            article.setId(IdGenerator.newId());
            article.setSlug(a[0]);
            article.setTitle(a[1]);
            article.setSummary(a[2]);
            article.setBody(a[3]);
            article.setAuthorName("Redakce Bankovnictví");
            article.setPublished(true);
            article.setPublishedAt(now);
            articleRepository.save(article);
        }
        log.info("Dev seed: {} published article(s) created", articles.length);
    }

    private void seedPodcasts() {
        if (podcastShowRepository.count() > 0) {
            return;
        }
        PodcastShowEntity show = new PodcastShowEntity();
        show.setId(IdGenerator.newId());
        show.setTitle("Bankovnictví Podcast");
        show.setDescription("Rozhovory o dění ve finančním sektoru — ukázkový pořad (dev).");
        show.setSortOrder(0);
        podcastShowRepository.save(show);

        String[][] episodes = {
            {"Díl 1: Výhled sazeb pro rok 2026", "O tom, kam míří úrokové sazby a co to znamená pro banky."},
            {"Díl 2: Inflace a koruna", "Jak spolu souvisí inflace, měnová politika a kurz koruny."},
        };
        for (String[] e : episodes) {
            PodcastEpisodeEntity episode = new PodcastEpisodeEntity();
            episode.setId(IdGenerator.newId());
            episode.setShowId(show.getId());
            episode.setTitle(e[0]);
            episode.setSummary(e[1]);
            episode.setSource("external");
            episode.setExternalUrl("https://www.bankovnictvionline.cz");
            episode.setPublished(true);
            podcastEpisodeRepository.save(episode);
        }
        log.info("Dev seed: podcast show '{}' + {} episode(s) created", show.getTitle(), episodes.length);
    }
}
