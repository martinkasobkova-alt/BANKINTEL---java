package cz.bankintel.service.content;

import cz.bankintel.domain.dto.ContentDtos.PodcastEpisodeResponse;
import cz.bankintel.domain.entity.PodcastEpisodeEntity;
import cz.bankintel.domain.entity.PodcastShowEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.PodcastEpisodeRepository;
import cz.bankintel.repository.PodcastShowRepository;
import cz.bankintel.service.upload.UploadPolicy;
import cz.bankintel.util.IdGenerator;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PodcastEpisodeWriteService {

    private static final long MAX_AUDIO_BYTES = 120L * 1024 * 1024;
    private static final long MAX_COVER_BYTES = 2L * 1024 * 1024;
    private static final Set<String> ALLOWED_AUDIO_EXT =
            Set.of(".mp3", ".m4a", ".mp4", ".aac", ".ogg", ".wav", ".mpeg");
    private static final Set<String> ALLOWED_COVER_EXT = Set.of(".jpg", ".jpeg", ".png", ".webp");
    private static final Pattern DIRECT_AUDIO =
            Pattern.compile("\\.(mp3|m4a|mp4|aac|ogg|wav|mpeg)(\\?|$)", Pattern.CASE_INSENSITIVE);

    private final PodcastShowRepository showRepository;
    private final PodcastEpisodeRepository episodeRepository;
    private final PodcastMediaStorageService mediaStorage;
    private final PodcastService podcastService;

    @Transactional
    public PodcastEpisodeResponse uploadEpisode(
            UserEntity user,
            MultipartFile file,
            String title,
            String summary,
            String showId,
            String feedTitle,
            MultipartFile coverImage,
            String coverImageUrl) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor je prázdný.");
        }
        byte[] raw = readBytes(file);
        if (raw.length > MAX_AUDIO_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Audio soubor je příliš velký");
        }
        String safeName = UploadPolicy.safeFilename(file.getOriginalFilename());
        String ext = UploadPolicy.extension(safeName).toLowerCase(Locale.ROOT);
        if (!ALLOWED_AUDIO_EXT.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Povolené formáty: MP3, M4A, MP4, AAC, OGG, WAV");
        }

        PodcastShowEntity show = requireShow(showId);
        String episodeId = IdGenerator.newId();
        String audioRel = mediaStorage.storeAudio(episodeId, ext, raw);

        String coverUrl = blankToNull(coverImageUrl);
        String coverRel = null;
        String coverContentType = null;
        if (coverImage != null && !coverImage.isEmpty()) {
            byte[] coverRaw = readBytes(coverImage);
            if (coverRaw.length > MAX_COVER_BYTES) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Obálka je příliš velká");
            }
            String coverName = UploadPolicy.safeFilename(coverImage.getOriginalFilename());
            String coverExt = UploadPolicy.extension(coverName).toLowerCase(Locale.ROOT);
            if (!ALLOWED_COVER_EXT.contains(coverExt)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Obálka: povolené formáty JPG, PNG, WEBP");
            }
            coverRel = mediaStorage.storeCover(episodeId, coverExt, coverRaw);
            coverContentType = coverImage.getContentType() != null ? coverImage.getContentType() : "image/jpeg";
            coverUrl = "/api/podcasts/episodes/" + episodeId + "/cover";
        }

        Instant now = Instant.now();
        PodcastEpisodeEntity entity = new PodcastEpisodeEntity();
        entity.setId(episodeId);
        entity.setUserId(user.getId());
        entity.setShowId(show.getId());
        entity.setTitle(cleanTitle(title, safeName));
        entity.setSummary(trim(summary, 800));
        entity.setFeedTitle(show.getTitle());
        entity.setAuthor(authorName(user));
        entity.setStoredAudioRel(audioRel);
        entity.setStoredCoverRel(coverRel);
        entity.setGridfsId("stored");
        entity.setAudioContentType(file.getContentType() != null ? file.getContentType() : "audio/mpeg");
        entity.setCoverContentType(coverContentType);
        entity.setOriginalFilename(safeName);
        entity.setCoverImageUrl(coverUrl);
        entity.setSource("upload");
        entity.setPublished(true);
        entity.setPublishedAt(now);
        episodeRepository.save(entity);

        return podcastService.toEpisodeResponse(entity, user, showTitlesMap());
    }

    @Transactional
    public PodcastEpisodeResponse createExternalEpisode(
            UserEntity user,
            String title,
            String externalUrl,
            String summary,
            String showId,
            String feedTitle,
            String audioUrl) {
        String ext = externalUrl != null ? externalUrl.trim() : "";
        if (ext.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vyplňte externí URL (Spotify, Apple Podcasts…).");
        }
        if (!(ext.startsWith("http://") || ext.startsWith("https://"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Externí URL musí začínat http:// nebo https://");
        }
        String direct = audioUrl != null ? audioUrl.trim() : "";
        if (!direct.isEmpty() && !DIRECT_AUDIO.matcher(direct).find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Volitelná audio URL musí být přímý soubor (MP3, M4A, MP4…).");
        }

        PodcastShowEntity show = requireShow(showId);
        Instant now = Instant.now();
        PodcastEpisodeEntity entity = new PodcastEpisodeEntity();
        entity.setId(IdGenerator.newId());
        entity.setUserId(user.getId());
        entity.setShowId(show.getId());
        entity.setTitle(cleanTitle(title, "Epizoda"));
        entity.setSummary(trim(summary, 800));
        entity.setExternalUrl(ext);
        entity.setAudioUrl(direct.isEmpty() ? null : direct);
        entity.setFeedTitle(!feedTitle.isBlank() ? trim(feedTitle, 500) : show.getTitle());
        entity.setAuthor(authorName(user));
        entity.setSource("external");
        entity.setPublished(true);
        entity.setPublishedAt(now);
        episodeRepository.save(entity);
        return podcastService.toEpisodeResponse(entity, user, showTitlesMap());
    }

    public byte[] readAudio(PodcastEpisodeEntity episode) {
        if (episode.getStoredAudioRel() == null || episode.getStoredAudioRel().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Audio soubor nenalezen");
        }
        return mediaStorage.read(episode.getStoredAudioRel());
    }

    public byte[] readCover(PodcastEpisodeEntity episode) {
        if (episode.getStoredCoverRel() == null || episode.getStoredCoverRel().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Obálka nenalezena");
        }
        return mediaStorage.read(episode.getStoredCoverRel());
    }

    public PodcastEpisodeEntity requirePublishedEpisode(String episodeId) {
        PodcastEpisodeEntity episode = episodeRepository
                .findById(episodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Epizoda nenalezena"));
        if (!episode.isPublished()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Epizoda nenalezena");
        }
        return episode;
    }

    private PodcastShowEntity requireShow(String showId) {
        String resolved = showId != null ? showId.trim() : "";
        if (resolved.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vyberte složku podcastu.");
        }
        return showRepository
                .findById(resolved)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Složka podcastu nenalezena"));
    }

    private Map<String, String> showTitlesMap() {
        Map<String, String> map = new HashMap<>();
        for (PodcastShowEntity show : showRepository.findAll()) {
            map.put(show.getId(), show.getTitle());
        }
        return map;
    }

    private static String authorName(UserEntity user) {
        if (user.getName() != null && !user.getName().isBlank()) {
            return user.getName().trim();
        }
        return user.getEmail();
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor se nepodařilo načíst.");
        }
    }

    private static String cleanTitle(String title, String fallback) {
        String clean = title != null ? title.trim() : "";
        if (clean.isEmpty()) {
            clean = fallback;
        }
        return clean.length() > 2000 ? clean.substring(0, 2000) : clean;
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
