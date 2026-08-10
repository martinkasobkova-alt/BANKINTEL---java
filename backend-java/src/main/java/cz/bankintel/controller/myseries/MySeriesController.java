package cz.bankintel.controller.myseries;

import cz.bankintel.domain.dto.MySeriesDtos.MySavedSeriesCreateRequest;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.service.myseries.MySavedSeriesService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/my-series")
@RequiredArgsConstructor
public class MySeriesController {

    private final CurrentUser currentUser;
    private final MySavedSeriesService mySavedSeriesService;

    @GetMapping({"", "/"})
    public List<Map<String, Object>> list() {
        return mySavedSeriesService.list(currentUser.requireUserEntity());
    }

    @PostMapping({"", "/"})
    public Map<String, Object> create(@Valid @RequestBody MySavedSeriesCreateRequest body) {
        return mySavedSeriesService.create(currentUser.requireUserEntity(), body);
    }

    @GetMapping("/{seriesId}")
    public Map<String, Object> detail(@PathVariable String seriesId) {
        return mySavedSeriesService.get(currentUser.requireUserEntity(), seriesId);
    }

    @DeleteMapping("/{seriesId}")
    public Map<String, Object> delete(@PathVariable String seriesId) {
        return mySavedSeriesService.delete(currentUser.requireUserEntity(), seriesId);
    }

    @PostMapping("/{seriesId}/refresh")
    public Map<String, Object> refresh(@PathVariable String seriesId) {
        return mySavedSeriesService.refresh(currentUser.requireUserEntity(), seriesId);
    }
}
