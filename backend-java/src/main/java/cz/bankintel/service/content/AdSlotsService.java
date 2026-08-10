package cz.bankintel.service.content;

import cz.bankintel.domain.entity.AppSettingsEntity;
import cz.bankintel.repository.AppSettingsRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdSlotsService {

    private static final String SETTINGS_ID = "ad_slots";
    private static final List<String> SLOT_NAMES = List.of("sidebar", "topbar");

    private final AppSettingsRepository appSettingsRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> getAdSlots() {
        Map<String, Object> stored = loadStored();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String slotName : SLOT_NAMES) {
            out.put(slotName, normalizeSlot(stored.get(slotName)));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> updateSlot(String slotName, Map<String, Object> payload) {
        if (!SLOT_NAMES.contains(slotName)) {
            throw new IllegalArgumentException("Unknown slot '" + slotName + "'");
        }
        AppSettingsEntity entity = appSettingsRepository
                .findById(SETTINGS_ID)
                .orElseGet(() -> {
                    AppSettingsEntity created = new AppSettingsEntity();
                    created.setId(SETTINGS_ID);
                    created.setSettingsJson(new LinkedHashMap<>());
                    return created;
                });
        Map<String, Object> json = new LinkedHashMap<>(entity.getSettingsJson() != null ? entity.getSettingsJson() : Map.of());
        json.put(slotName, normalizeSlot(payload));
        entity.setSettingsJson(json);
        appSettingsRepository.save(entity);
        return getAdSlots();
    }

    private Map<String, Object> loadStored() {
        return appSettingsRepository
                .findById(SETTINGS_ID)
                .map(AppSettingsEntity::getSettingsJson)
                .orElse(Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeSlot(Object raw) {
        Map<String, Object> defaults = defaultSlot();
        if (!(raw instanceof Map<?, ?> map)) {
            return defaults;
        }
        Map<String, Object> out = new LinkedHashMap<>(defaults);
        out.put("enabled", Boolean.TRUE.equals(map.get("enabled")));
        @SuppressWarnings("unchecked")
        Map<String, Object> slotMap = (Map<String, Object>) map;
        String kind = String.valueOf(slotMap.getOrDefault("kind", "image")).trim().toLowerCase();
        out.put("kind", List.of("image", "richtext", "html").contains(kind) ? kind : "image");
        for (String key : List.of("image_url", "link_url", "alt", "content", "html")) {
            Object value = slotMap.get(key);
            out.put(key, value == null ? "" : String.valueOf(value));
        }
        String imageMode = String.valueOf(slotMap.getOrDefault("image_mode", "single")).trim().toLowerCase();
        out.put("image_mode", "carousel".equals(imageMode) ? "carousel" : "single");
        out.put("slides", normalizeSlides(slotMap.get("slides")));
        try {
            int interval = Integer.parseInt(String.valueOf(slotMap.getOrDefault("carousel_interval_sec", 5)));
            out.put("carousel_interval_sec", Math.max(2, Math.min(interval, 60)));
        } catch (NumberFormatException ex) {
            out.put("carousel_interval_sec", 5);
        }
        return out;
    }

    private static List<Map<String, String>> normalizeSlides(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String imageUrl = String.valueOf(map.get("image_url") != null ? map.get("image_url") : "").trim();
            if (imageUrl.isBlank()) {
                continue;
            }
            out.add(Map.of(
                    "image_url", imageUrl.length() > 4000 ? imageUrl.substring(0, 4000) : imageUrl,
                    "link_url", trim(String.valueOf(map.get("link_url")), 4000),
                    "alt", trim(String.valueOf(map.get("alt")), 500)));
            if (out.size() >= 20) {
                break;
            }
        }
        return out;
    }

    private static String trim(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static Map<String, Object> defaultSlot() {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("enabled", false);
        slot.put("kind", "image");
        slot.put("image_url", "");
        slot.put("link_url", "");
        slot.put("alt", "");
        slot.put("content", "");
        slot.put("html", "");
        slot.put("image_mode", "single");
        slot.put("slides", List.of());
        slot.put("carousel_interval_sec", 5);
        return slot;
    }
}
