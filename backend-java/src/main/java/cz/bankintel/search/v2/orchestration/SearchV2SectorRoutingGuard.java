package cz.bankintel.search.v2.orchestration;

import cz.bankintel.search.v2.ontology.SearchV2ConceptRegistry;
import cz.bankintel.search.v2.ontology.SearchV2InstitutionalSectorRegistry;
import cz.bankintel.search.v2.schema.SearchQueryPlan;
import java.util.List;

/**
 * Decides whether concept-driven source routing needs a safety fan-out.
 *
 * <p>Root cause this guards against: the planner must always emit some {@code primary_concepts}, but
 * the concept ontology only models profitability/cost/asset concepts for banks - a query about any
 * other institutional sector (insurance, pension funds...) gets routed via the closest bank-shaped
 * concept (or a raw, unregistered piece of text), which then sends retrieval to bank-only sources.
 * {@link cz.bankintel.search.v2.reranking.SearchV2SemanticValidator} correctly rejects the resulting
 * wrong-sector candidates, but by then nothing right-sector was ever fetched - a routing problem the
 * validator cannot fix after the fact.
 *
 * <p>{@code institutionalSectors} on the plan is a deterministic signal (never LLM-driven, see {@code
 * SearchV2QueryPlanner#institutionalSectorsFor}); {@code impliedSectorForConcepts} derives what
 * sector a *known* concept inherently implies from that concept's own alias text - no new hardcoded
 * concept-to-sector table. Fan-out only triggers on a genuine mismatch or blind spot; it never fires
 * for a bank query correctly routed to bank concepts, so latency/precision there is unchanged.
 *
 * <p><b>{@code conceptUnknown} alone never triggers fan-out.</b> A planner that echoes raw query text
 * back as {@code primary_concepts} (observed live, non-deterministically, even for plain macro
 * queries like "HDP Nemecko") must not by itself force all 11 sources open - that would tax every
 * macro query whenever the planner has an off run, with no sector at stake to justify it. Fan-out for
 * an unknown concept only fires via {@code sectorUnaddressed}, i.e. when the user's own wording ALSO
 * names an explicit institutional sector that nothing in routing addresses. An unknown concept with no
 * detected sector falls through to the existing lexical/vector/default-source fallback unchanged.
 */
public final class SearchV2SectorRoutingGuard {

    private SearchV2SectorRoutingGuard() {}

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    public record Assessment(
            boolean conceptKnown,
            boolean conceptUnknown,
            String impliedSector,
            String detectedSector,
            boolean conceptSectorConflict,
            boolean sectorUnaddressed,
            boolean fanOutTriggered) {

        public String conceptRegistryStatus() {
            if (!conceptKnown && !conceptUnknown) {
                return "empty";
            }
            return conceptKnown ? "known" : "free_metric_intent";
        }
    }

    public static Assessment assess(
            SearchQueryPlan plan,
            SearchV2ConceptRegistry conceptRegistry,
            SearchV2InstitutionalSectorRegistry institutionalSectorRegistry) {
        List<String> primaryConcepts =
                plan == null || plan.primaryConcepts() == null ? List.of() : plan.primaryConcepts();
        List<SearchV2ConceptRegistry.ConceptDefinition> known = conceptRegistry.definitions(primaryConcepts);
        boolean conceptKnown = !primaryConcepts.isEmpty() && !known.isEmpty();
        boolean conceptUnknown = !primaryConcepts.isEmpty() && known.isEmpty();
        String impliedSector = emptyIfNull(institutionalSectorRegistry.impliedSectorForConcepts(known));
        if (impliedSector.isBlank() && plan != null && plan.highConfidenceExactEntity()) {
            impliedSector = emptyIfNull(institutionalSectorRegistry.resolve(plan.entityResolution().catalogFamily()));
        }
        List<String> detected = plan == null ? List.of() : plan.institutionalSectors();
        String detectedSector = detected.isEmpty() ? "" : detected.get(0);
        boolean conceptSectorConflict = !detectedSector.isBlank()
                && !impliedSector.isBlank()
                && !detectedSector.equalsIgnoreCase(impliedSector);
        boolean sectorUnaddressed = !detectedSector.isBlank() && impliedSector.isBlank();
        // conceptUnknown deliberately does NOT appear here on its own - see class javadoc. It already
        // contributes to sectorUnaddressed whenever the query also names an explicit sector (known is
        // empty -> impliedSector is blank), so no case from the original design is lost.
        boolean fanOutTriggered = conceptSectorConflict || sectorUnaddressed;
        return new Assessment(
                conceptKnown,
                conceptUnknown,
                impliedSector,
                detectedSector,
                conceptSectorConflict,
                sectorUnaddressed,
                fanOutTriggered);
    }
}
