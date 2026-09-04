package cz.bankintel.controller.magazine;

import cz.bankintel.domain.dto.MagazineDtos.AiChatResponse;
import cz.bankintel.domain.dto.MagazineDtos.MagazineAiChatRequest;
import cz.bankintel.domain.dto.MagazineDtos.MagazineAiSearchRequest;
import cz.bankintel.domain.dto.MagazineDtos.MagazineCreateRequest;
import cz.bankintel.domain.dto.MagazineDtos.MagazineIssueDetailResponse;
import cz.bankintel.domain.dto.MagazineDtos.MagazineIssueResponse;
import cz.bankintel.domain.dto.MagazineDtos.MagazineIssueUpdateRequest;
import cz.bankintel.domain.dto.MagazineDtos.MagazinePdfLinkCreateRequest;
import cz.bankintel.domain.dto.MagazineDtos.MagazinePdfLinkResponse;
import cz.bankintel.domain.dto.MagazineDtos.MagazineResponse;
import cz.bankintel.domain.dto.MagazineDtos.SearchResponse;
import cz.bankintel.security.AdminAccess;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.service.access.FeatureAccessService;
import cz.bankintel.service.magazine.MagazineAiService;
import cz.bankintel.service.magazine.MagazineService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/magazines")
@RequiredArgsConstructor
public class MagazinesController {

    private final MagazineService magazineService;
    private final MagazineAiService magazineAiService;
    private final AdminAccess adminAccess;
    private final FeatureAccessService featureAccessService;
    private final CurrentUser currentUser;

    /**
     * Obsah časopisu je předplatitelský.
     *
     * SecurityConfig pouští všechna /api/**, takže bez tohohle checku si celé PDF čísla stáhl
     * kdokoli i bez přihlášení — a předplatné časopisu je přitom jediná placená funkce.
     */
    private void requireMagazineAccess() {
        featureAccessService.requireFeature(currentUser.optionalUserEntity(), "magazine_archive");
    }

    @GetMapping({"", "/"})
    public List<MagazineResponse> listMagazines() {
        requireMagazineAccess();
        return magazineService.listMagazines();
    }

    @PostMapping({"", "/"})
    public MagazineResponse createMagazine(@Valid @RequestBody MagazineCreateRequest body) {
        adminAccess.requireAdmin();
        return magazineService.createMagazine(body);
    }

    @PatchMapping("/{magazineId}")
    public MagazineResponse patchMagazine(
            @PathVariable String magazineId, @Valid @RequestBody MagazineCreateRequest body) {
        adminAccess.requireAdmin();
        return magazineService.patchMagazine(magazineId, body);
    }

    @GetMapping("/{magazineId}/issues")
    public List<MagazineIssueResponse> listIssues(@PathVariable String magazineId) {
        requireMagazineAccess();
        return magazineService.listIssues(magazineId);
    }

    @PostMapping("/{magazineId}/issues/upload")
    public MagazineIssueResponse uploadIssue(
            @PathVariable String magazineId,
            @RequestParam("issue_label") String issueLabel,
            @RequestParam(value = "title", defaultValue = "") String title,
            @RequestParam(value = "published_at", defaultValue = "") String publishedAt,
            @RequestParam(value = "description", defaultValue = "") String description,
            @RequestParam(value = "cover_image_url", required = false) String coverImageUrl,
            @RequestParam("file") MultipartFile file) {
        adminAccess.requireAdmin();
        return magazineService.uploadIssue(
                magazineId, issueLabel, title, publishedAt, description, coverImageUrl, file);
    }

