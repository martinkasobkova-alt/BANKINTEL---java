package cz.bankintel.search.v2.orchestration;

import cz.bankintel.util.BankIntelEnvVars;
import java.util.Locale;
import java.util.Map;

/**
 * PR-9: tiered preview-verification timeout policy.
 *
 * <p>Before PR-9, {@link SearchV2PreviewVerifier} used ONE global timeout
 * ({@code SEARCH_PREVIEW_TIMEOUT_MS}, default 8000 ms) for every source, regardless of how slow or
 * fast that source's own connector genuinely is. The connectors' own documented HTTP timeouts (read
 * directly from their source, not from any stale doc) are wildly different: {@code FredConnector}
 * and {@code EcbConnector} use 30s/35s; {@code ImfConnector}, {@code Data360Connector},
 * {@code OecdConnector} and {@code BisConnector} use 60s/60s/90s/90s; {@code EurostatConnector} uses
 * 120s, {@code AradConnector} uses 600s, and {@code CsuConnector} uses up to 300s across its several
 * calls. This class groups sources into three tiers - FAST/NORMAL/SLOW - directly from those
 * already-documented values (no new numbers are invented here; see the tier assignment below), and
 * lets each tier's preview-verification timeout be configured independently.
 *
 * <p>Disabled by default via {@code SEARCH_PREVIEW_TIERED_TIMEOUT_ENABLED}; when disabled,
 * {@link #resolveMs} returns the caller's {@code globalTimeoutMs} verbatim for every source - the
 * exact same single global timeout as before this PR. When enabled but no per-tier override is
 * configured, every tier still defaults to the same 8000 ms default as before, so turning the flag
 * on alone changes nothing until an operator actually configures a tier differently.
 */
final class SearchV2PreviewTimeoutPolicy {

    enum Tier {
        FAST,
        NORMAL,
        SLOW
    }

    private static final long MIN_MS = 500;
    private static final long MAX_MS = 30_000;
    private static final long DEFAULT_MS = 8_000;

    /**
     * Tier assignment, derived from each connector's own documented HTTP timeout (see class javadoc).
     * FAST: connector timeout &lt;= ~35s. NORMAL: 60-90s. SLOW: &gt;= 120s. Any source not listed here
     * (including sources with no real network call, e.g. {@code worldbank}/{@code commodities}, and
     * {@code stocks}) defaults to NORMAL - the tier whose default value equals the pre-PR-9 global
     * default, i.e. no behavioral change for those sources unless explicitly configured.
     */
    private static final Map<String, Tier> SOURCE_TIERS = Map.ofEntries(
            Map.entry("fred", Tier.FAST),
            Map.entry("ecb2", Tier.FAST),
            Map.entry("imf", Tier.NORMAL),
            Map.entry("data360", Tier.NORMAL),
            Map.entry("oecd4", Tier.NORMAL),
            Map.entry("bis", Tier.NORMAL),
            Map.entry("eurostat", Tier.SLOW),
            Map.entry("arad", Tier.SLOW),
            Map.entry("csu", Tier.SLOW));

    private SearchV2PreviewTimeoutPolicy() {}

    static boolean enabled() {
        return BankIntelEnvVars.isTruthy("SEARCH_PREVIEW_TIERED_TIMEOUT_ENABLED");
    }

    /**
     * Resolves the effective preview-verification timeout for one candidate's source.
     *
     * @param globalTimeoutMs the pre-PR-9 single global timeout (already resolved from
     *     {@code SEARCH_PREVIEW_TIMEOUT_MS} with its existing clamp) - returned verbatim whenever
     *     tiering is disabled, so the disabled path is byte-for-byte identical to before this PR.
     */
    static long resolveMs(String source, long globalTimeoutMs) {
        if (!enabled()) {
            return globalTimeoutMs;
        }
        Tier tier = tierFor(source);
        return clamp(parseLongEnv(envVarFor(tier), DEFAULT_MS));
    }

    static Tier tierFor(String source) {
        String key = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        return SOURCE_TIERS.getOrDefault(key, Tier.NORMAL);
    }

    private static String envVarFor(Tier tier) {
        return "SEARCH_PREVIEW_TIMEOUT_" + tier.name() + "_MS";
    }

    private static long clamp(long value) {
        return Math.max(MIN_MS, Math.min(value, MAX_MS));
    }

    private static long parseLongEnv(String name, long fallback) {
        String raw = BankIntelEnvVars.get(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
