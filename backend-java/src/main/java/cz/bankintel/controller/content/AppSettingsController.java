package cz.bankintel.controller.content;

import cz.bankintel.security.AdminAccess;
import cz.bankintel.service.settings.AppSettingsService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AppSettingsController {

    private final AppSettingsService appSettingsService;
    private final AdminAccess adminAccess;

    @GetMapping("/app-settings")
    public Map<String, Object> getAppSettings() {
        return appSettingsService.getAppSettings();
    }

    @PatchMapping("/app-settings")
    public Map<String, Object> patchAppSettings(@RequestBody Map<String, Object> payload) {
        adminAccess.requireAdmin();
        return appSettingsService.patchAppSettings(payload);
    }
}
