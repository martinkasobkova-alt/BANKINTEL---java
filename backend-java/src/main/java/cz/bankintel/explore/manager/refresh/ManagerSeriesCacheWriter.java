package cz.bankintel.explore.manager.refresh;

import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import cz.bankintel.explore.manager.ManagerSeriesCacheMongoConnection;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Java-native writer for the {@code manager_series_cache} Mongo collection - the first Mongo
 * write path in this backend ({@link cz.bankintel.explore.manager.ManagerSeriesCacheReader} is
 * read-only). Shares its connection with the reader via {@link ManagerSeriesCacheMongoConnection}
 * rather than opening a second client against the same cluster.
 *
 * <p><b>Match/upsert key is {@code (series_id, geo)}, NOT {@code _id}.</b> The real collection
 * carries TWO overlapping unique indexes discovered during the first live Phase 2 run:
 * {@code manager_series_cache_series_geo_unique} on {@code (series_id, geo)} alone (a legacy,
 * source-agnostic constraint - apparently series_id has always been assumed unique per geo
 * regardless of source) and {@code manager_series_cache_source_series_geo_unique} on
 * {@code (source, series_id, geo)}. Matching by {@code _id} alone let this writer's own
 * "does this exist" check miss pre-existing documents that carry a different {@code _id} scheme,
 * so an upsert would try to INSERT a new document that collided with the stricter
 * {@code (series_id, geo)} index and threw {@code E11000 duplicate key} mid-batch (confirmed
 * live: {@code sts_inpr_m_c29}/PL). Matching by {@code (series_id, geo)} instead always finds and
 * updates the correct pre-existing document regardless of its historical {@code _id}, and can
 * never itself violate either unique index.
 */
@Service
@RequiredArgsConstructor
public class ManagerSeriesCacheWriter {

    private static final Logger log = LoggerFactory.getLogger(ManagerSeriesCacheWriter.class);
    private static final int BATCH_SIZE = 300;

    private final ManagerSeriesCacheMongoConnection mongoConnection;

