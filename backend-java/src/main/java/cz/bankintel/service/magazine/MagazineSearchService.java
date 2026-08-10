package cz.bankintel.service.magazine;

import cz.bankintel.domain.dto.MagazineDtos.SearchHit;
import cz.bankintel.domain.dto.MagazineDtos.SearchHitIssue;
import cz.bankintel.domain.entity.MagazineEntity;
import cz.bankintel.domain.entity.MagazineIssueEntity;
import cz.bankintel.domain.entity.MagazineTextChunkEntity;
import cz.bankintel.repository.MagazineIssueRepository;
import cz.bankintel.repository.MagazineRepository;
import cz.bankintel.repository.MagazineTextChunkRepository;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MagazineSearchService {

    private static final int CHUNK_SIZE = 900;
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+");
    private static final Set<String> STOPWORDS = Set.of(
            "pro", "jak", "kde", "kdy", "kdo", "proc", "jaky", "jaka", "jake", "jaci", "jakou",
            "ktery", "ktera", "ktere", "kteri", "kterou", "kterych",
            "jsou", "jsem", "jste", "byl", "byla", "bylo", "byly", "byti", "mit", "maji",
            "ten", "tak", "tam", "tady", "tento", "tato", "toto", "tyto", "cely", "cela", "cele",
            "nad", "pod", "mezi", "bez", "ale", "nebo", "tez", "tedy", "pak", "jen", "jeste",
            "muze", "muzu", "dokazes", "prosim", "chci", "chce", "ohledne", "ono", "ona", "oni");

    private final MagazineTextChunkRepository chunkRepository;
    private final MagazineIssueRepository issueRepository;
    private final MagazineRepository magazineRepository;

    @Transactional(readOnly = true)
    public List<SearchHit> search(String query, String magazineId, String issueId, int limit) {
        String q = query == null ? "" : query.trim();
        int cappedLimit = Math.min(Math.max(limit, 1), 100);
        if (q.isEmpty()) {
            return List.of();
        }
        Map<String, ScoredChunk> merged = new LinkedHashMap<>();
        for (String term : queryTerms(q)) {
            List<MagazineTextChunkEntity> rows =
                    chunkRepository.searchByTerm(blankToNull(magazineId), blankToNull(issueId), term, cappedLimit * 8);
            for (MagazineTextChunkEntity row : rows) {
                merged.compute(
                        row.getId(),
                        (id, existing) -> {
                            if (existing == null) {
                                return new ScoredChunk(row, 1.0);
                            }
                            existing.score += 1.0;
                            return existing;
                        });
            }
        }
        if (merged.isEmpty()) {
            List<String> stems = stemsFromQuery(q);
            int scanLimit = issueId != null && !issueId.isBlank() ? 400 : 1500;
            List<MagazineTextChunkEntity> scope =
                    chunkRepository.findForScope(blankToNull(magazineId), blankToNull(issueId));
            int scanned = 0;
            for (MagazineTextChunkEntity row : scope) {
                if (scanned++ >= scanLimit) {
                    break;
                }
                String folded = foldText(row.getText());
                int matched = 0;
                for (String stem : stems) {
                    if (folded.contains(stem)) {
                        matched++;
                    }
                }
                if (matched > 0) {
                    merged.putIfAbsent(row.getId(), new ScoredChunk(row, matched));
                }
            }
        }
        List<ScoredChunk> ranked = new ArrayList<>(merged.values());
        ranked.sort(Comparator.comparingDouble((ScoredChunk s) -> s.score).reversed());
        return hydrateHits(ranked.stream().map(s -> s).toList(), cappedLimit);
    }

    @Transactional(readOnly = true)
    public List<SearchHit> representativeChunks(String magazineId, String issueId, int limit) {
        int cappedLimit = Math.min(Math.max(limit, 1), 100);
        List<MagazineTextChunkEntity> rows =
                chunkRepository.findForScope(blankToNull(magazineId), blankToNull(issueId));
        int maxRows = magazineId != null && (issueId == null || issueId.isBlank()) ? 300 : Math.max(cappedLimit * 4, 40);
        List<ScoredChunk> picked = new ArrayList<>();
        int count = 0;
        for (MagazineTextChunkEntity row : rows) {
            if (count++ >= maxRows) {
                break;
            }
            picked.add(new ScoredChunk(row, 1.0));
        }
        if (magazineId != null && (issueId == null || issueId.isBlank())) {
            Map<String, Integer> perIssue = new HashMap<>();
            List<ScoredChunk> sampled = new ArrayList<>();
            for (ScoredChunk row : picked) {
                String iid = row.chunk.getIssueId();
                if (perIssue.getOrDefault(iid, 0) >= 2) {
                    continue;
                }
                perIssue.put(iid, perIssue.getOrDefault(iid, 0) + 1);
                sampled.add(row);
                if (sampled.size() >= Math.max(cappedLimit * 2, cappedLimit)) {
                    break;
                }
            }
            picked = sampled;
        }
        return hydrateHits(picked, cappedLimit);
    }

    private List<SearchHit> hydrateHits(List<ScoredChunk> rows, int limit) {
        List<ScoredChunk> slice = rows.size() > limit ? rows.subList(0, limit) : rows;
        Set<String> issueIds = new HashSet<>();
        for (ScoredChunk row : slice) {
            issueIds.add(row.chunk.getIssueId());
        }
        Map<String, MagazineIssueEntity> issues = new HashMap<>();
        for (String issueId : issueIds) {
            issueRepository.findById(issueId).ifPresent(i -> issues.put(issueId, i));
        }
        Set<String> magazineIds = new HashSet<>();
        for (MagazineIssueEntity issue : issues.values()) {
            if (issue.getMagazineId() != null) {
                magazineIds.add(issue.getMagazineId());
            }
        }
        Map<String, MagazineEntity> magazines = new HashMap<>();
        for (String magazineId : magazineIds) {
            magazineRepository.findById(magazineId).ifPresent(m -> magazines.put(magazineId, m));
        }
        List<SearchHit> out = new ArrayList<>();
        for (ScoredChunk row : slice) {
            MagazineIssueEntity issue = issues.get(row.chunk.getIssueId());
            MagazineEntity magazine = issue == null ? null : magazines.get(issue.getMagazineId());
            String txt = row.chunk.getText() == null ? "" : row.chunk.getText();
            SearchHitIssue hitIssue = new SearchHitIssue(
                    issue == null ? "" : issue.getId(),
                    issue == null ? "" : issue.getIssueLabel(),
                    issue == null ? "" : issue.getTitle(),
                    issue == null ? "" : issue.getMagazineId(),
                    magazine == null ? "" : magazine.getTitle());
            out.add(new SearchHit(
                    row.chunk.getId(),
                    row.score,
                    row.chunk.getPage(),
                    txt.length() > 260 ? txt.substring(0, 260) : txt,
                    txt.length() > CHUNK_SIZE ? txt.substring(0, CHUNK_SIZE) : txt,
                    hitIssue));
        }
        return out;
    }

    private static List<String> queryTerms(String query) {
        List<String> terms = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(foldText(query));
        while (matcher.find()) {
            String tok = matcher.group();
            if (tok.length() >= 3 && !STOPWORDS.contains(tok)) {
                terms.add(tok);
            }
        }
        if (terms.isEmpty()) {
            String folded = foldText(query);
            if (folded.length() >= 2) {
                terms.add(folded);
            }
        }
        return terms;
    }

    private static List<String> stemsFromQuery(String query) {
        Set<String> seen = new HashSet<>();
        List<String> stems = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(foldText(query));
        while (matcher.find()) {
            String tok = matcher.group();
            if (tok.length() < 3 || STOPWORDS.contains(tok)) {
                continue;
            }
            String stem = simpleStem(tok);
            if (stem.length() >= 3 && seen.add(stem)) {
                stems.add(stem);
            }
        }
        return stems;
    }

    private static String simpleStem(String token) {
        if (token.length() <= 4) {
            return token;
        }
        if (token.endsWith("ich") || token.endsWith("ych")) {
            return token.substring(0, token.length() - 3);
        }
        if (token.endsWith("em") || token.endsWith("im") || token.endsWith("um")) {
            return token.substring(0, token.length() - 2);
        }
        if (token.endsWith("ou") || token.endsWith("au")) {
            return token.substring(0, token.length() - 2);
        }
        if (token.endsWith("a") || token.endsWith("e") || token.endsWith("i") || token.endsWith("o")
                || token.endsWith("u") || token.endsWith("y")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    static String foldText(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.getType(ch) != Character.NON_SPACING_MARK) {
                sb.append(ch);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static final class ScoredChunk {
        private final MagazineTextChunkEntity chunk;
        private double score;

        private ScoredChunk(MagazineTextChunkEntity chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }
    }
}
