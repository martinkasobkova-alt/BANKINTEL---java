package cz.bankintel.search.v2.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchV2HoldoutDatasetTest {

    @Test
    @SuppressWarnings("unchecked")
    void holdoutDatasetIsFrozenByChecksum() throws Exception {
        Path path = projectRoot().resolve("evaluation/search_v2_holdout_queries.json");
        assertThat(path).isRegularFile();

        ObjectMapper mapper = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        Map<String, Object> root = mapper.readValue(path.toFile(), Map.class);
        List<Map<String, Object>> queries = (List<Map<String, Object>>) root.get("queries");

        assertThat(queries).hasSizeGreaterThanOrEqualTo(25);
        assertThat(queries).allSatisfy(query -> {
            assertThat(query).containsKeys("query_id", "query", "category", "judgment_type");
            assertThat(String.valueOf(query.get("query"))).isNotBlank();
        });
        String canonical = mapper.writeValueAsString(queries);
        assertThat(sha256(canonical)).isEqualTo(root.get("queries_sha256"));
    }

    private static Path projectRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if ("backend-java".equalsIgnoreCase(String.valueOf(cwd.getFileName())) && cwd.getParent() != null) {
            return cwd.getParent();
        }
        return cwd;
    }

    private static String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
