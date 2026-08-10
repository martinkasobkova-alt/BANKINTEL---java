package cz.bankintel.service.magazine;

import cz.bankintel.domain.dto.MagazineDtos.IssueLinksResponse;
import cz.bankintel.domain.dto.MagazineDtos.MagazineCreateRequest;
import cz.bankintel.domain.dto.MagazineDtos.MagazineIssueDetailResponse;
import cz.bankintel.domain.dto.MagazineDtos.MagazineIssueResponse;
import cz.bankintel.domain.dto.MagazineDtos.MagazineIssueUpdateRequest;
import cz.bankintel.domain.dto.MagazineDtos.MagazinePdfLinkCreateRequest;
import cz.bankintel.domain.dto.MagazineDtos.MagazinePdfLinkResponse;
import cz.bankintel.domain.dto.MagazineDtos.MagazineResponse;
import cz.bankintel.domain.dto.MagazineDtos.SearchHit;
import cz.bankintel.domain.dto.MagazineDtos.SearchResponse;
import cz.bankintel.domain.entity.MagazineEntity;
import cz.bankintel.domain.entity.MagazineIssueEntity;
import cz.bankintel.domain.entity.MagazinePdfLinkEntity;
import cz.bankintel.domain.entity.StoredFileEntity;
import cz.bankintel.repository.MagazineIssueRepository;
import cz.bankintel.repository.MagazinePdfLinkRepository;
import cz.bankintel.repository.MagazineRepository;
import cz.bankintel.repository.StoredFileRepository;
import cz.bankintel.storage.MagazineStorageService;
import cz.bankintel.util.IdGenerator;
import cz.bankintel.util.SlugUtils;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class MagazineService {

    private static final long MAX_PDF_BYTES = 80L * 1024 * 1024;

    private final MagazineRepository magazineRepository;
    private final MagazineIssueRepository issueRepository;
    private final MagazinePdfLinkRepository linkRepository;
    private final StoredFileRepository storedFileRepository;
    private final MagazineStorageService storageService;
    private final MagazineIngestService ingestService;
    private final MagazineSearchService searchService;
    private final MagazinePdfTextExtractor pdfTextExtractor;

    @Transactional(readOnly = true)
    public List<MagazineResponse> listMagazines() {
        return magazineRepository.findAllOrdered().stream()
                .map(this::toMagazineResponse)
                .toList();
    }

    @Transactional
    public MagazineResponse createMagazine(MagazineCreateRequest body) {
        Instant now = Instant.now();
        MagazineEntity entity = new MagazineEntity();
        entity.setId(IdGenerator.newId());
        entity.setTitle(body.title().trim());
        entity.setSlug(resolveUniqueSlug(body.slug() != null ? body.slug() : body.title(), null));
        entity.setDescription(trimOrEmpty(body.description()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toMagazineResponse(magazineRepository.save(entity));
    }

    @Transactional
    public MagazineResponse patchMagazine(String magazineId, MagazineCreateRequest body) {
        MagazineEntity entity = requireMagazine(magazineId);
        entity.setTitle(body.title().trim());
        entity.setSlug(resolveUniqueSlug(body.slug() != null ? body.slug() : body.title(), magazineId));
        entity.setDescription(trimOrEmpty(body.description()));
        entity.setUpdatedAt(Instant.now());
        return toMagazineResponse(magazineRepository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<MagazineIssueResponse> listIssues(String magazineId) {
        requireMagazine(magazineId);
        return issueRepository.findByMagazineIdOrdered(magazineId).stream()
                .map(this::toIssueResponse)
                .toList();
    }

    @Transactional
    public MagazineIssueResponse uploadIssue(
            String magazineId,
            String issueLabel,
            String title,
            String publishedAt,
            String description,
            String coverImageUrl,
            MultipartFile file) {
        requireMagazine(magazineId);
        if (issueLabel == null || issueLabel.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vyplňte označení čísla.");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!filename.toLowerCase().endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Podporován je pouze PDF soubor.");
        }
        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor se nepodařilo načíst.", e);
        }
        if (raw.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor je prázdný.");
        }
        if (raw.length > MAX_PDF_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Soubor je příliš velký (max 80 MB).");
        }
        String issueId = IdGenerator.newId();
        StoredFileEntity stored = storageService.storePdf(magazineId, issueId, filename, raw);
        Instant now = Instant.now();
        MagazineIssueEntity entity = new MagazineIssueEntity();
        entity.setId(issueId);
        entity.setMagazineId(magazineId);
        entity.setIssueLabel(issueLabel.trim());
        entity.setTitle(trimOrEmpty(title));
        entity.setPublishedAt(trimOrEmpty(publishedAt));
        entity.setDescription(trimOrEmpty(description));
        entity.setCoverImageUrl(normalizeCoverUrl(coverImageUrl));
        entity.setOriginalName(stored.getOriginalName());
        entity.setStoredFileId(stored.getId());
        entity.setSizeBytes(raw.length);
        entity.setIngestStatus("pending");
        entity.setIngestError("");
        entity.setPageCount(0);
        entity.setChunkCount(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        MagazineIssueResponse response = toIssueResponse(issueRepository.save(entity));
        ingestService.ingestIssueAsync(issueId);
        return response;
    }

    /**
     * Replaces the PDF file of an EXISTING issue (e.g. an issue that was created without a file, or
     * to swap in a corrected PDF). Mirrors {@link #uploadIssue}'s validation but keeps the same
     * issue id/metadata. {@code storePdf} writes to the deterministic path {@code
     * {magazineId}/{issueId}.pdf}, so the previous file is physically overwritten; we only clean up
     * the now-orphaned {@code stored_files} row (never the physical file). Re-ingests afterwards.
     */
    @Transactional
    public MagazineIssueResponse replaceIssueFile(String issueId, MultipartFile file) {
        MagazineIssueEntity issue = requireIssue(issueId);
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!filename.toLowerCase().endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Podporován je pouze PDF soubor.");
        }
        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor se nepodařilo načíst.", e);
        }
        if (raw.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Soubor je prázdný.");
        }
        if (raw.length > MAX_PDF_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Soubor je příliš velký (max 80 MB).");
        }
        String previousStoredFileId = issue.getStoredFileId();
        StoredFileEntity stored = storageService.storePdf(issue.getMagazineId(), issueId, filename, raw);
        issue.setOriginalName(stored.getOriginalName());
        issue.setStoredFileId(stored.getId());
        issue.setSizeBytes(raw.length);
        issue.setIngestStatus("pending");
        issue.setIngestError("");
        issue.setPageCount(0);
        issue.setChunkCount(0);
        MagazineIssueResponse response = toIssueResponse(issueRepository.save(issue));
        if (previousStoredFileId != null
                && !previousStoredFileId.isBlank()
                && !previousStoredFileId.equals(stored.getId())) {
            storedFileRepository.findById(previousStoredFileId).ifPresent(storedFileRepository::delete);
        }
        ingestService.ingestIssueAsync(issueId);
        return response;
    }

    @Transactional
    public MagazineIssueResponse updateIssue(String issueId, MagazineIssueUpdateRequest body) {
        MagazineIssueEntity entity = requireIssue(issueId);
        if (body.title() != null) {
            entity.setTitle(trimOrEmpty(body.title()));
        }
        if (body.publishedAt() != null) {
            entity.setPublishedAt(trimOrEmpty(body.publishedAt()));
        }
        if (body.description() != null) {
            entity.setDescription(trimOrEmpty(body.description()));
        }
        if (body.coverImageUrl() != null) {
            entity.setCoverImageUrl(normalizeCoverUrl(body.coverImageUrl()));
        }
        return toIssueResponse(issueRepository.save(entity));
    }

    @Transactional
    public Map<String, Object> reindexIssue(String issueId) {
        requireIssue(issueId);
        MagazineIssueEntity entity = requireIssue(issueId);
        entity.setIngestStatus("pending");
        entity.setIngestError("");
        issueRepository.save(entity);
        ingestService.ingestIssueAsync(issueId);
        return Map.of("ok", true, "issue_id", issueId, "status", "pending");
    }

    @Transactional(readOnly = true)
    public MagazineIssueDetailResponse getIssue(String issueId) {
        MagazineIssueEntity issue = requireIssue(issueId);
        MagazineEntity magazine = magazineRepository.findById(issue.getMagazineId()).orElse(null);
        Map<String, String> mag = new LinkedHashMap<>();
        mag.put("id", magazine == null ? "" : magazine.getId());
        mag.put("title", magazine == null ? "" : magazine.getTitle());
        mag.put("slug", magazine == null ? "" : magazine.getSlug());
        MagazineIssueResponse base = toIssueResponse(issue);
        return new MagazineIssueDetailResponse(
                base.id(),
                base.magazineId(),
                base.issueLabel(),
                base.title(),
                base.description(),
                base.coverImageUrl(),
                base.publishedAt(),
                base.createdAt(),
                base.ingestStatus(),
                base.ingestError(),
                base.pageCount(),
                base.chunkCount(),
                base.size(),
                mag);
    }

    @Transactional(readOnly = true)
    public byte[] readIssuePdf(String issueId, Integer readerPage) {
        MagazineIssueEntity issue = requireIssue(issueId);
        StoredFileEntity file = requireStoredFile(issue.getStoredFileId());
        byte[] raw = storageService.readPdf(file);
        if (readerPage != null && readerPage > 0) {
            return pdfTextExtractor.extractSinglePagePdf(raw, readerPage);
        }
        return raw;
    }

    @Transactional(readOnly = true)
    public String issuePdfFilename(String issueId, Integer readerPage) {
        MagazineIssueEntity issue = requireIssue(issueId);
        String filename = issue.getOriginalName() == null ? "issue.pdf" : issue.getOriginalName();
        if (readerPage != null && readerPage > 0) {
            int dot = filename.lastIndexOf('.');
            String stem = dot > 0 ? filename.substring(0, dot) : filename;
            return stem + "-p" + readerPage + ".pdf";
        }
        return filename;
    }

    @Transactional(readOnly = true)
    public byte[] renderPagePreview(String issueId, int page, int width) {
        MagazineIssueEntity issue = requireIssue(issueId);
        StoredFileEntity file = requireStoredFile(issue.getStoredFileId());
        byte[] raw = storageService.readPdf(file);
        try {
            return pdfTextExtractor.renderPagePreviewPng(raw, page, width);
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Náhled stránky teď není dostupný. Propojení přes text v PDF funguje i bez označování myší.");
        }
    }

    @Transactional(readOnly = true)
    public SearchResponse searchMagazine(String magazineId, String query, int limit) {
        requireMagazine(magazineId);
        List<SearchHit> hits = searchService.search(query, magazineId, null, limit);
        return new SearchResponse(query, magazineId, hits, null);
    }

    @Transactional(readOnly = true)
    public SearchResponse searchIssue(String issueId, String query, int limit) {
        requireIssue(issueId);
        List<SearchHit> hits = searchService.search(query, null, issueId, limit);
        return new SearchResponse(query, null, hits, null);
    }

    @Transactional(readOnly = true)
    public IssueLinksResponse listLinks(String issueId, Integer page) {
        requireIssue(issueId);
        List<MagazinePdfLinkResponse> links = linkRepository.findByIssue(issueId, page).stream()
                .map(this::toLinkResponse)
                .toList();
        return new IssueLinksResponse(issueId, page, links);
    }

    @Transactional
    public MagazinePdfLinkResponse createLink(String issueId, MagazinePdfLinkCreateRequest body) {
        MagazineIssueEntity issue = requireIssue(issueId);
        validateLinkRequest(body);
        Instant now = Instant.now();
        String linkId = IdGenerator.newId();
        String targetKind = normalizeTargetKind(body.targetKind());
        String targetTitle;
        String sourceType;
        String setId;
        String linkUrl;
        if ("chart".equals(targetKind)) {
            targetTitle = trimOrEmpty(body.targetTitle());
            sourceType = trimOrEmpty(body.sourceType());
            setId = trimOrEmpty(body.setId());
            linkUrl = trimOrEmpty(body.linkUrl());
        } else {
            linkUrl = trimOrEmpty(body.linkUrl());
            targetTitle = firstNonBlank(body.targetTitle(), body.label());
            if (targetTitle.length() > 240) {
                targetTitle = targetTitle.substring(0, 240);
            }
            sourceType = "external_" + targetKind;
            setId = "external:" + targetKind + ":" + linkId.substring(0, Math.min(10, linkId.length()));
        }
        Bbox bbox = parseBbox(body.bbox());
        MagazinePdfLinkEntity entity = new MagazinePdfLinkEntity();
        entity.setId(linkId);
        entity.setIssueId(issueId);
        entity.setMagazineId(issue.getMagazineId());
        entity.setPage(body.page());
        entity.setLabel(body.label().trim());
        entity.setAnchorText(trimOrEmpty(body.anchorText()));
        entity.setLinkKind(body.linkKind() == null || body.linkKind().isBlank() ? "text" : body.linkKind().trim());
        if (bbox != null) {
            entity.setBboxX0(bbox.x0());
            entity.setBboxY0(bbox.y0());
            entity.setBboxX1(bbox.x1());
            entity.setBboxY1(bbox.y1());
        }
        entity.setTargetKind(targetKind);
        entity.setTargetTitle(targetTitle);
        entity.setSourceType(sourceType);
        entity.setSetId(setId);
        entity.setLinkUrl(linkUrl);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toLinkResponse(linkRepository.save(entity));
    }

    @Transactional
    public Map<String, Object> deleteLink(String issueId, String linkId) {
        requireIssue(issueId);
        MagazinePdfLinkEntity link = linkRepository
                .findByIdAndIssueId(linkId, issueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Propojení nebylo nalezeno."));
        linkRepository.delete(link);
        return Map.of("ok", true, "id", linkId);
    }

    private MagazineEntity requireMagazine(String magazineId) {
        return magazineRepository
                .findById(magazineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Časopis nebyl nalezen."));
    }

    private MagazineIssueEntity requireIssue(String issueId) {
        return issueRepository
                .findById(issueId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Číslo nebylo nalezeno."));
    }

    private StoredFileEntity requireStoredFile(String storedFileId) {
        if (storedFileId == null || storedFileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PDF soubor není dostupný.");
        }
        return storedFileRepository
                .findById(storedFileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PDF soubor není dostupný."));
    }

    private MagazineResponse toMagazineResponse(MagazineEntity entity) {
        int issueCount = (int) issueRepository.countByMagazineId(entity.getId());
        int readyCount = (int) issueRepository.countReadyByMagazineId(entity.getId());
        return new MagazineResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getSlug(),
                entity.getDescription(),
                formatInstant(entity.getCreatedAt()),
                formatInstant(entity.getUpdatedAt()),
                issueCount,
                readyCount);
    }

    private MagazineIssueResponse toIssueResponse(MagazineIssueEntity entity) {
        return new MagazineIssueResponse(
                entity.getId(),
                entity.getMagazineId(),
                entity.getIssueLabel(),
                entity.getTitle(),
                entity.getDescription(),
                blankToNull(entity.getCoverImageUrl()),
                blankToNull(entity.getPublishedAt()),
                formatInstant(entity.getCreatedAt()),
                entity.getIngestStatus(),
                entity.getIngestError(),
                entity.getPageCount(),
                entity.getChunkCount(),
                (int) entity.getSizeBytes());
    }

    private MagazinePdfLinkResponse toLinkResponse(MagazinePdfLinkEntity entity) {
        return new MagazinePdfLinkResponse(
                entity.getId(),
                entity.getIssueId(),
                entity.getMagazineId(),
                entity.getPage(),
                entity.getLabel(),
                blankToNull(entity.getAnchorText()),
                entity.getLinkKind(),
                toBboxList(entity),
                entity.getTargetKind(),
                blankToNull(entity.getTargetTitle()),
                blankToNull(entity.getSourceType()),
                blankToNull(entity.getSetId()),
                blankToNull(entity.getLinkUrl()),
                formatInstant(entity.getCreatedAt()),
                formatInstant(entity.getUpdatedAt()));
    }

    private String resolveUniqueSlug(String base, String excludeId) {
        String slug = SlugUtils.slugify(base, "casopis");
        if (excludeId == null) {
            if (!magazineRepository.findBySlug(slug).isPresent()) {
                return slug;
            }
        } else if (!magazineRepository.existsBySlugAndIdNot(slug, excludeId)) {
            return slug;
        }
        for (int i = 2; i < 2000; i++) {
            String candidate = slug + "-" + i;
            boolean free = excludeId == null
                    ? magazineRepository.findBySlug(candidate).isEmpty()
                    : !magazineRepository.existsBySlugAndIdNot(candidate, excludeId);
            if (free) {
                return candidate;
            }
        }
        return slug + "-" + IdGenerator.newId().substring(0, 8);
    }

    private static void validateLinkRequest(MagazinePdfLinkCreateRequest body) {
        String targetKind = normalizeTargetKind(body.targetKind());
        if ("chart".equals(targetKind)) {
            for (Map.Entry<String, String> field :
                    Map.of(
                                    "target_title", trimOrEmpty(body.targetTitle()),
                                    "source_type", trimOrEmpty(body.sourceType()),
                                    "set_id", trimOrEmpty(body.setId()),
                                    "link_url", trimOrEmpty(body.linkUrl()))
                            .entrySet()) {
                if (field.getValue().isEmpty()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Pole " + field.getKey() + " je povinné pro propojení s grafem.");
                }
            }
        } else {
            String url = trimOrEmpty(body.linkUrl());
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Pro externí obsah zadejte platnou URL (http/https).");
            }
        }
        parseBbox(body.bbox());
    }

    private static Bbox parseBbox(List<Double> bbox) {
        if (bbox == null || bbox.isEmpty()) {
            return null;
        }
        if (bbox.size() != 4) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bbox musí mít 4 hodnoty [x0, y0, x1, y1]");
        }
        double x0 = bbox.get(0);
        double y0 = bbox.get(1);
        double x1 = bbox.get(2);
        double y1 = bbox.get(3);
        for (double v : new double[] {x0, y0, x1, y1}) {
            if (v < 0 || v > 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bbox souřadnice musí být v rozsahu 0–1");
            }
        }
        if (x1 <= x0 || y1 <= y0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bbox musí mít kladnou plochu");
        }
        return new Bbox(x0, y0, x1, y1);
    }

    private static List<Double> toBboxList(MagazinePdfLinkEntity entity) {
        if (entity.getBboxX0() == null
                || entity.getBboxY0() == null
                || entity.getBboxX1() == null
                || entity.getBboxY1() == null) {
            return null;
        }
        return List.of(entity.getBboxX0(), entity.getBboxY0(), entity.getBboxX1(), entity.getBboxY1());
    }

    private static String normalizeTargetKind(String value) {
        String k = value == null ? "chart" : value.trim().toLowerCase();
        return switch (k) {
            case "chart", "web", "video", "document", "podcast" -> k;
            default -> "chart";
        };
    }

    private static String normalizeCoverUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "URL náhledového obrázku musí začínat http:// nebo https://");
        }
        return trimmed.length() > 2048 ? trimmed.substring(0, 2048) : trimmed;
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private record Bbox(double x0, double y0, double x1, double y1) {}
}
