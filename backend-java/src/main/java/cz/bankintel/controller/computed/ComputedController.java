package cz.bankintel.controller.computed;

import cz.bankintel.domain.dto.AdminDtos.ComputedIndicatorCreateRequest;
import cz.bankintel.security.AdminAccess;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.service.computed.ComputedOperations;
import cz.bankintel.service.computed.ComputedService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/computed")
@RequiredArgsConstructor
public class ComputedController {

    private final ComputedService computedService;
    private final AdminAccess adminAccess;
    private final CurrentUser currentUser;

    @GetMapping({"", "/"})
    public List<Map<String, Object>> listComputed() {
        return computedService.listComputed(currentUser.optionalUserEntity());
    }

    @PostMapping({"", "/"})
    public Map<String, Object> createComputed(@Valid @RequestBody ComputedIndicatorCreateRequest request) {
        return computedService.createComputed(request, adminAccess.requireAdmin());
    }

    @PostMapping("/preview")
    public Map<String, Object> previewComputed(@RequestBody Map<String, Object> payload) {
        return computedService.previewComputed(payload, adminAccess.requireAdmin());
    }

    @GetMapping("/operations")
    public List<Map<String, String>> listOperations() {
        return ComputedOperations.sortedOperations();
    }

    @GetMapping("/{computedId}/run")
    public Map<String, Object> runComputed(@PathVariable String computedId) {
        return computedService.runComputed(computedId, currentUser.optionalUserEntity());
    }

    @GetMapping("/{computedId}")
    public Map<String, Object> getComputed(@PathVariable String computedId) {
        return computedService.getComputed(computedId, currentUser.optionalUserEntity());
    }

    @PutMapping("/{computedId}")
    public Map<String, Object> updateComputed(
            @PathVariable String computedId, @Valid @RequestBody ComputedIndicatorCreateRequest request) {
        return computedService.updateComputed(computedId, request, adminAccess.requireAdmin());
    }

    @DeleteMapping("/{computedId}")
    public Map<String, Object> deleteComputed(@PathVariable String computedId) {
        adminAccess.requireAdmin();
        return computedService.deleteComputed(computedId);
    }
}
