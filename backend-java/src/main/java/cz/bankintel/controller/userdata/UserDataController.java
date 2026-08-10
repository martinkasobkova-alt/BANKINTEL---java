package cz.bankintel.controller.userdata;

import cz.bankintel.domain.dto.UserDataDtos.UserSeriesMapRequest;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.service.userdata.UserDataService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/user-data")
@RequiredArgsConstructor
public class UserDataController {

    private final CurrentUser currentUser;
    private final UserDataService userDataService;

    @PostMapping("/upload")
    public Map<String, Object> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "company_id", required = false) String companyId) {
        return userDataService.upload(currentUser.requireUserEntity(), file, companyId);
    }

    @GetMapping("/uploads")
    public Map<String, Object> listUploads(@RequestParam(value = "company_id", required = false) String companyId) {
        return userDataService.listUploads(currentUser.requireUserEntity(), companyId);
    }

    @GetMapping("/uploads/{uploadId}")
    public Map<String, Object> uploadDetail(@PathVariable String uploadId) {
        return userDataService.getUploadDetail(currentUser.requireUserEntity(), uploadId);
    }

    @DeleteMapping("/uploads/{uploadId}")
    public Map<String, Object> deleteUpload(@PathVariable String uploadId) {
        return userDataService.deleteUpload(currentUser.requireUserEntity(), uploadId);
    }

    @GetMapping("/series")
    public Map<String, Object> listSeries(
            @RequestParam(value = "company_id", required = false) String companyId,
            @RequestParam(value = "upload_id", required = false) String uploadId) {
        return userDataService.listSeries(currentUser.requireUserEntity(), companyId, uploadId);
    }

    @PostMapping("/series/{seriesId}/map")
    public Map<String, Object> mapSeries(
            @PathVariable String seriesId, @RequestBody UserSeriesMapRequest body) {
        return userDataService.mapSeries(currentUser.requireUserEntity(), seriesId, body);
    }
}
