package cz.bankintel.service.magazine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import cz.bankintel.domain.dto.MagazineDtos.MagazineCreateRequest;
import cz.bankintel.domain.dto.MagazineDtos.MagazineResponse;
import cz.bankintel.domain.entity.MagazineEntity;
import cz.bankintel.repository.MagazineIssueRepository;
import cz.bankintel.repository.MagazinePdfLinkRepository;
import cz.bankintel.repository.MagazineRepository;
import cz.bankintel.repository.StoredFileRepository;
import cz.bankintel.storage.MagazineStorageService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * Regression test for the ported {@code PATCH /api/magazines/{magazineId}} behaviour
 * (magazines_routes.py, ř. 628): updates title/slug/description in place.
 */
@ExtendWith(MockitoExtension.class)
class MagazineServicePatchTest {

    @Mock
    private MagazineRepository magazineRepository;

    @Mock
    private MagazineIssueRepository issueRepository;

    @Mock
    private MagazinePdfLinkRepository linkRepository;

    @Mock
    private StoredFileRepository storedFileRepository;

    @Mock
    private MagazineStorageService storageService;

    @Mock
    private MagazineIngestService ingestService;

    @Mock
    private MagazineSearchService searchService;

    @Mock
    private MagazinePdfTextExtractor pdfTextExtractor;

    private MagazineService service;

    @BeforeEach
    void setUp() {
        service = new MagazineService(
                magazineRepository,
                issueRepository,
                linkRepository,
                storedFileRepository,
                storageService,
                ingestService,
                searchService,
                pdfTextExtractor);
    }

    private MagazineEntity existingMagazine() {
        MagazineEntity entity = new MagazineEntity();
        entity.setId("mag-1");
        entity.setTitle("Starý název");
        entity.setSlug("stary-nazev");
        entity.setDescription("Starý popis");
        entity.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        entity.setUpdatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        return entity;
    }

    @Test
    void patchMagazineUpdatesTitleSlugAndDescription() {
        MagazineEntity entity = existingMagazine();
        when(magazineRepository.findById("mag-1")).thenReturn(Optional.of(entity));
        when(magazineRepository.existsBySlugAndIdNot(eq("novy-slug"), eq("mag-1"))).thenReturn(false);
        when(magazineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(issueRepository.countByMagazineId("mag-1")).thenReturn(3L);
        when(issueRepository.countReadyByMagazineId("mag-1")).thenReturn(2L);

        MagazineResponse response = service.patchMagazine(
                "mag-1", new MagazineCreateRequest("Nový název", "novy-slug", "Nový popis"));

        assertEquals("mag-1", response.id());
        assertEquals("Nový název", response.title());
        assertEquals("novy-slug", response.slug());
        assertEquals("Nový popis", response.description());
        assertEquals(3, response.issueCount());
        assertEquals(2, response.readyIssueCount());
    }

    @Test
    void patchMagazineThrows404WhenMissing() {
        when(magazineRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(
                ResponseStatusException.class,
                () -> service.patchMagazine("missing", new MagazineCreateRequest("T", "s", "d")));
    }
}