    /**
     * Upserts {@code docs} into {@code collectionName}, chunked into batches of {@value
     * #BATCH_SIZE}. For each doc, skips the write entirely if an existing document for the same
     * {@code (series_id, geo)} already has a {@code latest_period} that is not older than the
     * incoming one (see {@link #shouldSkipWrite}) - a fetch made with slightly different timing
     * must never regress freshly-cached data back to something older.
     *
     * <p>Each batch's {@code bulkWrite} is unordered (one bad item never blocks the rest of that
     * same batch) and independently try/caught (one batch's failure never aborts the remaining
     * batches) - a single unexpected duplicate must degrade gracefully, not silently drop
     * thousands of already-fetched documents that were never attempted.
     *
     * @return the number of documents actually written (upserted), excluding regression-skips and
     *     any batch that failed outright.
     */
    public int upsertBatch(String collectionName, List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return 0;
        }
        MongoCollection<Document> collection = mongoConnection.collection(collectionName);
        int written = 0;
        for (int from = 0; from < docs.size(); from += BATCH_SIZE) {
            int to = Math.min(docs.size(), from + BATCH_SIZE);
            List<Document> chunk = docs.subList(from, to);
            List<WriteModel<Document>> models = new ArrayList<>();
            for (Document doc : chunk) {
                String seriesId = doc.getString("series_id");
                String geo = doc.getString("geo");
                if (seriesId == null || geo == null) {
                    continue;
                }
                org.bson.conversions.Bson matchFilter = Filters.and(Filters.eq("series_id", seriesId), Filters.eq("geo", geo));
                Document existing = collection.find(matchFilter).first();
                if (existing != null && shouldSkipWrite(existing.getString("latest_period"), doc.getString("latest_period"))) {
                    continue;
                }
                Document withoutId = new Document(doc);
                withoutId.remove("_id");
                models.add(new UpdateOneModel<>(matchFilter, new Document("$set", withoutId), new UpdateOptions().upsert(true)));
            }
            if (models.isEmpty()) {
                continue;
            }
            try {
                BulkWriteResult result = collection.bulkWrite(models, new BulkWriteOptions().ordered(false));
                written += result.getUpserts().size() + result.getModifiedCount();
            } catch (com.mongodb.MongoBulkWriteException ex) {
                // Unordered mode still attempts every operation despite earlier errors, but the
                // driver raises this exception at the end if ANY item failed - the successful
                // ones (typically the overwhelming majority; only genuine duplicates fail) must
                // still be credited, not discarded as if the whole batch was lost.
                BulkWriteResult partial = ex.getWriteResult();
                written += partial.getUpserts().size() + partial.getModifiedCount();
                log.warn(
                        "manager_series_cache batch write had {} error(s) out of {} docs (collection={}): {}",
                        ex.getWriteErrors().size(),
                        models.size(),
                        collectionName,
                        ex.getWriteErrors());
            } catch (Exception ex) {
                log.warn(
                        "manager_series_cache batch write failed ({} of {} docs in this batch, collection={}): {}",
                        models.size(),
                        chunk.size(),
                        collectionName,
                        ex.getMessage());
            }
        }
        return written;
    }

    /**
     * No-regression rule, extracted as a pure function so it's testable without a live Mongo
     * connection: a new fetch whose {@code latest_period} is older than what's already cached
     * must be skipped. Either period being unparseable (not a recognizable year-first period
     * string) never blocks the write - this mirrors the legacy Python writer's own "don't let a
     * missing/malformed period accidentally freeze the cache" behavior.
     */
    static boolean shouldSkipWrite(String existingLatestPeriod, String incomingLatestPeriod) {
        if (existingLatestPeriod == null || existingLatestPeriod.isBlank()) {
            return false;
        }
        if (incomingLatestPeriod == null || incomingLatestPeriod.isBlank()) {
            return true;
        }
        Integer existingIdx = ManagerSeriesCacheDocBuilder.periodToMonthIndex(existingLatestPeriod);
        Integer incomingIdx = ManagerSeriesCacheDocBuilder.periodToMonthIndex(incomingLatestPeriod);
        if (existingIdx == null || incomingIdx == null) {
            return false;
        }
        return incomingIdx < existingIdx;
    }

    /** Ports the indexes {@code services/manager_series_cache.py::ensure_manager_series_cache_indexes}
     * creates, PLUS the {@code (series_id, geo)} unique index discovered live on the real
     * collection (see class javadoc) - the actual match/upsert key this writer uses. Each index
     * is created independently (one already existing under a different name, e.g. on a
     * collection this writer doesn't own the schema of, must never block the rest from being
     * created) - safe to call repeatedly. */
    public void ensureIndexes(String collectionName) {
        MongoCollection<Document> collection = mongoConnection.collection(collectionName);
        createIndexSafely(collection, Indexes.ascending("series_id", "geo"), new IndexOptions().unique(true));
        createIndexSafely(collection, Indexes.ascending("source", "series_id", "geo"), new IndexOptions().unique(true));
        createIndexSafely(collection, Indexes.ascending("segment_id", "geo", "freshness"), new IndexOptions());
        createIndexSafely(collection, Indexes.ascending("segment_id", "geo", "freshness_category"), new IndexOptions());
        createIndexSafely(collection, Indexes.ascending("source", "series_id"), new IndexOptions());
        createIndexSafely(collection, Indexes.descending("latest_period"), new IndexOptions());
        createIndexSafely(collection, Indexes.descending("updated_at"), new IndexOptions());
    }

    private void createIndexSafely(MongoCollection<Document> collection, org.bson.conversions.Bson keys, IndexOptions options) {
        try {
            collection.createIndex(keys, options);
        } catch (Exception ex) {
            log.debug("index already present or in conflict (non-fatal) on {}: {}", collection.getNamespace(), ex.getMessage());
        }
    }
}
