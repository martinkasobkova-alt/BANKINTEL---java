package cz.bankintel.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Matchable semantic slots for the final catalog rank.
 *
 * <p>The profile is intentionally query-shaped: a query such as "cena ropy" becomes a metric
 * slot (price) plus a domain slot (oil). A candidate must match the whole shape to outrank rows
 * that only mention the domain or only mention the metric.
 */
final class CatalogQueryRelevanceProfile {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private final List<SemanticGroup> groups;
    private final int metricGroupCount;
    private final int domainGroupCount;

    private CatalogQueryRelevanceProfile(List<SemanticGroup> groups) {
        this.groups = List.copyOf(groups);
        this.metricGroupCount = (int) groups.stream().filter(SemanticGroup::metric).count();
        this.domainGroupCount = groups.size() - metricGroupCount;
    }

    static CatalogQueryRelevanceProfile from(String query, Map<String, Object> geoIntent) {
        CatalogQueryIntent.QueryIntent intent = CatalogQueryIntent.classifyQueryIntent(query, geoIntent);
        Set<String> seen = new LinkedHashSet<>();
        List<SemanticGroup> groups = new ArrayList<>();

        for (CatalogQueryIntent.IntentTerm term : intent.metricTerms()) {
            addGroup(groups, seen, term.raw(), true, semanticSurfaces(term.raw(), term.surfaces()));
        }
        for (CatalogQueryIntent.IntentTerm term : intent.domainTerms()) {
            addGroup(groups, seen, term.raw(), false, semanticSurfaces(term.raw(), term.surfaces()));
        }

        Set<String> geoTokens = geoTokenSet(geoIntent);
        for (String token : CatalogRequiredTokenScorer.extractRequiredTokens(query)) {
            String label = normalizeSurface(token);
            if (label.length() < 2 || seen.contains(label) || isGeoToken(label, geoTokens)) {
                continue;
            }
            List<String> surfaces = semanticSurfaces(label, List.of());
            boolean expanded = surfaces.size() > 1;
            if (CatalogSearchLexicon.isGenericToken(label) && !expanded) {
                continue;
            }
            addGroup(groups, seen, label, fallbackMetricGroup(label, surfaces), surfaces);
        }
        return new CatalogQueryRelevanceProfile(groups);
    }

    int groupCount() {
        return groups.size();
    }

    int metricGroupCount() {
        return metricGroupCount;
    }

    int domainGroupCount() {
        return domainGroupCount;
    }

    List<String> labels() {
        return groups.stream().map(SemanticGroup::label).toList();
    }

    SemanticFit match(String title, String haystack) {
        if (groups.isEmpty()) {
            return SemanticFit.empty();
        }
        String titleF = normalizeSurface(title);
        String hayF = normalizeSurface(haystack);
        List<String> hitLabels = new ArrayList<>();
        int totalHits = 0;
        int titleHits = 0;
        int metricHits = 0;
        int domainHits = 0;
        for (SemanticGroup group : groups) {
            boolean titleHit = group.surfaces().stream().anyMatch(surface -> surfaceHit(titleF, surface));
            boolean hayHit = titleHit || group.surfaces().stream().anyMatch(surface -> surfaceHit(hayF, surface));
            if (!hayHit) {
                continue;
            }
            totalHits++;
            if (titleHit) {
                titleHits++;
            }
            if (group.metric()) {
                metricHits++;
            } else {
                domainHits++;
            }
            hitLabels.add(group.label());
        }
        return new SemanticFit(totalHits, titleHits, metricHits, domainHits, List.copyOf(hitLabels));
    }

    int titleProximityBonus(String title) {
        if (metricGroupCount == 0 || domainGroupCount == 0) {
            return 0;
        }
        String titleF = normalizeSurface(title);
        if (titleF.isBlank()) {
            return 0;
        }
        for (SemanticGroup metric : groups) {
            if (!metric.metric()) {
                continue;
            }
            List<SurfaceSpan> metricSpans = surfaceSpans(titleF, metric.surfaces());
            for (SemanticGroup domain : groups) {
                if (domain.metric()) {
                    continue;
                }
                List<SurfaceSpan> domainSpans = surfaceSpans(titleF, domain.surfaces());
                for (SurfaceSpan metricSpan : metricSpans) {
                    for (SurfaceSpan domainSpan : domainSpans) {
                        if (directTitlePair(titleF, metricSpan, domainSpan)) {
                            return 360;
                        }
                    }
                }
            }
        }
        return 0;
    }

    private static void addGroup(
            List<SemanticGroup> groups, Set<String> seen, String labelRaw, boolean metric, List<String> surfaces) {
        String label = normalizeSurface(labelRaw);
        if (label.length() < 2 || !seen.add(label)) {
            return;
        }
        LinkedHashSet<String> cleanSurfaces = new LinkedHashSet<>(surfaces);
        cleanSurfaces.add(label);
        groups.add(new SemanticGroup(label, metric, List.copyOf(cleanSurfaces)));
    }

