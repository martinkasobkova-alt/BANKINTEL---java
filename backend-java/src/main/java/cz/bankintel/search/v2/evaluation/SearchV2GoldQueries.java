package cz.bankintel.search.v2.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SearchV2GoldQueries {

    private final ObjectMapper objectMapper;

    public List<SearchV2GoldQuery> load() {
        try (InputStream in = SearchV2GoldQueries.class.getResourceAsStream("/search_v2/gold_queries.json")) {
            if (in == null) {
                return List.of();
            }
            return objectMapper.readValue(in, new TypeReference<>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot load Search V2 gold queries: " + ex.getMessage(), ex);
        }
    }
}
