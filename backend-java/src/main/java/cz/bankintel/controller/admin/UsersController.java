package cz.bankintel.controller.admin;

import cz.bankintel.domain.dto.AdminDtos.AdminPatchUserRequest;
import cz.bankintel.domain.dto.AdminDtos.CreateUserRequest;
import cz.bankintel.security.AdminAccess;
import cz.bankintel.service.admin.UserAdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UsersController {

    private final AdminAccess adminAccess;
    private final UserAdminService userAdminService;

    @GetMapping({"", "/"})
    public List<Map<String, Object>> listUsers() {
        adminAccess.requireAdmin();
        return userAdminService.listUsers();
    }

    @GetMapping("/{userId}")
    public Map<String, Object> getUser(@PathVariable String userId) {
        adminAccess.requireAdmin();
        return userAdminService.getUser(userId);
    }

    @PostMapping({"", "/"})
    public Map<String, Object> createUser(@Valid @RequestBody CreateUserRequest request) {
        adminAccess.requireAdmin();
        return userAdminService.createUser(request);
    }

    @PatchMapping("/{userId}")
    public Map<String, Object> patchUser(
            @PathVariable String userId,
            @Valid @RequestBody AdminPatchUserRequest request,
            HttpServletRequest httpRequest) {
        var admin = adminAccess.requireAdmin();
        return userAdminService.patchUser(
                userId, request, admin, httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"));
    }

    @DeleteMapping("/{userId}")
    public Map<String, Object> deleteUser(@PathVariable String userId, HttpServletRequest httpRequest) {
        var admin = adminAccess.requireAdmin();
        return userAdminService.deleteUser(userId, admin, httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent"));
    }
}
