package cz.bankintel.search.v2.vector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

final class SearchEmbeddingCache implements AutoCloseable {

    private final Connection connection;
    private final PreparedStatement select;
    private final PreparedStatement insert;

    SearchEmbeddingCache(Path path) throws Exception {
        Files.createDirectories(path.toAbsolutePath().normalize().getParent());
        connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath().normalize());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS embedding_cache(
                        content_hash TEXT NOT NULL,
                        model_id TEXT NOT NULL,
                        dimensions INTEGER NOT NULL,
                        vector BLOB NOT NULL,
                        PRIMARY KEY(content_hash, model_id)
                    )
                    """);
        }
        select = connection.prepareStatement(
                "SELECT dimensions, vector FROM embedding_cache WHERE content_hash = ? AND model_id = ?");
        insert = connection.prepareStatement(
                "INSERT OR REPLACE INTO embedding_cache(content_hash, model_id, dimensions, vector) VALUES(?,?,?,?)");
    }

    Optional<float[]> get(String contentHash, String modelId, int expectedDimensions) throws Exception {
        select.setString(1, contentHash);
        select.setString(2, modelId);
        try (ResultSet result = select.executeQuery()) {
            if (!result.next() || result.getInt("dimensions") != expectedDimensions) {
                return Optional.empty();
            }
            return Optional.of(decode(result.getBytes("vector"), expectedDimensions));
        }
    }

    void put(String contentHash, String modelId, float[] vector) throws Exception {
        insert.setString(1, contentHash);
        insert.setString(2, modelId);
        insert.setInt(3, vector.length);
        insert.setBytes(4, encode(vector));
        insert.executeUpdate();
    }

    private static byte[] encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    private static float[] decode(byte[] bytes, int dimensions) {
        if (bytes == null || bytes.length != dimensions * Float.BYTES) {
            throw new IllegalStateException("Invalid cached embedding length");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            out[i] = buffer.getFloat();
        }
        return out;
    }

    @Override
    public void close() throws Exception {
        select.close();
        insert.close();
        connection.close();
    }
}