    @GetMapping("/{magazineId}/search")
    public SearchResponse searchMagazine(
            @PathVariable String magazineId,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "40") int limit) {
        requireMagazineAccess();
        return magazineService.searchMagazine(magazineId, q, limit);
    }

    @GetMapping("/issues/{issueId}")
    public MagazineIssueDetailResponse getIssue(@PathVariable String issueId) {
        requireMagazineAccess();
        return magazineService.getIssue(issueId);
    }

    @PatchMapping("/issues/{issueId}")
    public MagazineIssueResponse updateIssue(
            @PathVariable String issueId, @Valid @RequestBody MagazineIssueUpdateRequest body) {
        adminAccess.requireAdmin();
        return magazineService.updateIssue(issueId, body);
    }

    @PostMapping("/issues/{issueId}/reindex")
    public Map<String, Object> reindexIssue(@PathVariable String issueId) {
        adminAccess.requireAdmin();
        return magazineService.reindexIssue(issueId);
    }

    @PostMapping("/issues/{issueId}/file")
    public MagazineIssueResponse replaceIssueFile(
            @PathVariable String issueId, @RequestParam("file") MultipartFile file) {
        adminAccess.requireAdmin();
        return magazineService.replaceIssueFile(issueId, file);
    }

    @GetMapping("/issues/{issueId}/file")
    public ResponseEntity<ByteArrayResource> getIssueFile(
            @PathVariable String issueId,
            @RequestParam(value = "reader_page", required = false) Integer readerPage) {
        requireMagazineAccess();
        // Celé číslo jedním požadavkem dostane jen administrace. Předplatitel čte po stránkách
        // (`reader_page`), takže mu aplikace nikdy nepošle celý soubor ke stažení.
        if (readerPage == null) {
            adminAccess.requireAdmin();
        }
        byte[] raw = magazineService.readIssuePdf(issueId, readerPage);
        String filename = magazineService.issuePdfFilename(issueId, readerPage);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"");
        headers.setCacheControl(readerPage != null ? "private, max-age=300" : "no-store, max-age=0");
        if (readerPage == null) {
            headers.setPragma("no-cache");
        }
        headers.setAccessControlAllowOrigin("*");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_PDF)
                .body(new ByteArrayResource(raw));
    }

    @GetMapping("/issues/{issueId}/page-preview")
    public ResponseEntity<ByteArrayResource> getIssuePagePreview(
            @PathVariable String issueId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "1200") int width) {
        int safePage = Math.max(1, Math.min(page, 9999));
        int safeWidth = Math.max(320, Math.min(width, 2400));
        requireMagazineAccess();
        byte[] png = magazineService.renderPagePreview(issueId, safePage, safeWidth);
        String filename = magazineService.issuePdfFilename(issueId, safePage).replace(".pdf", ".png");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"");
        headers.setCacheControl("private, max-age=300");
        headers.setAccessControlAllowOrigin("*");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.IMAGE_PNG)
                .body(new ByteArrayResource(png));
    }

    @GetMapping("/issues/{issueId}/search")
    public SearchResponse searchIssue(
            @PathVariable String issueId,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "20") int limit) {
        requireMagazineAccess();
        return magazineService.searchIssue(issueId, q, limit);
    }

    @GetMapping("/issues/{issueId}/links")
    public cz.bankintel.domain.dto.MagazineDtos.IssueLinksResponse listLinks(
            @PathVariable String issueId, @RequestParam(required = false) Integer page) {
        requireMagazineAccess();
        return magazineService.listLinks(issueId, page);
    }

    @PostMapping("/issues/{issueId}/links")
    public MagazinePdfLinkResponse createLink(
            @PathVariable String issueId, @Valid @RequestBody MagazinePdfLinkCreateRequest body) {
        adminAccess.requireAdmin();
        return magazineService.createLink(issueId, body);
    }

    @DeleteMapping("/issues/{issueId}/links/{linkId}")
    public Map<String, Object> deleteLink(@PathVariable String issueId, @PathVariable String linkId) {
        adminAccess.requireAdmin();
        return magazineService.deleteLink(issueId, linkId);
    }

    @PostMapping("/ai/search")
    public SearchResponse aiSearch(@Valid @RequestBody MagazineAiSearchRequest body) {
        requireMagazineAccess();
        return magazineAiService.aiSearch(body);
    }

    @PostMapping("/ai/chat")
    public AiChatResponse aiChat(@Valid @RequestBody MagazineAiChatRequest body) {
        requireMagazineAccess();
        return magazineAiService.aiChat(body);
    }
}
