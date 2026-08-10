package cz.bankintel.controller.content;

import cz.bankintel.security.AdminAccess;
import cz.bankintel.service.content.AdSlotsService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ad-slots")
@RequiredArgsConstructor
public class AdSlotsController {

    private final AdSlotsService adSlotsService;
    private final AdminAccess adminAccess;

    @GetMapping({"", "/"})
    public Map<String, Object> getAdSlots() {
        return adSlotsService.getAdSlots();
    }

    @PutMapping("/{slotName}")
    public Map<String, Object> putSlot(@PathVariable String slotName, @RequestBody Map<String, Object> payload) {
        adminAccess.requireAdmin();
        try {
            return adSlotsService.updateSlot(slotName, payload != null ? payload : Map.of());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }
}
