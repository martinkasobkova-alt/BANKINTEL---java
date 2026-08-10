package cz.bankintel.search.v2.vector;

import cz.bankintel.search.v2.sidecar.SearchCatalogSidecarDocument;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class VectorDocumentBuilder {

    private static final int MAX_TEXT_LENGTH = 2_400;

    public String build(SearchCatalogSidecarDocument document) {
        Set<String> parts = new LinkedHashSet<>();
        add(parts, document.canonicalTitleCs());
        add(parts, document.canonicalTitleEn());
        add(parts, document.originalTitle());
        add(parts, document.canonicalDescriptionCs());
        add(parts, document.canonicalDescriptionEn());
        add(parts, document.originalDescription());
        addAll(parts, document.aliasesCs());
        addAll(parts, document.aliasesEn());
        addAll(parts, document.abbreviations());
        addAll(parts, document.concepts());
        add(parts, document.dataset());
        add(parts, document.frequency());
        add(parts, document.unit());
        add(parts, document.seasonalAdjustment());
        add(parts, document.source());
        String text = String.join(". ", parts);
        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH);
    }

    public String queryText(String originalQuery, List<String> concepts, List<String> semanticTerms) {
        Set<String> parts = new LinkedHashSet<>();
        add(parts, originalQuery);
        addAll(parts, concepts);
        addAll(parts, semanticTerms);
        return String.join(". ", parts);
    }

    private static void addAll(Set<String> target, List<String> values) {
        if (values != null) {
            values.forEach(value -> add(target, value));
        }
    }

    private static void add(Set<String> target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        String identity = normalized.toLowerCase(Locale.ROOT);
        if (target.stream().noneMatch(existing -> existing.toLowerCase(Locale.ROOT).equals(identity))) {
            target.add(normalized);
        }
    }
}
