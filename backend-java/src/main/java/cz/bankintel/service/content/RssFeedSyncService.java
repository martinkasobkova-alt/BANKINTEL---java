package cz.bankintel.service.content;

import cz.bankintel.domain.entity.RssFeedEntity;
import cz.bankintel.domain.entity.RssItemEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.RssFeedRepository;
import cz.bankintel.repository.RssItemRepository;
import cz.bankintel.util.IdGenerator;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

@Service
@RequiredArgsConstructor
public class RssFeedSyncService {

    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Pattern TAG = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final RssFeedRepository feedRepository;
    private final RssItemRepository itemRepository;
    private final RssService rssService;

    @Transactional(readOnly = true)
    public Map<String, Object> validateFeed(UserEntity user, String feedId) {
        RssFeedEntity feed = requireFeed(user, feedId);
        return validateUrl(feed.getUrl(), feed.getSourceType());
    }

    @Transactional
    public Map<String, Object> syncFeed(UserEntity user, String feedId) {
        RssFeedEntity feed = requireFeed(user, feedId);
        Map<String, Object> validation = validateUrl(feed.getUrl(), feed.getSourceType());
        if (validation.containsKey("error")) {
            feed.setLastSyncStatus("error");
            feed.setLastSyncMessage(String.valueOf(validation.get("error")));
            feed.setLastSyncAt(Instant.now());
            feedRepository.save(feed);
            return validation;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) validation.getOrDefault("sample_items", List.of());
        int inserted = 0;
        for (Map<String, Object> item : items) {
            String link = String.valueOf(item.getOrDefault("link", "")).strip();
            if (link.isBlank()) {
                continue;
            }
            if (itemRepository.findFirstByFeedIdAndLink(feed.getId(), link).isPresent()) {
                continue;
            }
            RssItemEntity entity = new RssItemEntity();
            entity.setId(IdGenerator.newId());
            entity.setFeedId(feed.getId());
            entity.setTitle(String.valueOf(item.getOrDefault("title", "")).strip());
            entity.setLink(link);
            // Datum vydání se dřív nikdy nečetlo z kanálu - každá položka dostala čas
            // synchronizace, takže staré i nové články vypadaly stejně "čerstvé" a řazení podle
            // published_at bylo ve skutečnosti řazení podle toho, kdy appka feed naposled stáhla.
            Instant published = parsePublishedAt(String.valueOf(item.getOrDefault("published_raw", "")));
            entity.setPublishedAt(published != null ? published : Instant.now());
            entity.setSummary(String.valueOf(item.getOrDefault("summary", "")).strip());
            entity.setCreatedAt(Instant.now());
            itemRepository.save(entity);
            inserted++;
        }
        feed.setLastSyncAt(Instant.now());
        feed.setLastSyncStatus("ok");
        feed.setLastSyncMessage("Synchronizováno " + inserted + " nových položek.");
        feedRepository.save(feed);
        Map<String, Object> out = new LinkedHashMap<>(validation);
        out.put("ok", true);
        out.put("inserted", inserted);
        return out;
    }

    /** System-level sync for scheduled jobs — no user ownership check. */
    @Transactional
    public Map<String, Object> syncFeedSystem(RssFeedEntity feed) {
        Map<String, Object> validation = validateUrl(feed.getUrl(), feed.getSourceType());
        if (validation.containsKey("error")) {
            feed.setLastSyncStatus("error");
            feed.setLastSyncMessage(String.valueOf(validation.get("error")));
            feed.setLastSyncAt(Instant.now());
            feedRepository.save(feed);
            return validation;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) validation.getOrDefault("sample_items", List.of());
        int inserted = 0;
        for (Map<String, Object> item : items) {
            String link = String.valueOf(item.getOrDefault("link", "")).strip();
            if (link.isBlank()) {
                continue;
            }
            if (itemRepository.findFirstByFeedIdAndLink(feed.getId(), link).isPresent()) {
                continue;
            }
            RssItemEntity entity = new RssItemEntity();
            entity.setId(IdGenerator.newId());
            entity.setFeedId(feed.getId());
            entity.setTitle(String.valueOf(item.getOrDefault("title", "")).strip());
            entity.setLink(link);
            // Datum vydání se dřív nikdy nečetlo z kanálu - každá položka dostala čas
            // synchronizace, takže staré i nové články vypadaly stejně "čerstvé" a řazení podle
            // published_at bylo ve skutečnosti řazení podle toho, kdy appka feed naposled stáhla.
            Instant published = parsePublishedAt(String.valueOf(item.getOrDefault("published_raw", "")));
            entity.setPublishedAt(published != null ? published : Instant.now());
            entity.setSummary(String.valueOf(item.getOrDefault("summary", "")).strip());
            entity.setCreatedAt(Instant.now());
            itemRepository.save(entity);
            inserted++;
        }
        feed.setLastSyncAt(Instant.now());
        feed.setLastSyncStatus("ok");
        feed.setLastSyncMessage("Synchronizováno " + inserted + " nových položek (scheduled).");
        feedRepository.save(feed);
        Map<String, Object> out = new LinkedHashMap<>(validation);
        out.put("ok", true);
        out.put("inserted", inserted);
        out.put("feed_id", feed.getId());
        return out;
    }

