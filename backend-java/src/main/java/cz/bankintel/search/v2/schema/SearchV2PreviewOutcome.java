package cz.bankintel.search.v2.schema;

import cz.bankintel.search.model.CatalogMapSupport;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PR-7/PR-7b/PR-7c: the explicit, mutually-exclusive preview verification outcomes for one
 * candidate, and the three response buckets they fall into (verified / unverified / rejected - see
 * {@link #isUnverifiedBucket} and {@link #isRejectedBucket}).
 *
 * <p>Before PR-7, {@code SearchV2Service.resultMapWithPreview} classified every candidate binarily
 * (ok → "verified", everything else → generic "candidate"/"unverified"), which meant a timeout, a
 * cancellation, and a genuine technical failure were indistinguishable downstream. PR-7b further
 * splits the old single {@code FAILED} outcome into {@link #TRANSPORT_FAILURE} (the connector call
 * itself failed - network/HTTP level) and {@link #INTERNAL_FAILURE} (an exception before any
 * transport was even attempted - e.g. connector resolution), and adds the two capacity-management
 * outcomes {@link #CIRCUIT_OPEN} and {@link #BULKHEAD_REJECTED} so PR-8/PR-10's fail-fast admission
 * denials are never conflated with a genuine timeout or connector failure. PR-7c adds {@link #POSSIBLE}
 * to the unverified bucket (an inconclusive candidate is relevant-but-unconfirmed, same as a timeout
 * or a failure - it must never silently disappear either) and renames the old {@code INVALID} outcome
 * to {@link #STRUCTURALLY_INVALID} (see that field's javadoc for why, and for the accepted legacy
 * input alias).
 *
 * <p>{@link #classify} never conflates {@link #TIMEOUT}, {@link #CANCELLED}, {@link #CIRCUIT_OPEN} or
 * {@link #BULKHEAD_REJECTED} with "the data does not exist" - those always keep their own distinct
 * label and belong to the "unverified" bucket: relevant, but not confirmed. Only a confirmed-empty
 * response (successful call, zero rows, no error) or a structurally invalid candidate falls into the
 * "rejected" bucket - see {@link #isRejectedBucket}.
 */
public final class SearchV2PreviewOutcome {

    public static final String VERIFIED = "verified";
    /** Inconclusive for a reason not covered by a more specific outcome below (e.g. a blank/unknown state). */
    public static final String POSSIBLE = "possible";
    public static final String TIMEOUT = "timeout";
    public static final String CANCELLED = "cancelled";
    /** The connector call itself failed (network/HTTP/IO level), after a transport attempt was made. */
    public static final String TRANSPORT_FAILURE = "transport_failure";
    /** The connector/operation explicitly does not support this request. */
    public static final String UNSUPPORTED = "unsupported";
    /** An exception occurred before any transport was even attempted (e.g. connector resolution). */
    public static final String INTERNAL_FAILURE = "internal_failure";
    /** PR-10: the per-connector circuit breaker was open - no attempt was made at all. */
    public static final String CIRCUIT_OPEN = "circuit_open";
    /** PR-8: the per-source bulkhead had no free capacity within the admission budget. */
    public static final String BULKHEAD_REJECTED = "bulkhead_rejected";
    /** Confirmed: the call succeeded and returned zero rows, with no error/unsupported/timeout signal. */
    public static final String EMPTY = "empty";
    /**
     * PR-7c: confirmed - the candidate itself is structurally incomplete before preview was even
     * attempted (missing {@code source} or {@code series_id}). Renamed from the PR-7b name
     * {@code INVALID}, which read as a semantic/business-rule rejection ("this query is invalid")
     * rather than what it actually is: a malformed candidate object that never reached the connector
     * at all. The old wire value {@code "invalid"} is still accepted as an input alias by
     * {@link #classify} (in case it exists in already-persisted telemetry), but this class never
     * produces it - every new status this outcome is derived from must use the canonical
     * {@code "structurally_invalid"} string (see {@code SearchV2PreviewVerifier}).
     */
    public static final String STRUCTURALLY_INVALID = "structurally_invalid";
    /** The candidate was never sent to preview verification at all (e.g. outside the checked window). */
    public static final String NOT_CHECKED = "not_checked";

    private static final Set<String> UNVERIFIED_BUCKET = Set.of(
            POSSIBLE, TIMEOUT, CANCELLED, TRANSPORT_FAILURE, UNSUPPORTED, INTERNAL_FAILURE, CIRCUIT_OPEN,
            BULKHEAD_REJECTED);
    private static final Set<String> REJECTED_BUCKET = Set.of(EMPTY, STRUCTURALLY_INVALID);
    /**
     * PR-10 breaker fix: the only outcomes that ever count as a genuine connector-health failure for
     * {@code SearchV2PreviewCircuitBreaker} - a dispatched request that actually timed out, or one
     * whose transport/HTTP call itself failed (including the {@code sync_failed} shape). Every other
     * non-{@link #VERIFIED} outcome (capacity denials, pre-transport rejections, cancellation,
     * confirmed-empty) is neutral - see {@code SearchV2PreviewVerifier#recordBreakerOutcome}.
     */
    private static final Set<String> BREAKER_FAILURE_BUCKET = Set.of(TIMEOUT, TRANSPORT_FAILURE);

    private SearchV2PreviewOutcome() {}

    /**
     * Classifies a raw preview status map - as produced by {@code SearchV2PreviewVerifier}'s
     * {@code doVerify}/{@code toVerificationStatus}/{@code timeoutStatus}/{@code failureStatus}/
     * {@code circuitOpenStatus}/{@code bulkheadRejectedStatus}/{@code structurallyInvalidStatus} -
     * into exactly one of the outcomes above.
     */
    public static String classify(Map<String, Object> status) {
        if (status == null || status.isEmpty()) {
            return NOT_CHECKED;
        }
        if (Boolean.TRUE.equals(status.get("ok"))) {
            return VERIFIED;
        }
        String state = CatalogMapSupport.str(status.get("preview_state")).trim().toLowerCase(Locale.ROOT);
        switch (state) {
            case "timeout":
                return TIMEOUT;
            case "cancelled":
                return CANCELLED;
            case "circuit_open":
                return CIRCUIT_OPEN;
            case "bulkhead_rejected":
                return BULKHEAD_REJECTED;
            case "error":
            case "sync_failed": // PreviewResponseBuilder.buildError's real production shape for any HTTP-level failure (429/5xx/connection).
                return TRANSPORT_FAILURE;
            case "internal_error":
                return INTERNAL_FAILURE;
            case "unsupported":
                return UNSUPPORTED;
            case "structurally_invalid":
            case "invalid": // PR-7b legacy alias - accepted on input, never produced on output.
                return STRUCTURALLY_INVALID;
            default:
                break;
        }
        return isConfirmedEmpty(status) ? EMPTY : POSSIBLE;
    }

    /** Relevant, but not confirmed - belongs in the {@code unverified} response bucket, never dropped silently. */
    public static boolean isUnverifiedBucket(String outcome) {
        return UNVERIFIED_BUCKET.contains(outcome);
    }

    /** Confirmed absent/invalid - safe to omit from both {@code results} and {@code unverified}. */
    public static boolean isRejectedBucket(String outcome) {
        return REJECTED_BUCKET.contains(outcome);
    }

    /** Whether this outcome counts as a genuine connector-health failure for the circuit breaker (PR-10). */
    public static boolean isBreakerFailure(String outcome) {
        return BREAKER_FAILURE_BUCKET.contains(outcome);
    }

    private static boolean isConfirmedEmpty(Map<String, Object> status) {
        Object rows = status.get("rows");
        return rows instanceof Number number && number.intValue() == 0;
    }
}
