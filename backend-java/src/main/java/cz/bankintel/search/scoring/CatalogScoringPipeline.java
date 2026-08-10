package cz.bankintel.search.scoring;

import cz.bankintel.search.AradSeriesIdentity;
import cz.bankintel.search.CatalogCompositeScorer;
import cz.bankintel.search.CatalogGeoIntent;
import cz.bankintel.search.CatalogLikelySources;
import cz.bankintel.search.CatalogMetadataScorer;
import cz.bankintel.search.CatalogSearchMetadataSidecar;
import cz.bankintel.search.CatalogSearchVariantDedup;
import cz.bankintel.search.CatalogSourceRegistry;
import cz.bankintel.search.CatalogTextUtils;
import cz.bankintel.search.model.CatalogHit;
import cz.bankintel.search.model.CatalogKeys;
import cz.bankintel.search.model.CatalogMapSupport;
import cz.bankintel.search.model.CatalogRawRow;
import cz.bankintel.search.model.GeoIntentSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Single scoring pipeline entry point — orchestrates sidecar metadata, composite blend, geo adjust, dedup.
 *
 * <p>Individual classes keep one role each (see {@code docs/CATALOG_SCORING.md}); this wires them in order.
 */
@Component
public class CatalogScoringPipeline {

    private final CatalogSearchMetadataSidecar metadataSidecar;

    public CatalogScoringPipeline(CatalogSearchMetadataSidecar metadataSidecar) {
        this.metadataSidecar = metadataSidecar;
    }

    public List<CatalogHit> scoreAndRank(
            String source, String queryRaw, List<CatalogRawRow> rows, int limit) {
        List<String> needles = CatalogTextUtils.needlesFromQuery(queryRaw.trim());
        List<String> likelySources = CatalogLikelySources.inferLikelyCatalogSources(queryRaw);
        List<String> intentTags = metadataSidecar.queryIntentTags(queryRaw);
        GeoIntentSnapshot geoIntent = GeoIntentSnapshot.fromDetection(queryRaw);
        List<CatalogHit> scored = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CatalogRawRow raw : rows) {
            String identity = rowIdentityKey(source, raw);
            if (identity.isBlank() || !seen.add(identity)) {
                continue;
            }
            Map<String, Object> rowMap = raw.fields();
            CatalogGeoIntent.GeoRowAdjustment geoAdj =
                    CatalogGeoIntent.rowCountryGeoAdjustment(rowMap, geoIntent.toMap());
            if (geoAdj.hardReject()) {
                continue;
            }
            String title = raw.title();
            int match = CatalogTextUtils.titleMatchScore(CatalogTextUtils.foldAscii(title), needles);
            if (match <= 0) {
                match = raw.matchScore() > 0 ? raw.matchScore() : 5;
            }
            String displayId = displaySetId(source, rowMap);
            Map<String, Object> metadata =
                    metadataSidecar.getSearchMetadata(source, displayId, CatalogMapSupport.str(rowMap.get(CatalogKeys.SET_ID)));
            int metaScore = CatalogMetadataScorer.scoreRow(source, queryRaw, rowMap, metadata, intentTags);
            List<String> geoCodes = CatalogGeoIntent.requestedGeoCodes(geoIntent.toMap());
            int total = CatalogCompositeScorer.scoreWithLikelySources(
                    source,
                    queryRaw,
                    rowMap,
                    needles,
                    metaScore,
                    match,
                    likelySources,
                    geoIntent.toMap(),
                    geoCodes);
            total = (int) Math.round(total * geoAdj.multiplier());
            scored.add(new CatalogHit(
                    source,
                    displayId,
                    title,
                    title,
                    CatalogMapSupport.str(rowMap.getOrDefault(CatalogKeys.FULL_PATH, rowMap.get("tree_path"))),
                    total,
                    metaScore,
                    match,
                    geoAdj.reason(),
                    CatalogGeoIntent.extractRowCountryCode(rowMap),
                    raw,
                    0));
        }
        scored.sort(Comparator.comparingInt(CatalogHit::searchScore).reversed());
        List<Map<String, Object>> asMaps = scored.stream().map(CatalogHit::toMap).toList();
        asMaps = CatalogSearchVariantDedup.consolidateDisplayRows(asMaps);
        List<CatalogHit> deduped = asMaps.stream().map(CatalogHit::fromMap).toList();
        if (deduped.size() > limit) {
            return new ArrayList<>(deduped.subList(0, limit));
        }
        return deduped;
    }

    public List<Map<String, Object>> scoreAndRankAsMaps(
            String source, String queryRaw, List<Map<String, Object>> rows, int limit) {
        List<CatalogRawRow> typed = rows.stream().map(CatalogRawRow::of).toList();
        return CatalogMapSupport.toMaps(scoreAndRank(source, queryRaw, typed, limit));
    }

    private static String rowIdentityKey(String source, CatalogRawRow row) {
        return rowIdentityKey(source, row.fields());
    }

    private static String rowIdentityKey(String source, Map<String, Object> row) {
        if ("arad".equals(source)) {
            return AradSeriesIdentity.fromRow(row).toLowerCase(Locale.ROOT);
        }
        return CatalogMapSupport.str(row.getOrDefault(CatalogKeys.SET_ID, row.get("id"))).toLowerCase(Locale.ROOT);
    }

    private static String displaySetId(String source, Map<String, Object> row) {
        if ("arad".equals(source)) {
            String composite = AradSeriesIdentity.fromRow(row);
            if (!composite.isBlank()) {
                return composite;
            }
        }
        return CatalogMapSupport.str(row.getOrDefault(CatalogKeys.SET_ID, row.get("id")));
    }
}
