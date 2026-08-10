package cz.bankintel.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Port of {@code root} (backend/server.py, ř. 474) — jednoduché info o běžícím API.
 */
@RestController
public class RootController {

    @GetMapping("/api")
    public Map<String, Object> root() {
        return Map.of("name", "BankIntel BI", "status", "ok");
    }
}
