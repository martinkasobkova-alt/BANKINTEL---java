package cz.bankintel.controller.content;

import cz.bankintel.security.CurrentUser;
import cz.bankintel.security.RoleGuard;
import cz.bankintel.service.content.MediaUploadService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaUploadService mediaUploadService;
    private final CurrentUser currentUser;
    private final RoleGuard roleGuard;

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        roleGuard.requireEditor(currentUser.requireUserEntity());
        return mediaUploadService.upload(currentUser.requireUserEntity(), file);
    }
}
