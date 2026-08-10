package cz.bankintel.service.settings;

import cz.bankintel.domain.entity.AppSettingsEntity;
import cz.bankintel.repository.AppSettingsRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AppSettingsService {

    private static final String GLOBAL_SETTINGS_ID = "global";
    private static final Set<String> ALLOWED_APPEARANCE_IDS = Set.of(
            "blue",
            "soft-blue-ivory",
            "nude-rose-gold",
            "lavender-pearl",
            "mint-sand",
            "navy-champagne",
            "blush-cream",
            "sage-beige",
            "slate-ice-blue",
            "dusty-pink-taupe",
            "lilac-silver",
            "graphite-pearl",
            "onyx-copper");

    private final AppSettingsRepository appSettingsRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> getAppSettings() {
        return serialize(loadOrCreate());
    }

    @Transactional
    public Map<String, Object> patchAppSettings(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nic ke změně.");
        }
        AppSettingsEntity entity = loadOrCreate();
        Map<String, Object> json = new LinkedHashMap<>(entity.getSettingsJson() != null ? entity.getSettingsJson() : Map.of());
        boolean changed = false;
        if (payload.containsKey("default_appearance_id")) {
            String aid = String.valueOf(payload.get("default_appearance_id") != null ? payload.get("default_appearance_id") : "")
                    .trim();
            if (!ALLOWED_APPEARANCE_IDS.contains(aid)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatné ID barevného schématu.");
            }
            json.put("default_appearance_id", aid);
            changed = true;
        }
        if (payload.containsKey("podcast_feed_urls")) {
            json.put("podcast_feed_urls", normalizePodcastUrls(payload.get("podcast_feed_urls")));
            changed = true;
        }
        if (!changed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nic ke změně.");
        }
        entity.setSettingsJson(json);
        entity.setUpdatedAt(Instant.now());
        appSettingsRepository.save(entity);
        return serialize(entity);
    }

    private AppSettingsEntity loadOrCreate() {
        return appSettingsRepository
                .findById(GLOBAL_SETTINGS_ID)
                .orElseGet(() -> {
                    AppSettingsEntity created = new AppSettingsEntity();
                    created.setId(GLOBAL_SETTINGS_ID);
                    created.setSettingsJson(new LinkedHashMap<>(Map.of("default_appearance_id", "blue")));
                    return appSettingsRepository.save(created);
                });
    }

    private static Map<String, Object> serialize(AppSettingsEntity entity) {
        Map<String, Object> json = entity.getSettingsJson() != null ? entity.getSettingsJson() : Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("default_appearance_id", json.getOrDefault("default_appearance_id", "blue"));
        out.put("podcast_feed_urls", readPodcastUrls(json.get("podcast_feed_urls")));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> readPodcastUrls(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return (List<String>) list;
    }

    private static List<String> normalizePodcastUrls(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "podcast_feed_urls musí být pole URL.");
        }
        List<String> urls = new ArrayList<>();
        for (Object item : list) {
            String url = String.valueOf(item != null ? item : "").trim();
            if (!url.isBlank() && !urls.contains(url)) {
                urls.add(url.length() > 2000 ? url.substring(0, 2000) : url);
            }
        }
        return urls;
    }
}
