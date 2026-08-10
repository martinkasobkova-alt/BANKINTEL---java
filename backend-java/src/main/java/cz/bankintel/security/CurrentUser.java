package cz.bankintel.security;

import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.UserRepository;
import cz.bankintel.util.RoleUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@RequiredArgsConstructor
public class CurrentUser {

    private final UserRepository userRepository;

    public JwtService.AuthenticatedUser requireUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof JwtService.AuthenticatedUser user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return user;
    }

    public UserEntity requireUserEntity() {
        JwtService.AuthenticatedUser auth = requireUser();
        return userRepository
                .findById(auth.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized"));
    }

    public JwtService.AuthenticatedUser optionalUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof JwtService.AuthenticatedUser user)) {
            return null;
        }
        return user;
    }

    public UserEntity optionalUserEntity() {
        JwtService.AuthenticatedUser user = optionalUser();
        if (user == null) {
            return null;
        }
        return userRepository.findById(user.id()).orElse(null);
    }

    public UserEntity requireEditor() {
        UserEntity user = requireUserEntity();
        if (!RoleUtils.isContentManager(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Vyžadována role editor nebo admin.");
        }
        return user;
    }

    public UserEntity requireAdmin() {
        UserEntity user = requireUserEntity();
        if (!RoleUtils.isAdminRole(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Jen pro administrátory.");
        }
        return user;
    }
}
