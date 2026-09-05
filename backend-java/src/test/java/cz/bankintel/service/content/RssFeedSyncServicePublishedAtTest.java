package cz.bankintel.service.content;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Datum vydání položky se dřív z kanálu vůbec nečetlo — `syncFeed`/`syncFeedSystem` každé
 * položce natvrdo nastavily `Instant.now()`. Živě ověřeno na reálném BBC RSS kanálu: kanál měl
 * v `<pubDate>` časy rozeslané přes několik hodin i dnů, appka je všechny uložila se stejným
 * časem (okamžik synchronizace) — staré i nové články tak vypadaly stejně "čerstvé" a řazení
 * podle `published_at` bylo ve skutečnosti řazení podle toho, kdy appka feed naposled stáhla.
 */
class RssFeedSyncServicePublishedAtTest {

    private static final RssFeedSyncService SERVICE = new RssFeedSyncService(null, null, null);

    @Test
    void rfc1123PubDateSeSpravneNaparsuje() {
        Instant parsed = RssFeedSyncService.parsePublishedAt("Thu, 03 Sep 2026 13:26:54 GMT");
        assertThat(parsed).isEqualTo(Instant.parse("2026-09-03T13:26:54Z"));
    }

    @Test
    void iso8601AtomPublishedSeSpravneNaparsuje() {
        Instant parsed = RssFeedSyncService.parsePublishedAt("2026-09-03T13:26:54Z");
        assertThat(parsed).isEqualTo(Instant.parse("2026-09-03T13:26:54Z"));
    }

    @Test
    void prazdneNeboNesmyslneDatumVratiNullMistoPadu() {
        assertThat(RssFeedSyncService.parsePublishedAt("")).isNull();
        assertThat(RssFeedSyncService.parsePublishedAt(null)).isNull();
        assertThat(RssFeedSyncService.parsePublishedAt("nesmysl")).isNull();
    }

    @Test
    void parseItemsVytahnePubDateZRssPolozky() throws Exception {
        String xml = """
                <?xml version="1.0"?>
                <rss version="2.0"><channel>
                <item>
                  <title>Test</title>
                  <link>https://example.test/a</link>
                  <description>Popis</description>
                  <pubDate>Thu, 03 Sep 2026 13:26:54 GMT</pubDate>
                </item>
                </channel></rss>
                """;
        List<Map<String, Object>> items = SERVICE.parseItems(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().get("published_raw")).isEqualTo("Thu, 03 Sep 2026 13:26:54 GMT");
    }

    @Test
    void parseItemsVytahnePublishedZAtomPolozky() throws Exception {
        String xml = """
                <?xml version="1.0"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                <entry>
                  <title>Test</title>
                  <link href="https://example.test/a" />
                  <summary>Popis</summary>
                  <published>2026-09-03T13:26:54Z</published>
                </entry>
                </feed>
                """;
        List<Map<String, Object>> items = SERVICE.parseItems(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().get("published_raw")).isEqualTo("2026-09-03T13:26:54Z");
    }

    @Test
    void chybejiciDatumDaPrazdnyRetezecNeNull() throws Exception {
        // Map.of() nesmí dostat null - položka bez data musí dát "", ne shodit parsování.
        String xml = """
                <?xml version="1.0"?>
                <rss version="2.0"><channel>
                <item>
                  <title>Bez data</title>
                  <link>https://example.test/b</link>
                </item>
                </channel></rss>
                """;
        List<Map<String, Object>> items = SERVICE.parseItems(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().get("published_raw")).isEqualTo("");
    }
}