    private static List<String> semanticSurfaces(String raw, List<String> intentSurfaces) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        addSurface(out, raw);
        for (String surface : intentSurfaces == null ? List.<String>of() : intentSurfaces) {
            addSurface(out, surface);
        }
        for (String surface : CatalogSearchLexicon.relatedSurfaces(raw)) {
            addSurface(out, surface);
        }
        for (String surface : CatalogSearchLexicon.commoditySurfacesForStem(raw)) {
            addSurface(out, surface);
        }
        return List.copyOf(out);
    }

    private static void addSurface(Set<String> out, String value) {
        String surface = normalizeSurface(value);
        if (surface.length() >= 2) {
            out.add(surface);
        }
    }

    private static boolean fallbackMetricGroup(String label, List<String> surfaces) {
        if (CatalogSearchLexicon.isGenericToken(label)) {
            return true;
        }
        String joined = " " + label + " " + String.join(" ", surfaces) + " ";
        return joined.contains(" price ")
                || joined.contains(" prices ")
                || joined.contains(" rate ")
                || joined.contains(" ratio ")
                || joined.contains(" profit ")
                || joined.contains(" profitability ")
                || joined.contains(" return on ")
                || joined.contains(" gdp ")
                || joined.contains(" inflation ")
                || joined.contains(" unemployment ");
    }

    private static boolean surfaceHit(String hayFolded, String surface) {
        if (hayFolded.isBlank() || surface.isBlank()) {
            return false;
        }
        if (surface.contains(" ")) {
            return hayFolded.contains(surface);
        }
        String paddedHay = " " + hayFolded + " ";
        if (surface.length() <= 4) {
            return paddedHay.contains(" " + surface + " ");
        }
        return hayFolded.contains(surface) || paddedHay.contains(" " + surface + " ");
    }

    private static List<SurfaceSpan> surfaceSpans(String hayFolded, List<String> surfaces) {
        List<SurfaceSpan> out = new ArrayList<>();
        for (String surface : surfaces) {
            int pos = surfacePosition(hayFolded, surface);
            if (pos >= 0) {
                out.add(new SurfaceSpan(pos, surface.length()));
            }
        }
        return out;
    }

    private static int surfacePosition(String hayFolded, String surface) {
        if (!surfaceHit(hayFolded, surface)) {
            return -1;
        }
        if (surface.contains(" ")) {
            return hayFolded.indexOf(surface);
        }
        String paddedHay = " " + hayFolded + " ";
        String needle = " " + surface + " ";
        int bounded = paddedHay.indexOf(needle);
        if (bounded >= 0) {
            return Math.max(0, bounded - 1);
        }
        return surface.length() > 4 ? hayFolded.indexOf(surface) : -1;
    }

    private static boolean directTitlePair(String titleFolded, SurfaceSpan metric, SurfaceSpan domain) {
        if (domain.position() <= metric.position()) {
            int gap = metric.position() - domain.end();
            return gap >= 0 && gap <= 32;
        }
        int gap = domain.position() - metric.end();
        if (gap < 0 || gap > 24) {
            return false;
        }
        String between = titleFolded.substring(metric.end(), domain.position()).trim();
        return between.isBlank() || "of".equals(between) || "for".equals(between);
    }

    private static Set<String> geoTokenSet(Map<String, Object> geoIntent) {
        Set<String> out = new LinkedHashSet<>();
        for (String term : CatalogRequiredTokenScorer.geoScoringTerms(geoIntent)) {
            addSurface(out, term);
            for (String word : term.split("\\s+")) {
                addSurface(out, word);
            }
        }
        return out;
    }

    private static boolean isGeoToken(String label, Set<String> geoTokens) {
        if (label == null || label.isBlank() || geoTokens == null || geoTokens.isEmpty()) {
            return false;
        }
        if (geoTokens.contains(label)) {
            return true;
        }
        for (String token : geoTokens) {
            if (token.length() >= 4 && label.startsWith(token)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeSurface(String value) {
        String folded = CatalogTextUtils.foldAscii(value == null ? "" : value);
        return NON_ALNUM.matcher(folded).replaceAll(" ").trim().replaceAll("\\s+", " ");
    }

    private record SemanticGroup(String label, boolean metric, List<String> surfaces) {}

    private record SurfaceSpan(int position, int length) {
        int end() {
            return position + length;
        }
    }

    record SemanticFit(int totalHits, int titleHits, int metricHits, int domainHits, List<String> hitLabels) {
        static SemanticFit empty() {
            return new SemanticFit(0, 0, 0, 0, List.of());
        }
    }
}
