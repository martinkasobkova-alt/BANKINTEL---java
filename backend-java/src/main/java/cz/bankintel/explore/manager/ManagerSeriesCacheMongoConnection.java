package cz.bankintel.explore.manager;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import cz.bankintel.util.BankIntelEnvVars;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.Document;
import org.springframework.stereotype.Service;

/**
 * Shared, lazily-created Mongo client for the {@code manager_series_cache} database - the
 * single connection point both {@link ManagerSeriesCacheReader} (read-only) and {@link
 * cz.bankintel.explore.manager.refresh.ManagerSeriesCacheWriter} (the Java-native Eurostat
 * refresh job) use, so the two never open a second independent connection pool against the
 * same Mongo cluster.
 */
@Service
public class ManagerSeriesCacheMongoConnection {

    private final AtomicReference<MongoClient> clientRef = new AtomicReference<>();

    public boolean isAvailable() {
        return !resolveMongoUrl().isBlank();
    }

    /** @param collectionName defaults to {@code "manager_series_cache"} in production; Phase-1
     * verification overrides this to a shadow collection via {@code
     * MANAGER_SERIES_CACHE_COLLECTION_OVERRIDE} at the call site, never here - this class stays
     * a plain connection resolver with no refresh-job-specific knowledge. */
    public MongoCollection<Document> collection(String collectionName) {
        String url = resolveMongoUrl();
        MongoClient client = clientRef.updateAndGet(existing -> {
            if (existing != null) {
                return existing;
            }
            MongoClientSettings settings =
                    MongoClientSettings.builder().applyConnectionString(new ConnectionString(url)).build();
            return MongoClients.create(settings);
        });
        ConnectionString cs = new ConnectionString(url);
        String dbName = cs.getDatabase() != null ? cs.getDatabase() : "bankovnictvi";
        return client.getDatabase(dbName).getCollection(collectionName);
    }

    private static String resolveMongoUrl() {
        String url = BankIntelEnvVars.get("MONGO_URL");
        if (url.isBlank()) {
            url = BankIntelEnvVars.get("MONGODB_URI");
        }
        return url;
    }
}
