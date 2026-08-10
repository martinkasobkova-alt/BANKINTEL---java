package cz.bankintel.controller.explore;

import cz.bankintel.explore.ExploreAuxiliaryService;
import cz.bankintel.explore.manager.ManagerAnalysisPlanService;
import cz.bankintel.explore.ExploreDtos.ExploreSectorRequest;
import cz.bankintel.explore.ExploreDtos.ExploreSummarizeRequest;
import cz.bankintel.explore.ExploreFollowupService;
import cz.bankintel.explore.ExploreGeoCatalog;
import cz.bankintel.explore.ExploreSectorService;
import cz.bankintel.explore.ExploreStreamService;
import cz.bankintel.explore.ExploreTraceEnvelope;
import cz.bankintel.explore.ExploreSummarizeService;
import cz.bankintel.explore.ExploreSummarizeService.SummarizeJobNotFoundException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Port of {@code backend/routes/explore_routes.py} + {@code backend/services/explore_manager.py}.
 */
@RestController
@RequestMapping("/api/explore")
@RequiredArgsConstructor
public class ExploreController {

    private final ExploreGeoCatalog geoCatalog;
    private final ExploreSectorService sectorService;
    private final ExploreStreamService streamService;
    private final ExploreSummarizeService summarizeService;
    private final ExploreAuxiliaryService exploreAuxiliaryService;
    private final ExploreFollowupService exploreFollowupService;
    private final ManagerAnalysisPlanService managerAnalysisPlanService;

    @GetMapping("/geo-options")
    public Map<String, Object> geoOptions() {
        return geoCatalog.geoOptions();
    }

    @PostMapping("/sector")
    public Map<String, Object> sector(@RequestBody ExploreSectorRequest request) {
        return ExploreTraceEnvelope.forRest(sectorService.analyzeSector(request));
    }

    @GetMapping(value = "/sector/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sectorStream(
            @RequestParam(defaultValue = "") String sector,
            @RequestParam(required = false) String question,
            @RequestParam(required = false) String country,
            @RequestParam(name = "geo_mode", required = false) String geoMode,
            @RequestParam(required = false) String continent,
            @RequestParam(name = "related_segments", required = false) String relatedSegments,
            @RequestParam(name = "related_segment_ranking", required = false) String relatedSegmentRanking,
            @RequestParam(name = "analysis_mode", defaultValue = "sector") String analysisMode,
            @RequestParam(name = "request_id", required = false) String requestId) {
        return streamService.streamSector(
                sector,
                question,
                country,
                geoMode,
                continent,
                relatedSegments,
                relatedSegmentRanking,
                analysisMode,
                requestId);
    }

    @PostMapping("/summarize")
    public Map<String, Object> summarize(@RequestBody ExploreSummarizeRequest request) {
        return summarizeService.startSummarize(request);
    }

    @GetMapping("/summarize/status/{jobId}")
    public Map<String, Object> summarizeStatus(@PathVariable String jobId) {
        try {
            return summarizeService.summarizeStatus(jobId);
        } catch (SummarizeJobNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    @GetMapping("/summarize/detail/{jobId}")
    public Map<String, Object> summarizeDetail(@PathVariable String jobId) {
        try {
            return summarizeService.summarizeDetail(jobId);
        } catch (SummarizeJobNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    @PostMapping({"/related-suggestions", "/sector/related-suggestions"})
    public Map<String, Object> relatedSuggestions(@RequestBody Map<String, Object> body) {
        return exploreAuxiliaryService.relatedSuggestions(
                str(body.get("sector")),
                str(body.get("country")),
                str(body.get("geo_mode")),
                str(body.get("continent")),
                str(body.get("related_segments")));
    }

    @PostMapping({"/country-suggestions", "/geo-country-suggestions"})
    public Map<String, Object> countrySuggestions(@RequestBody Map<String, Object> body) {
        return exploreAuxiliaryService.countrySuggestions(
                str(body.get("sector")), str(body.get("country")), str(body.get("geo_mode")), str(body.get("continent")));
    }

    @PostMapping("/query-understanding")
    public Map<String, Object> queryUnderstanding(@RequestBody Map<String, Object> body) {
        return exploreAuxiliaryService.queryUnderstanding(str(body.get("query")));
    }

    @PostMapping("/sector/refine")
    public Map<String, Object> sectorRefine(@RequestBody Map<String, Object> body) {
        return exploreAuxiliaryService.refineSector(body);
    }

    @GetMapping("/sector/preset-preview")
    public Map<String, Object> sectorPresetPreview(
            @RequestParam(required = false) String sector,
            @RequestParam(required = false) String question,
            @RequestParam(required = false) String country,
            @RequestParam(name = "geo_mode", required = false) String geoMode,
            @RequestParam(required = false) String continent) {
        return sectorService.buildPresetPreview(sector, question, country, geoMode, continent);
    }

    @PostMapping("/summarize/followup")
    public Map<String, Object> summarizeFollowup(@RequestBody Map<String, Object> body) {
        return exploreFollowupService.followup(body);
    }

    /**
     * ETAPA 7 decision (keep as public contract, mark clearly as unsupported - option C, NOT
     * deleted): repo-wide search of the BankIntel-v2 frontend found zero callers of this route,
     * and both MANAGER_EXPLORER_AUDIT.md and MANAGER_EXPLORER_AUDIT_V2.md independently reached
     * the same conclusion. {@link ManagerAnalysisPlanService} itself is real, non-trivial logic
     * (not a stub), and the workspace also contains a same-named route in a separate, unrelated
     * Python experiment repo (Bankoapp-ai-latency-experiment) that this endpoint may have been
     * ported from - there is no way from inside this repo to rule out an external caller (mobile
     * app, other integration) outside the workspace. Given that ambiguity, deleting a working
     * 440-line service is a needlessly risky, hard-to-reverse action compared to leaving it
     * intact and clearly documented as currently unwired.
     */
    @PostMapping("/manager/analysis-plan")
    public Map<String, Object> managerAnalysisPlan(@RequestBody Map<String, Object> body) {
        List<String> relatedOverrides = null;
        Object relatedRaw = body.get("related_segments");
        if (relatedRaw != null) {
            relatedOverrides = java.util.Arrays.stream(String.valueOf(relatedRaw).split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        @SuppressWarnings("unchecked")
        List<String> uploadIds = body.get("upload_ids") instanceof List<?> list
                ? (List<String>) list
                : List.of();
        return managerAnalysisPlanService.buildManagerAnalysisPlan(
                str(body.get("analysis_mode")),
                str(body.get("question")),
                str(body.get("sector")),
                str(body.get("country")),
                str(body.get("geo_mode")),
                str(body.get("continent")),
                relatedOverrides,
                uploadIds,
                str(body.get("company_id")));
    }

    private static String str(Object value) {
        return value != null ? String.valueOf(value).strip() : "";
    }
}
