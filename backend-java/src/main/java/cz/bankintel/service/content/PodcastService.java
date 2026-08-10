package cz.bankintel.service.content;

import cz.bankintel.domain.dto.ContentDtos;
import cz.bankintel.domain.dto.ContentDtos.PodcastEpisodeListResponse;
import cz.bankintel.domain.dto.ContentDtos.PodcastEpisodeResponse;
import cz.bankintel.domain.dto.ContentDtos.PodcastShowCreateRequest;
import cz.bankintel.domain.dto.ContentDtos.PodcastShowListResponse;
import cz.bankintel.domain.dto.ContentDtos.PodcastShowResponse;
import cz.bankintel.domain.dto.ContentDtos.PodcastShowUpdateRequest;
import cz.bankintel.domain.entity.PodcastEpisodeEntity;
import cz.bankintel.domain.entity.PodcastShowEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.PodcastEpisodeRepository;
import cz.bankintel.repository.PodcastShowRepository;
import cz.bankintel.util.IdGenerator;
import cz.bankintel.util.RoleUtils;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PodcastService {

    private static final Pattern DIRECT_AUDIO =
            Pattern.compile("\\.(mp3|m4a|mp4|aac|ogg|wav|mpeg)(\\?|$)", Pattern.CASE_INSENSITIVE);

    private final PodcastShowRepository showRepository;
    private final PodcastEpisodeRepository episodeRepository;

    public PodcastShowListResponse listShows() {
        Map<String, Long> counts = episodeCountsByShow();
        List<PodcastShowResponse> items = showRepository.findAllOrdered().stream()
                .map(show -> toShowResponse(show, counts.getOrDefault(show.getId(), 0L).intValue()))
                .toList();
        return new PodcastShowListResponse(items);
    }

    public PodcastShowResponse getShow(String showId) {
        PodcastShowEntity show = findShow(showId);
        int count = (int) episodeRepository.countPublishedByShowId(showId);
        return toShowResponse(show, count);
    }

    @Transactional
    public PodcastShowResponse createShow(PodcastShowCreateRequest body, UserEntity admin) {
        String title = body.title().trim();
        if (title.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vyplňte název složky podcastu.");
        }
        Instant now = Instant.now();
        PodcastShowEntity entity = new PodcastShowEntity();
        entity.setId(IdGenerator.newId());
        entity.setTitle(title.length() > 500 ? title.substring(0, 500) : title);
        entity.setDescription(trimDescription(body.description()));
        entity.setSortOrder(body.sortOrder());
        entity.setCreatedBy(admin.getId());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toShowResponse(showRepository.save(entity), 0);
    }

    @Transactional
    public PodcastShowResponse updateShow(String showId, PodcastShowUpdateRequest body) {
        PodcastShowEntity entity = findShow(showId);
        if (body.title() != null) {
            String clean = body.title().trim();
            if (clean.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Název složky nesmí být prázdný.");
            }
            entity.setTitle(clean.length() > 500 ? clean.substring(0, 500) : clean);
        }
        if (body.description() != null) {
            entity.setDescription(trimDescription(body.description()));
        }
        if (body.sortOrder() != null) {
            entity.setSortOrder(body.sortOrder());
        }
        int count = (int) episodeRepository.countPublishedByShowId(showId);
        return toShowResponse(showRepository.save(entity), count);
    }

    @Transactional
    public void deleteShow(String showId) {
        findShow(showId);
        for (PodcastEpisodeEntity episode : episodeRepository.findByShowId(showId)) {
            episode.setShowId(null);
            episode.setFeedTitle(null);
            episodeRepository.save(episode);
        }
        showRepository.deleteById(showId);
    }

    public PodcastEpisodeListResponse listEpisodes(int limit, String showId, UserEntity user) {
        int cappedLimit = Math.min(Math.max(limit, 1), 120);
        String showFilter = showId == null || showId.isBlank() ? null : showId.trim();
        Map<String, String> showTitles = showTitlesMap();
        List<PodcastEpisodeResponse> items = episodeRepository
                .findPublished(showFilter, PageRequest.of(0, cappedLimit))
                .stream()
                .map(ep -> toEpisodeResponse(ep, user, showTitles))
                .toList();
        return new PodcastEpisodeListResponse(items);
    }

    @Transactional
    public void deleteEpisode(String episodeId, UserEntity user) {
        PodcastEpisodeEntity episode = episodeRepository
                .findById(episodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Epizoda nenalezena"));
        if (!canManageEpisode(episode, user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Nemáte oprávnění smazat tuto epizodu.");
        }
        episodeRepository.delete(episode);
    }

    private PodcastShowEntity findShow(String showId) {
        return showRepository
                .findById(showId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Složka podcastu nenalezena"));
    }

    private Map<String, Long> episodeCountsByShow() {
        Map<String, Long> counts = new HashMap<>();
        for (PodcastShowEntity show : showRepository.findAll()) {
            counts.put(show.getId(), episodeRepository.countPublishedByShowId(show.getId()));
        }
        return counts;
    }

    private Map<String, String> showTitlesMap() {
        Map<String, String> map = new HashMap<>();
        for (PodcastShowEntity show : showRepository.findAll()) {
            map.put(show.getId(), show.getTitle());
        }
        return map;
    }

    private PodcastShowResponse toShowResponse(PodcastShowEntity entity, int episodeCount) {
        return new PodcastShowResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getSortOrder(),
                episodeCount,
                formatInstant(entity.getCreatedAt()),
                formatInstant(entity.getUpdatedAt()));
    }

    public PodcastEpisodeResponse toEpisodeResponse(
            PodcastEpisodeEntity entity, UserEntity user, Map<String, String> showTitles) {
        String episodeId = entity.getId();
        String externalUrl = blankToNull(entity.getExternalUrl());
        String directUrl = blankToNull(entity.getAudioUrl());
        String gridfsId = blankToNull(entity.getGridfsId());
        String storedAudioRel = blankToNull(entity.getStoredAudioRel());

        String audioUrl = null;
        String playMode = "external";

        if (storedAudioRel != null || gridfsId != null) {
            audioUrl = "/api/podcasts/episodes/" + episodeId + "/audio";
            playMode = "background";
        } else if (directUrl != null && isDirectAudioUrl(directUrl)) {
            audioUrl = directUrl;
            playMode = "background";
        } else if (externalUrl != null && isEmbedPlatformUrl(externalUrl)) {
            playMode = "embed";
        } else if (externalUrl != null) {
            playMode = "external";
        }

        Instant pub = entity.getPublishedAt() != null ? entity.getPublishedAt() : entity.getCreatedAt();
        String showId = blankToNull(entity.getShowId());
        String showTitle = showId != null ? showTitles.getOrDefault(showId, "") : "";
        String feedTitle = !showTitle.isBlank()
                ? showTitle
                : blankToNull(entity.getFeedTitle());
        if (feedTitle == null && "upload".equals(entity.getSource())) {
            feedTitle = "Nahrávka";
        }
        String author = blankToNull(entity.getAuthor());
        if (author == null) {
            author = feedTitle;
        }

        String pageUrl = externalUrl;
        if (pageUrl == null) {
            pageUrl = directUrl;
        }
        if (pageUrl == null && audioUrl != null && audioUrl.startsWith("http")) {
            pageUrl = audioUrl;
        }

        return new PodcastEpisodeResponse(
                episodeId,
                entity.getTitle(),
                entity.getSummary(),
                audioUrl,
                externalUrl,
                pageUrl,
                showId,
                !showTitle.isBlank() ? showTitle : feedTitle,
                feedTitle,
                author,
                formatInstant(pub),
                entity.getSource(),
                playMode,
                canManageEpisode(entity, user),
                blankToNull(entity.getCoverImageUrl()));
    }

    private boolean canManageEpisode(PodcastEpisodeEntity entity, UserEntity user) {
        if (user == null) {
            return false;
        }
        if (RoleUtils.isAdminRole(user.getRole())) {
            return true;
        }
        return user.getId().equals(entity.getUserId());
    }

    private static boolean isDirectAudioUrl(String url) {
        return DIRECT_AUDIO.matcher(url).find();
    }

    private static boolean isEmbedPlatformUrl(String url) {
        String lower = url.toLowerCase();
        return lower.contains("spotify.com") || lower.contains("podcasts.apple.com");
    }

    private static String trimDescription(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > 2000 ? trimmed.substring(0, 2000) : trimmed;
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
