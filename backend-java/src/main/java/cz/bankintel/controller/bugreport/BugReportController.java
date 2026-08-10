package cz.bankintel.controller.bugreport;

import cz.bankintel.service.bugreport.BugReportCreateService;
import cz.bankintel.service.captcha.TurnstileService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/bug-reports")
@RequiredArgsConstructor
public class BugReportController {

    private final BugReportCreateService bugReportCreateService;
    private final TurnstileService turnstileService;

    @PostMapping({"", "/"})
    public Map<String, Object> create(
            @RequestParam("title") String title,
            @RequestParam("description") String description,
            @RequestParam(value = "contact_email", required = false) String contactEmail,
            @RequestParam(value = "page_url", defaultValue = "") String pageUrl,
            @RequestParam(value = "user_agent", defaultValue = "") String userAgent,
            @RequestParam(value = "viewport", defaultValue = "") String viewport,
            @RequestParam(value = "route", defaultValue = "") String route,
            @RequestParam(value = "priority", defaultValue = "medium") String priority,
            @RequestParam(value = "captcha_token", required = false) String captchaToken,
            @RequestParam(value = "screenshot", required = false) MultipartFile screenshot) {
        turnstileService.requireTurnstileOrBypass(captchaToken, "bug_report");
        return bugReportCreateService.create(
                title, description, contactEmail, pageUrl, userAgent, viewport, route, priority, screenshot);
    }
}
