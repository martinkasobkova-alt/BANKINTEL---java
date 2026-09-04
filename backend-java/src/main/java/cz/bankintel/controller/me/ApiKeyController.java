package cz.bankintel.controller.me;

import cz.bankintel.domain.dto.ApiKeyDtos.ApiKeyCreateRequest;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.service.me.ApiKeyService;
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

/** Lets a logged-in user manage their own {@code /api/connect/**} API keys. */
@RestController
@RequestMapping("/api/me/api-keys")
@RequiredArgsConstructor
public class ApiKeyController {

    private final CurrentUser currentUser;
    private final ApiKeyService apiKeyService;

    @GetMapping
    public List<Map<String, Object>> list() {
        return apiKeyService.list(currentUser.requireUserEntity());
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody ApiKeyCreateRequest body) {
        return apiKeyService.create(currentUser.requireUserEntity(), body);
    }

    @DeleteMapping("/{id}")
    public Map<String, Boolean> revoke(@PathVariable String id) {
        apiKeyService.revoke(currentUser.requireUserEntity(), id);
        return Map.of("ok", true);
    }
}
