package cz.bankintel.service.magazine;

import cz.bankintel.domain.entity.MagazineIssueEntity;
import cz.bankintel.domain.entity.MagazineTextChunkEntity;
import cz.bankintel.domain.entity.StoredFileEntity;
import cz.bankintel.repository.MagazineIssueRepository;
import cz.bankintel.repository.MagazineTextChunkRepository;
import cz.bankintel.repository.StoredFileRepository;
import cz.bankintel.storage.MagazineStorageService;
import cz.bankintel.util.IdGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class MagazineIngestService {

    private final MagazineIssueRepository issueRepository;
    private final MagazineTextChunkRepository chunkRepository;
    private final StoredFileRepository storedFileRepository;
    private final MagazineStorageService storageService;
    private final MagazinePdfTextExtractor pdfTextExtractor;
    private final MagazineTextChunker textChunker;
    /**
     * Self-reference (lazy, so no circular-dependency issue) used to invoke {@link #ingestIssue}
     * THROUGH the Spring proxy. A plain {@code this.ingestIssue(...)} self-call would bypass the
     * proxy and thus the {@code @Transactional} advice, so the bulk {@code @Modifying} delete
     * ({@code chunkRepository.deleteByIssueId}) would run without a transaction and fail with
     * "Executing an update/delete query" (TransactionRequiredException). Real uploaded PDFs hit
     * ingest (unlike seeded issues, which are marked ready directly), so this affected every upload.
     */
    private final ObjectProvider<MagazineIngestService> selfProvider;

    @Async
    public void ingestIssueAsync(String issueId) {
        try {
            selfProvider.getObject().ingestIssue(issueId);
        } catch (Exception e) {
            log.error("Issue ingest failed issueId={}", issueId, e);
        }
    }

    @Transactional
    public void ingestIssue(String issueId) {
        MagazineIssueEntity issue =
                issueRepository.findById(issueId).orElse(null);
        if (issue == null) {
            return;
        }
        issue.setIngestStatus("indexing");
        issue.setIngestError("");
        issueRepository.save(issue);

        try {
            StoredFileEntity file = issue.getStoredFileId() == null
                    ? null
                    : storedFileRepository.findById(issue.getStoredFileId()).orElse(null);
            if (file == null) {
                throw new IllegalStateException("PDF soubor není dostupný.");
            }
            byte[] raw = storageService.readPdf(file);
            List<MagazinePdfTextExtractor.PageText> pages = pdfTextExtractor.extractPages(raw);
            List<MagazineTextChunkEntity> docs = new ArrayList<>();
            for (MagazinePdfTextExtractor.PageText pageEntry : pages) {
                int pageNo = pageEntry.page();
                String text = pageEntry.text();
                if (pageNo <= 0 || text == null || text.isBlank()) {
                    continue;
                }
                List<String> chunks = textChunker.chunkText(text);
                for (int idx = 0; idx < chunks.size(); idx++) {
                    MagazineTextChunkEntity chunk = new MagazineTextChunkEntity();
                    chunk.setId(IdGenerator.newId());
                    chunk.setIssueId(issueId);
                    chunk.setMagazineId(issue.getMagazineId());
                    chunk.setPage(pageNo);
                    chunk.setChunkOrder(idx + 1);
                    chunk.setText(chunks.get(idx));
                    chunk.setCreatedAt(Instant.now());
                    docs.add(chunk);
                }
            }
            chunkRepository.deleteByIssueId(issueId);
            if (!docs.isEmpty()) {
                chunkRepository.saveAll(docs);
            }
            issue.setIngestStatus("ready");
            issue.setIngestError("");
            issue.setPageCount(pages.size());
            issue.setChunkCount(docs.size());
            issue.setIngestedAt(Instant.now());
            issueRepository.save(issue);
        } catch (Exception e) {
            log.error("Issue ingest failed issueId={}", issueId, e);
            issue.setIngestStatus("error");
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            issue.setIngestError(msg.length() > 500 ? msg.substring(0, 500) : msg);
            issueRepository.save(issue);
        }
    }
}