    public boolean isDueForSync(RssFeedEntity feed) {
        if (feed == null || !feed.isEnabled()) {
            return false;
        }
        int intervalMin = Math.max(5, feed.getRefreshIntervalMinutes());
        Instant last = feed.getLastSyncAt();
        if (last == null) {
            return true;
        }
        return last.plusSeconds(intervalMin * 60L).isBefore(Instant.now());
    }

    private RssFeedEntity requireFeed(UserEntity user, String feedId) {
        rssService.listFeeds(user);
        return feedRepository
                .findById(feedId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Feed nenalezen"));
    }

    private Map<String, Object> validateUrl(String url, String sourceType) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url.trim())).timeout(TIMEOUT).GET().build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                return Map.of("ok", false, "error", "HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body.length > MAX_BYTES) {
                return Map.of("ok", false, "error", "Odpověď je příliš velká.");
            }
            List<Map<String, Object>> items = parseItems(body);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("source_type", sourceType);
            out.put("item_count", items.size());
            out.put("sample_items", items.stream().limit(20).toList());
            return out;
        } catch (Exception ex) {
            return Map.of("ok", false, "error", ex.getMessage() != null ? ex.getMessage() : "Validace selhala");
        }
    }

    List<Map<String, Object>> parseItems(byte[] body) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            Document doc = secureDocumentBuilderFactory()
                    .newDocumentBuilder()
                    .parse(new ByteArrayInputStream(body));
            NodeList nodes = doc.getElementsByTagName("item");
            if (nodes.getLength() == 0) {
                nodes = doc.getElementsByTagName("entry");
            }
            for (int i = 0; i < nodes.getLength() && i < 50; i++) {
                Element el = (Element) nodes.item(i);
                String title = text(el, "title");
                String link = attr(el, "link", "href");
                if (link.isBlank()) {
                    link = text(el, "link");
                }
                String summary = firstNonBlank(text(el, "description"), text(el, "summary"), text(el, "content"));
                if (title.isBlank() && link.isBlank()) {
                    continue;
                }
                // RSS 2.0 má <pubDate> (RFC 1123), Atom <published>/<updated> (ISO 8601) - obojí
                // pod jedním klíčem, přesný formát rozliší až parsePublishedAt při ukládání.
                String publishedRaw = firstNonBlank(text(el, "pubDate"), text(el, "published"), text(el, "updated"), text(el, "date"));
                items.add(Map.of("title", title, "link", link, "summary", summary, "published_raw", publishedRaw));
            }
        } catch (Exception ignored) {
            String text = new String(body);
            Matcher matcher = TAG.matcher(text);
            while (matcher.find() && items.size() < 20) {
                items.add(Map.of("title", stripTags(matcher.group(1)), "link", "", "summary", ""));
            }
        }
        return items;
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static String text(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() == 0) {
            return "";
        }
        return stripTags(list.item(0).getTextContent());
    }

    private static String attr(Element parent, String tag, String attr) {
        NodeList list = parent.getElementsByTagName(tag);
        if (list.getLength() == 0 || !(list.item(0) instanceof Element el)) {
            return "";
        }
        return el.hasAttribute(attr) ? el.getAttribute(attr).strip() : "";
    }

    private static String stripTags(String value) {
        return value == null ? "" : value.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
    }

    /**
     * RSS 2.0 {@code <pubDate>} je RFC 1123 ("Thu, 03 Sep 2026 13:26:54 GMT"), Atom
     * {@code <published>}/{@code <updated>} je ISO 8601 ("2026-09-03T13:26:54Z") - zkusí obojí,
     * {@code null} při selhání obou (volající pak sáhne po {@code Instant.now()} jako záloze,
     * ne přednostně).
     */
    static Instant parsePublishedAt(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value));
        } catch (DateTimeParseException ignored) {
            // spadne na ISO 8601 níž
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
