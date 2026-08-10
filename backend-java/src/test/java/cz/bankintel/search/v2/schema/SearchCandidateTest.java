package cz.bankintel.search.v2.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchCandidateTest {

    @Test
    void exposesLifecycleMetadataInPublicCandidateMap() {
        SearchCandidate candidate = new SearchCandidate(
                "ecb2:CBD:test",
                "test",
                "Historical banking series",
                "",
                "ecb2",
                "CBD",
                "SK",
                "A",
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                "2013",
                1.0,
                "bank income",
                List.of("title"),
                Map.of(
                        "lifecycle_status", "historical",
                        "lifecycle_reason", "dataset_registry_discontinued",
                        "lifecycle_confidence", 1.0));

        Map<String, Object> result = candidate.toMap();

        assertThat(result)
                .containsEntry("lifecycle_status", "historical")
                .containsEntry("lifecycle_reason", "dataset_registry_discontinued")
                .containsEntry("lifecycle_confidence", 1.0);
    }
}
