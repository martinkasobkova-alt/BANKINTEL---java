package cz.bankintel.search.v2.schema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record SearchQueryVariant(String text, String role, double weight) {

    public static final Set<String> FIRST_PASS_EXACT_ROLES = Set.of(
            "original_exact", "canonical_name", "exact_alias", "symbol", "translated_exact");

    public boolean firstPassExactRole() {
        return FIRST_PASS_EXACT_ROLES.contains(role);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text);
        out.put("role", role);
        out.put("weight", weight);
        return out;
    }
}
