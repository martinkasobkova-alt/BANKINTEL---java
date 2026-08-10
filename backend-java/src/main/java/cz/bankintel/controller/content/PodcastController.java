package cz.bankintel.controller.content;

import cz.bankintel.domain.dto.ContentDtos;
import cz.bankintel.domain.dto.ContentDtos.PodcastEpisodeResponse;
import cz.bankintel.domain.dto.ContentDtos.PodcastShowCreateRequest;
import cz.bankintel.domain.dto.ContentDtos.PodcastShowUpdateRequest;
import cz.bankintel.domain.entity.PodcastEpisodeEntity;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.service.content.PodcastEpisodeWriteService;
import cz.bankintel.service.content.PodcastService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/podcasts")
@RequiredArgsConstructor
public class PodcastController {

    private final PodcastService podcastService;
    private final PodcastEpisodeWriteService podcastEpisodeWriteService;
    private final CurrentUser currentUser;

    @GetMapping("/shows")
    public ContentDtos.PodcastShowListResponse listShows() {
        return podcastService.listShows();
    }

    @GetMapping("/shows/{showId}")
    public ContentDtos.PodcastShowResponse getShow(@PathVariable String showId) {
        return podcastService.getShow(showId);
    }

    @PostMapping("/shows")
    public ContentDtos.PodcastShowResponse createShow(@Valid @RequestBody PodcastShowCreateRequest body) {
        return podcastService.createShow(body, currentUser.requireAdmin());
    }

    @PatchMapping("/shows/{showId}")
    public ContentDtos.PodcastShowResponse updateShow(
            @PathVariable String showId, @RequestBody PodcastShowUpdateRequest body) {
        currentUser.requireAdmin();
        return podcastService.updateShow(showId, body);
    }

    @DeleteMapping("/shows/{showId}")
    public Map<String, Boolean> deleteShow(@PathVariable String showId) {
        currentUser.requireAdmin();
        podcastService.deleteShow(showId);
        return ContentDtos.okMap();
    }

    @GetMapping("/episodes")
    public ContentDtos.PodcastEpisodeListResponse listEpisodes(
            @RequestParam(defaultValue = "48") int limit,
            @RequestParam(name = "show_id", required = false) String showId) {
        return podcastService.listEpisodes(limit, showId, currentUser.optionalUserEntity());
    }

    @GetMapping("/episodes/{episodeId}/cover")
    public ResponseEntity<?> streamCover(@PathVariable String episodeId) {
        PodcastEpisodeEntity episode = podcastEpisodeWriteService.requirePublishedEpisode(episodeId);
        String coverUrl = episode.getCoverImageUrl() != null ? episode.getCoverImageUrl().trim() : "";
        if (coverUrl.startsWith("http://") || coverUrl.startsWith("https://")) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(coverUrl)).build();
        }
        byte[] raw = podcastEpisodeWriteService.readCover(episode);
        String mediaType = episode.getCoverContentType() != null ? episode.getCoverContentType() : "image/jpeg";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mediaType))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .body(raw);
    }

    @GetMapping("/episodes/{episodeId}/audio")
    public ResponseEntity<?> streamAudio(@PathVariable String episodeId) {
        PodcastEpisodeEntity episode = podcastEpisodeWriteService.requirePublishedEpisode(episodeId);
        String directUrl = episode.getAudioUrl() != null ? episode.getAudioUrl().trim() : "";
        boolean hasStored = episode.getStoredAudioRel() != null && !episode.getStoredAudioRel().isBlank();
        if (!hasStored && episode.getGridfsId() == null && !directUrl.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(directUrl)).build();
        }
        byte[] raw = podcastEpisodeWriteService.readAudio(episode);
        String mediaType = episode.getAudioContentType() != null ? episode.getAudioContentType() : "audio/mpeg";
        String filename = episode.getOriginalFilename() != null ? episode.getOriginalFilename() : "episode.mp3";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mediaType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .body(raw);
    }

    @PostMapping("/episodes/upload")
    public PodcastEpisodeResponse uploadEpisode(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "") String title,
            @RequestParam(defaultValue = "") String summary,
            @RequestParam(name = "show_id", defaultValue = "") String showId,
            @RequestParam(name = "feed_title", defaultValue = "") String feedTitle,
            @RequestPart(name = "cover_image", required = false) MultipartFile coverImage,
            @RequestParam(name = "cover_image_url", defaultValue = "") String coverImageUrl) {
        return podcastEpisodeWriteService.uploadEpisode(
                currentUser.requireUserEntity(),
                file,
                title,
                summary,
                showId,
                feedTitle,
                coverImage,
                coverImageUrl);
    }

    @PostMapping("/episodes/external")
    public PodcastEpisodeResponse createExternalEpisode(@RequestBody Map<String, Object> payload) {
        currentUser.requireAdmin();
        return podcastEpisodeWriteService.createExternalEpisode(
                currentUser.requireUserEntity(),
                str(payload.get("title")),
                str(payload.get("external_url")),
                str(payload.get("summary")),
                str(payload.get("show_id")),
                str(payload.get("feed_title")),
                str(payload.get("audio_url")));
    }

    @DeleteMapping("/episodes/{episodeId}")
    public Map<String, Boolean> deleteEpisode(@PathVariable String episodeId) {
        podcastService.deleteEpisode(episodeId, currentUser.requireUserEntity());
        return ContentDtos.okMap();
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value) : "";
    }
}
