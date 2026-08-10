package cz.bankintel.service.bugreport;

import cz.bankintel.domain.entity.BugReportEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.BugReportRepository;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.util.IdGenerator;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class BugReportCreateService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final String THANKS = "Děkujeme, chyba byla nahlášena.";

    private final BugReportRepository bugReportRepository;
    private final BugReportScreenshotStorage screenshotStorage;
    private final CurrentUser currentUser;

    @Transactional
    public Map<String, Object> create(
            String title,
            String description,
            String contactEmail,
            String pageUrl,
            String userAgent,
            String viewport,
            String route,
            String priority,
            MultipartFile screenshot) {
        String t = clip(title, 200);
        String d = clip(description, 5000);
        if (t.strip().length() < 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Název musí mít alespoň 3 znaky.");
        }
        if (d.strip().length() < 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Popis musí mít alespoň 10 znaků.");
        }

        String pr = (priority != null ? priority : "medium").toLowerCase(Locale.ROOT).strip();
        if (!Set.of("low", "medium", "high").contains(pr)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatná priorita.");
        }

        String ce = clip(contactEmail, 320);
        if (!ce.isBlank() && !EMAIL.matcher(ce).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neplatný e-mail pro kontakt.");
        }

        Map<String, Object> shot = null;
        if (screenshot != null && !screenshot.isEmpty() && screenshot.getOriginalFilename() != null && !screenshot.getOriginalFilename().isBlank()) {
            try {
                shot = screenshotStorage.save(screenshot.getBytes(), screenshot.getContentType(), screenshot.getOriginalFilename());
            } catch (ResponseStatusException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Screenshot musí být obrázek PNG, JPG nebo WEBP do 5 MB.");
            }
        }

        UserEntity user = currentUser.optionalUserEntity();
        String reportId = IdGenerator.newId();
        BugReportEntity entity = new BugReportEntity();
        entity.setId(reportId);
        entity.setTitle(t);
        entity.setDescription(d);
        entity.setContactEmail(ce.isBlank() ? null : ce);
        entity.setPageUrl(clip(pageUrl, 1000));
        entity.setUserAgent(clip(userAgent, 1000));
        entity.setViewport(clip(viewport, 200));
        entity.setRoute(clip(route, 500));
        entity.setUserId(user != null ? user.getId() : null);
        entity.setUserEmail(user != null ? user.getEmail() : null);
        entity.setUserRole(user != null ? user.getRole() : null);
        entity.setStatus("open");
        entity.setPriority(pr);
        entity.setScreenshot(shot);
        entity.setCreatedAt(Instant.now());
        bugReportRepository.save(entity);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", reportId);
        out.put("ok", true);
        out.put("message", THANKS);
        return out;
    }

    private static String clip(String value, int max) {
        String s = value != null ? value.strip() : "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
