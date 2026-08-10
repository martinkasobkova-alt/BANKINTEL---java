package cz.bankintel.sources.ecb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * ECB2 discovery katalog — lazy browse podle flowRef a series (SDMX discovery index).
 * Port {@code ecb2_catalog_routes.py}.
 */
@Service
@RequiredArgsConstructor
public class Ecb2CatalogService {

    private final EcbCuratedCatalog catalog;
    private final EcbSeriesAvailabilityService seriesAvailability;

    public Map<String, Object> getBrowseTree() {
        requireDiscovery();
        List<Map<String, Object>> countryChildren = new ArrayList<>();
        for (String code : catalog.sortedCountryCodes()) {
            Map<String, Object> info = catalog.countryInfo(code);
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("path", EcbSeriesAvailabilityService.ECB2_BROWSE_ROOT + " > " + code);
            child.put("name", info.get("name") + " (" + code + ")");
            child.put("children", List.of());
            child.put("sets", List.of());
            child.put("ecb_country", code);
            child.put("ecb_country_lazy", true);
            countryChildren.add(child);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("path", EcbSeriesAvailabilityService.ECB2_BROWSE_ROOT);
        root.put("name", EcbSeriesAvailabilityService.ECB2_BROWSE_ROOT);
        root.put("children", countryChildren);
        root.put("sets", List.of());
        root.put(
                "browse_notice",
                "Země → dataset (BSI = banky, MIR = sazby, …) → skupiny podle typu → ukazatele. Kurátorovaný výběr: ECB (beta).");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", List.of(root));
        out.put("total_sets", 0);
        out.put("browse_mode", "country_flow_letter_discovery");
        out.put("availability_revision", seriesAvailability.ecb2BrowseRevision());
        out.put("ecb_discovery_browse_enabled", true);
        return out;
    }

    public Map<String, Object> getCountryBrowseNode(String country) {
        requireDiscovery();
        String c = catalog.validateCountryCode(country);
        Map<String, Object> node = buildCountryNode(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("country", c);
        out.put("country_node", node);
        out.put("rows", List.of());
        out.put("total", node.getOrDefault("ecb2_discovery_total", 0));
        out.put("flow_buckets", node.getOrDefault("ecb2_flow_buckets", 0));
        out.put("availability_revision", seriesAvailability.ecb2BrowseRevision());
        return out;
    }

    public Map<String, Object> getCountryFlowBrowseNode(String country, String flowRef) {
        requireDiscovery();
        String c = catalog.validateCountryCode(country);
        String fr = validateFlow(flowRef);
        Map<String, Object> node = buildFlowNode(c, fr);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sets =
                node.get("sets") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("country", c);
        out.put("flow", fr);
        out.put("flow_node", node);
        out.put("rows", sets);
        out.put("count", sets.size());
        out.put("direct", Boolean.TRUE.equals(node.get("ecb_flow_direct")));
        out.put("availability_revision", seriesAvailability.ecb2BrowseRevision());
        return out;
    }

    public Map<String, Object> getCountryFlowLetterBrowseNode(
            String country, String flowRef, String letter, int offset, int limit) {
        requireDiscovery();
        String c = catalog.validateCountryCode(country);
        String fr = validateFlow(flowRef);
        String bucket = seriesAvailability.normalizeLetterBucket(letter);
        Map<String, Object> node = buildFlowLetterNode(c, fr, bucket, offset, limit);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sets =
                node.get("sets") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("country", c);
        out.put("flow", fr);
        out.put("letter", bucket);
        out.put("letter_node", node);
        out.put("rows", sets);
        out.put("count", sets.size());
        out.put("total", node.getOrDefault("ecb2_letter_total", 0));
        out.put("offset", offset);
        out.put("limit", limit);
        out.put("availability_revision", seriesAvailability.ecb2BrowseRevision());
        return out;
    }

    public Map<String, Object> legacyCountryLetterRoute() {
        throw new ResponseStatusException(
                HttpStatus.GONE,
                "Použijte země → dataset → písmeno: /api/ecb2/browse-tree/country/{country}/flow/{flow}/letter/{letter}");
    }

    private Map<String, Object> buildCountryNode(String code) {
        Map<String, Object> info = catalog.countryInfo(code);
        FlowChildrenPayload payload = buildCountryFlowChildren(code);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("path", EcbSeriesAvailabilityService.ECB2_BROWSE_ROOT + " > " + code);
        node.put("name", info.get("name") + " (" + code + ")");
        node.put("children", payload.children());
        node.put("sets", List.of());
        node.put("ecb_country", code);
        node.put("ecb2_discovery_total", payload.total());
        node.put("ecb2_discovery_capped", payload.capped());
        node.put("ecb2_flow_buckets", payload.children().size());
        node.put(
                "browse_notice",
                payload.total() > 0
                        ? payload.total()
                                + " ověřených řad pro "
                                + info.get("name")
                                + ". Rozbalte dataset (např. BSI = bilance bank, MIR = sazby), pak skupiny podle typu ukazatele (Loans, Deposits…). U malých datasetů (≤"
                                + seriesAvailability.letterSplitThreshold()
                                + " řad) bez skupin."
                        : "Pro " + info.get("name") + " zatím nejsou v mřížce žádné ověřené řady.");
        return node;
    }

    private Map<String, Object> buildFlowNode(String code, String flow) {
        FlowChildrenPayload countryPayload = buildCountryFlowChildren(code);
        Map<String, Object> match = countryPayload.children().stream()
                .filter(c -> flow.equalsIgnoreCase(String.valueOf(c.get("ecb_flow"))))
                .findFirst()
                .orElse(null);
        int count = match != null ? (int) match.getOrDefault("ecb_flow_count", 0) : 0;
        if (count <= seriesAvailability.letterSplitThreshold()) {
            List<String> ids = seriesAvailability.countryFlowIndex(code).getOrDefault(flow, List.of());
            int total = ids.size();
            List<Map<String, Object>> rows = seriesAvailability.rowsFromSetIds(ids, code, flow, "");
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("path", EcbSeriesAvailabilityService.ECB2_BROWSE_ROOT + " > " + code + " > " + flow);
            node.put("name", match != null ? match.get("name") : seriesAvailability.flowDisplayName(flow));
            node.put("children", List.of());
            node.put("sets", seriesAvailability.rowsToSets(rows));
            node.put("ecb_country", code);
            node.put("ecb_flow", flow);
            node.put("ecb_flow_direct", true);
            node.put("ecb2_letter_total", total);
            node.put("ecb2_discovery_capped", total > seriesAvailability.discoveryCap());
            return node;
        }
        LetterChildrenPayload letterPayload = buildFlowLetterChildren(code, flow);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("path", EcbSeriesAvailabilityService.ECB2_BROWSE_ROOT + " > " + code + " > " + flow);
        node.put("name", match != null ? match.get("name") : seriesAvailability.flowDisplayName(flow));
        node.put("children", letterPayload.children());
        node.put("sets", List.of());
        node.put("ecb_country", code);
        node.put("ecb_flow", flow);
        node.put("ecb_flow_letter_lazy", true);
        node.put("ecb2_letter_total", letterPayload.total());
        node.put(
                "browse_notice",
                letterPayload.total() + " řad v tomto datasetu — rozbalte skupinu podle typu ukazatele (např. Loans, Deposits).");
        return node;
    }

    private Map<String, Object> buildFlowLetterNode(String code, String flow, String bucket, int offset, int limit) {
        List<String> ids = seriesAvailability.flowLetterIndex(code, flow).getOrDefault(bucket, List.of());
        int total = ids.size();
        int off = Math.max(0, offset);
        int lim = Math.max(1, Math.min(seriesAvailability.maxRowsPerLetter(), limit));
        List<String> slice = ids.subList(Math.min(off, total), Math.min(off + lim, total));
        List<Map<String, Object>> rows = seriesAvailability.rowsFromSetIds(slice, code, flow, bucket);
        int shown = rows.size();
        String capNote = shown < total ? " (zobrazeno " + shown + " z " + total + ")" : "";
        String groupLabel = seriesAvailability.groupLabelForBucket(code, flow, bucket);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("path", EcbSeriesAvailabilityService.ECB2_BROWSE_ROOT + " > " + code + " > " + flow + " > " + bucket);
        node.put(
                "name",
                total > 0
                        ? letterFolderDisplayName(bucket, total, groupLabel)
                        : groupLabel + " (" + total + ")");
        node.put("children", List.of());
        node.put("sets", seriesAvailability.rowsToSets(rows));
        node.put("ecb_country", code);
        node.put("ecb_flow", flow);
        node.put("ecb_letter", bucket);
        node.put("ecb_group_label", groupLabel);
        node.put("ecb2_letter_total", total);
        node.put("ecb2_discovery_capped", total > seriesAvailability.discoveryCap());
        if (total > 0) {
            node.put("browse_notice", "Skupina „" + groupLabel + "“ v datasetu " + flow + capNote + ".");
        }
        return node;
    }

    private FlowChildrenPayload buildCountryFlowChildren(String code) {
        Map<String, List<String>> flows = seriesAvailability.countryFlowIndex(code);
        int total = flows.values().stream().mapToInt(List::size).sum();
        boolean capped = total > seriesAvailability.discoveryCap();
        String countryName = String.valueOf(catalog.countryInfo(code).get("name"));
        List<Map<String, Object>> children = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : flows.entrySet()) {
            String flow = entry.getKey();
            int count = entry.getValue().size();
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("path", EcbSeriesAvailabilityService.ECB2_BROWSE_ROOT + " > " + code + " > " + flow);
            child.put("name", seriesAvailability.flowDisplayName(flow) + " (" + count + ")");
            child.put("children", List.of());
            child.put("sets", List.of());
            child.put("ecb_country", code);
            child.put("ecb_country_name", countryName);
            child.put("ecb_flow", flow);
            child.put("ecb_flow_lazy", true);
            child.put("ecb_flow_count", count);
            if (count <= seriesAvailability.letterSplitThreshold()) {
                child.put("ecb_flow_direct", true);
            } else {
                child.put("ecb_flow_letter_lazy", true);
            }
            children.add(child);
        }
        return new FlowChildrenPayload(children, total, capped);
    }

    private LetterChildrenPayload buildFlowLetterChildren(String code, String flow) {
        Map<String, List<String>> buckets = seriesAvailability.flowLetterIndex(code, flow);
        int total = buckets.values().stream().mapToInt(List::size).sum();
        List<Map<String, Object>> children = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : buckets.entrySet()) {
            String groupKey = entry.getKey();
            int count = entry.getValue().size();
            String groupLabel = seriesAvailability.groupLabelForBucket(code, flow, groupKey);
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("path", EcbSeriesAvailabilityService.ECB2_BROWSE_ROOT + " > " + code + " > " + flow + " > " + groupKey);
            child.put("name", letterFolderDisplayName(groupKey, count, groupLabel));
            child.put("children", List.of());
            child.put("sets", List.of());
            child.put("ecb_country", code);
            child.put("ecb_flow", flow);
            child.put("ecb_letter", groupKey);
            child.put("ecb_group_label", groupLabel);
            child.put("ecb_letter_lazy", true);
            child.put("ecb_letter_count", count);
            child.put("browse_notice", "Skupina „" + groupLabel + "“ — " + count + " řad v datasetu " + flow + ".");
            children.add(child);
        }
        return new LetterChildrenPayload(children, total);
    }

    private static String letterFolderDisplayName(String groupKey, int count, String groupLabel) {
        if ("other".equals(groupKey)) {
            return "Ostatní (" + count + ")";
        }
        if ("0-9".equals(groupKey)) {
            return "0–9 (" + count + ")";
        }
        return groupLabel + " (" + count + ")";
    }

    private void requireDiscovery() {
        if (!seriesAvailability.discoveryBrowseEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "ECB 2 vyžaduje data/ecb_series_availability.json (build_ecb_series_availability.py).");
        }
    }

    private static String validateFlow(String flowRef) {
        String fr = flowRef != null ? flowRef.trim().toUpperCase() : "";
        if (fr.isBlank()) {
            throw new IllegalArgumentException("flow_ref");
        }
        return fr;
    }

    private record FlowChildrenPayload(List<Map<String, Object>> children, int total, boolean capped) {}

    private record LetterChildrenPayload(List<Map<String, Object>> children, int total) {}
}
