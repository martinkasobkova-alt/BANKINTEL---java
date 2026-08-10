package cz.bankintel.search;

import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Veřejné API pro náhled katalogu — deleguje na {@link CatalogPreviewOrchestrator}.
 */
@Service
@RequiredArgsConstructor
public class CatalogPreviewService {

    private final CatalogPreviewOrchestrator orchestrator;

    public Map<String, Object> preview(Map<String, Object> payload) {
        return orchestrator.preview(payload);
    }

    /** See {@link CatalogPreviewOrchestrator#previewAsyncIfSupported}. */
    public Optional<CatalogPreviewOrchestrator.AsyncPreviewHandle> previewAsyncIfSupported(Map<String, Object> payload) {
        return orchestrator.previewAsyncIfSupported(payload);
    }
}
