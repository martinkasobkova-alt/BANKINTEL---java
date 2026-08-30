package cz.bankintel.controller;

import cz.bankintel.connector.health.ConnectorHealthService;
import cz.bankintel.search.openai.OpenAiUsageMeter;
import cz.bankintel.security.AdminAccess;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operational surface for the two things that previously failed silently: an external data source
 * going down, and AI token spend.
 *
 * <p>The read endpoints are public because they expose no user data and are the natural target for
 * uptime monitoring. Forcing a fresh probe fans out to every upstream, so that one is admin-only.
 */
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class ConnectorHealthController {

    private final ConnectorHealthService connectorHealthService;
    private final OpenAiUsageMeter openAiUsageMeter;
    private final AdminAccess adminAccess;

    /** Last known reachability of every external source, as of the most recent scheduled probe. */
    @GetMapping("/connectors")
    public Map<String, Object> connectors() {
        return connectorHealthService.snapshot();
    }

    @PostMapping("/connectors/probe")
    public Map<String, Object> probeConnectors() {
        adminAccess.requireAdmin();
        return connectorHealthService.probeAll();
    }

    /** Token spend per task since process start. */
    @GetMapping("/ai-usage")
    public Map<String, Object> aiUsage() {
        return openAiUsageMeter.snapshot();
    }
}
