package cz.bankintel.connector;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Sdílené parsování SDMX/BIS/IMF/OECD/Data360 odpovědí pro preview konektory.
 *
 * <p>Používají {@link BisConnector}, {@link ImfConnector}, {@link OecdConnector}, {@link Data360Connector}.
 */
public final class ConnectorParseSupport {

    private static final Set<String> SDMX_COUNTRY_DIM_IDS =
            Set.of("REF_AREA", "COUNTRY", "GEO", "LOCATION", "AREA", "REFAREA");

    private ConnectorParseSupport() {}

    static List<Map<String, Object>> parseBisGenericDataXml(String xmlText) {
        if (xmlText == null || xmlText.isBlank()) {
            return List.of();
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xmlText.getBytes(StandardCharsets.UTF_8)));
            List<Map<String, Object>> rows = new ArrayList<>();
            NodeList seriesNodes = doc.getElementsByTagNameNS("*", "Series");
            for (int i = 0; i < seriesNodes.getLength(); i++) {
                if (!(seriesNodes.item(i) instanceof Element seriesEl)) {
                    continue;
                }
                Map<String, String> seriesDims = collectSeriesKeyValues(seriesEl);
                for (Element obs : findObsElements(seriesEl)) {
                    rows.add(parseObsRow(seriesDims, obs));
                }
            }
            rows.removeIf(row -> string(row.get("TIME_PERIOD")).isBlank() && row.get("value") == null);
            return rows;
        } catch (Exception ex) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> parseImfSdmxDataJson(Map<String, Object> body) {
        if (body == null) {
            return List.of();
        }
        Object dataObj = body.get("data");
        if (!(dataObj instanceof Map<?, ?> dataMapRaw)) {
            return List.of();
        }
        Map<String, Object> data = ConnectorHttpSupport.stringMap(dataMapRaw);

        List<String> timeValues = new ArrayList<>();
        List<Map<String, Object>> seriesDims = List.of();
        Integer countryDimPos = null;

        Object structuresObj = data.get("structures");
        if (structuresObj instanceof List<?> structures && !structures.isEmpty() && structures.get(0) instanceof Map<?, ?> st0Raw) {
            Map<String, Object> st0 = ConnectorHttpSupport.stringMap(st0Raw);
            Object dimsObj = st0.get("dimensions");
            if (dimsObj instanceof Map<?, ?> dimsRaw) {
                Map<String, Object> dims = ConnectorHttpSupport.stringMap(dimsRaw);
                Object obsDimsObj = dims.get("observation");
                if (obsDimsObj instanceof List<?> obsDims) {
                    for (Object dimObj : obsDims) {
                        if (!(dimObj instanceof Map<?, ?> dimRaw)) {
                            continue;
                        }
                        Map<String, Object> dim = ConnectorHttpSupport.stringMap(dimRaw);
                        String dimId = string(dim.get("id")).toUpperCase(Locale.ROOT);
                        if ("TIME_PERIOD".equals(dimId) || "TIME".equals(dimId)) {
                            Object valuesObj = dim.get("values");
                            if (valuesObj instanceof List<?> values) {
                                for (Object valueObj : values) {
                                    if (valueObj instanceof Map<?, ?> valueRaw) {
                                        Map<String, Object> value = ConnectorHttpSupport.stringMap(valueRaw);
                                        String tp = string(value.get("value"));
                                        if (tp.isBlank()) {
                                            tp = string(value.get("id"));
                                        }
                                        if (!tp.isBlank()) {
                                            timeValues.add(tp);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Object seriesDimsObj = dims.get("series");
                if (seriesDimsObj instanceof List<?> sdList) {
                    seriesDims = new ArrayList<>();
                    for (int pos = 0; pos < sdList.size(); pos++) {
                        if (sdList.get(pos) instanceof Map<?, ?> sdRaw) {
                            Map<String, Object> sd = ConnectorHttpSupport.stringMap(sdRaw);
                            seriesDims.add(sd);
                            if (countryDimPos == null) {
                                String did = string(sd.get("id")).toUpperCase(Locale.ROOT);
                                if (SDMX_COUNTRY_DIM_IDS.contains(did)) {
                                    countryDimPos = pos;
                                }
                            }
                        }
                    }
                }
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        Object dataSetsObj = data.get("dataSets");
        if (!(dataSetsObj instanceof List<?> dataSets)) {
            return rows;
        }
        for (Object dsObj : dataSets) {
            if (!(dsObj instanceof Map<?, ?> dsRaw)) {
                continue;
            }
            Map<String, Object> ds = ConnectorHttpSupport.stringMap(dsRaw);
            Object seriesObj = ds.get("series");
            if (seriesObj instanceof Map<?, ?> seriesMap) {
                for (Map.Entry<?, ?> entry : seriesMap.entrySet()) {
                    appendImfSeriesRows(rows, entry.getKey(), entry.getValue(), timeValues, seriesDims, countryDimPos);
                }
            } else if (seriesObj instanceof List<?> seriesList) {
                for (int idx = 0; idx < seriesList.size(); idx++) {
                    appendImfSeriesRows(rows, String.valueOf(idx), seriesList.get(idx), timeValues, seriesDims, countryDimPos);
                }
            }
        }
        rows.sort(Comparator.comparing(r -> string(r.get("date")) + "|" + string(r.get("COUNTRY"))));
        return rows;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> flattenOecdSdmxRecords(Map<String, Object> payload) {
        List<Map<String, Object>> indexed = flattenOecdIndexedObservations(payload);
        if (!indexed.isEmpty()) {
            indexed.sort(Comparator.comparing(r -> string(r.get("TIME_PERIOD"))));
            return indexed;
        }
        List<Map<String, Object>> collected = new ArrayList<>();
        collectOecdObsRecursive(payload, collected);
        collected.sort(Comparator.comparing(r -> string(r.get("TIME_PERIOD"))));
        return collected;
    }

    static List<Map<String, Object>> parseCsvPreviewRows(List<Map<String, Object>> csvRows) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> raw : csvRows) {
            Map<String, Object> clean = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                if (entry.getKey() != null) {
                    clean.put(entry.getKey().trim(), entry.getValue());
                }
            }
            String timeCol = null;
            String valCol = null;
            for (String key : clean.keySet()) {
                String kl = key.toLowerCase(Locale.ROOT);
                if (kl.contains("time") || "period".equals(kl)) {
                    timeCol = key;
                }
                if ("value".equals(kl) || kl.endsWith("value") || "obs_value".equals(kl)) {
                    valCol = key;
                }
            }
            if (timeCol == null) {
                for (String candidate : List.of("TIME", "TIME_PERIOD", "Period")) {
                    if (clean.containsKey(candidate)) {
                        timeCol = candidate;
                        break;
                    }
                }
            }
            if (valCol == null) {
                for (String candidate : List.of("Value", "OBS_VALUE", "ObsValue")) {
                    if (clean.containsKey(candidate)) {
                        valCol = candidate;
                        break;
                    }
                }
            }
            if (timeCol != null && valCol != null) {
                try {
                    double num = Double.parseDouble(String.valueOf(clean.get(valCol)).replace(",", "."));
                    clean.put("date", String.valueOf(clean.get(timeCol)));
                    clean.put("value", num);
                    clean.put("amount", num);
                } catch (NumberFormatException ignored) {
                    // keep raw row
                }
            }
            rows.add(clean);
        }
        return rows;
    }

    static List<Map<String, Object>> parseData360Rows(Map<String, Object> raw) {
        Object valsObj = raw.get("value");
        if (valsObj == null) {
            valsObj = raw.get("Value");
        }
        if (!(valsObj instanceof List<?> vals)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object itemObj : vals) {
            if (!(itemObj instanceof Map<?, ?> itemRaw)) {
                continue;
            }
            Map<String, Object> item = ConnectorHttpSupport.stringMap(itemRaw);
            Map<String, Object> row = new LinkedHashMap<>();
            Object obs = item.get("OBS_VALUE");
            Double numVal = null;
            if (obs != null) {
                try {
                    numVal = Double.parseDouble(String.valueOf(obs));
                } catch (NumberFormatException ignored) {
                    // keep raw
                }
            }
            row.put("observation_value_raw", obs);
            row.put("amount", numVal != null ? numVal : obs);
            row.put("value_num", numVal);
            for (Map.Entry<String, Object> entry : item.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equals(entry.getKey().toUpperCase(Locale.ROOT))) {
                    row.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
                }
            }
            row.put("DATABASE_ID", item.get("DATABASE_ID"));
            row.put("INDICATOR", item.get("INDICATOR"));
            row.put("REF_AREA", item.get("REF_AREA"));
            row.put("TIME_PERIOD", item.get("TIME_PERIOD"));
            row.put("FREQ", item.get("FREQ"));
            Object refArea = item.get("REF_AREA");
            if (refArea != null && !String.valueOf(refArea).isBlank()) {
                row.put("geo", String.valueOf(refArea).trim().toUpperCase(Locale.ROOT));
            }
            rows.add(row);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static void appendImfSeriesRows(
            List<Map<String, Object>> rows,
            Object seriesKey,
            Object seriesObj,
            List<String> timeValues,
            List<Map<String, Object>> seriesDims,
            Integer countryDimPos) {
        if (!(seriesObj instanceof Map<?, ?> serRaw)) {
            return;
        }
        Map<String, Object> ser = ConnectorHttpSupport.stringMap(serRaw);
        String countryCode = "";
        String countryName = "";
        if (countryDimPos != null) {
            String[] parts = String.valueOf(seriesKey).split(":");
            if (countryDimPos < parts.length) {
                try {
                    int vidx = Integer.parseInt(parts[countryDimPos]);
                    if (vidx >= 0 && vidx < seriesDims.size()) {
                        Object valuesObj = seriesDims.get(countryDimPos).get("values");
                        if (valuesObj instanceof List<?> values && vidx < values.size() && values.get(vidx) instanceof Map<?, ?> vRaw) {
                            Map<String, Object> v = ConnectorHttpSupport.stringMap(vRaw);
                            countryCode = string(v.get("id"));
                            if (countryCode.isBlank()) {
                                countryCode = string(v.get("value"));
                            }
                            countryName = string(v.get("name"));
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // skip country enrichment
                }
            }
        }
        Object obsObj = ser.get("observations");
        if (!(obsObj instanceof Map<?, ?> obsMap)) {
            return;
        }
        for (Map.Entry<?, ?> obsEntry : obsMap.entrySet()) {
            int idx;
            try {
                idx = Integer.parseInt(String.valueOf(obsEntry.getKey()));
            } catch (NumberFormatException ex) {
                continue;
            }
            Object obsVal = obsEntry.getValue();
            Object raw = obsVal instanceof List<?> list && !list.isEmpty() ? list.get(0) : obsVal;
            try {
                double num = Double.parseDouble(String.valueOf(raw).replace(",", "."));
                String tp = idx < timeValues.size() ? timeValues.get(idx) : String.valueOf(obsEntry.getKey());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("TIME_PERIOD", tp);
                row.put("date", tp);
                row.put("OBS_VALUE", num);
                row.put("value", num);
                row.put("amount", num);
                if (!countryCode.isBlank()) {
                    row.put("COUNTRY", countryCode);
                    if (!countryName.isBlank()) {
                        row.put("COUNTRY_label", countryName);
                    }
                }
                rows.add(row);
            } catch (NumberFormatException ignored) {
                // skip invalid observation
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> flattenOecdIndexedObservations(Map<String, Object> payload) {
        Object dataObj = payload.get("data");
        if (!(dataObj instanceof Map<?, ?> dataRaw)) {
            return List.of();
        }
        Map<String, Object> data = ConnectorHttpSupport.stringMap(dataRaw);
        Object dataSetsObj = data.get("dataSets");
        Object structuresObj = data.get("structures");
        if (!(dataSetsObj instanceof List<?> dataSets) || dataSets.isEmpty()) {
            return List.of();
        }
        if (!(structuresObj instanceof List<?> structures) || structures.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> collected = new ArrayList<>();
        for (Object dsObj : dataSets) {
            if (!(dsObj instanceof Map<?, ?> dsRaw)) {
                continue;
            }
            Map<String, Object> ds = ConnectorHttpSupport.stringMap(dsRaw);
            int structureIdx = 0;
            Object structureIdxRaw = ds.get("structure");
            if (structureIdxRaw instanceof Number n) {
                structureIdx = n.intValue();
            } else {
                try {
                    structureIdx = Integer.parseInt(String.valueOf(structureIdxRaw));
                } catch (NumberFormatException ignored) {
                    structureIdx = 0;
                }
            }
            if (structureIdx < 0 || structureIdx >= structures.size()) {
                structureIdx = 0;
            }
            if (!(structures.get(structureIdx) instanceof Map<?, ?> structureRaw)) {
                continue;
            }
            Map<String, Object> structure = ConnectorHttpSupport.stringMap(structureRaw);
            Object dimBlockObj = structure.get("dimensions");
            if (!(dimBlockObj instanceof Map<?, ?> dimBlockRaw)) {
                continue;
            }
            Map<String, Object> dimBlock = ConnectorHttpSupport.stringMap(dimBlockRaw);
            Object obsDimsObj = dimBlock.get("observation");
            if (!(obsDimsObj instanceof List<?> obsDims) || obsDims.isEmpty()) {
                continue;
            }
            Object observationsObj = ds.get("observations");
            if (!(observationsObj instanceof Map<?, ?> observations)) {
                continue;
            }
            for (Map.Entry<?, ?> obsEntry : observations.entrySet()) {
                String[] parts = String.valueOf(obsEntry.getKey()).split(":");
                Map<String, Object> row = new LinkedHashMap<>();
                for (int idx = 0; idx < obsDims.size(); idx++) {
                    if (!(obsDims.get(idx) instanceof Map<?, ?> dimRaw)) {
                        continue;
                    }
                    Map<String, Object> dim = ConnectorHttpSupport.stringMap(dimRaw);
                    String dimId = sdmxId(dim);
                    if (dimId.isBlank() || idx >= parts.length) {
                        continue;
                    }
                    String posRaw = parts[idx];
                    if (posRaw.isBlank() || "~".equals(posRaw)) {
                        continue;
                    }
                    int pos;
                    try {
                        pos = Integer.parseInt(posRaw);
                    } catch (NumberFormatException ex) {
                        continue;
                    }
                    Object valuesObj = dim.get("values");
                    if (!(valuesObj instanceof List<?> values) || pos < 0 || pos >= values.size()) {
                        continue;
                    }
                    if (!(values.get(pos) instanceof Map<?, ?> valueRaw)) {
                        continue;
                    }
                    Map<String, Object> value = ConnectorHttpSupport.stringMap(valueRaw);
                    String valueId = sdmxId(value);
                    if (valueId.isBlank()) {
                        continue;
                    }
                    row.put(dimId, valueId);
                    String valueName = sdmxName(value, valueId);
                    if (!valueName.isBlank() && !valueName.equals(valueId)) {
                        row.put(dimId + "_LABEL", valueName);
                    }
                }
                Object numericRaw =
                        obsEntry.getValue() instanceof List<?> list && !list.isEmpty() ? list.get(0) : obsEntry.getValue();
                Object tp = row.get("TIME_PERIOD");
                boolean rowOk = false;
                if (tp != null && !String.valueOf(tp).isBlank()) {
                    row.put("TIME_PERIOD", String.valueOf(tp));
                    row.put("date", String.valueOf(tp));
                }
                try {
                    if (numericRaw != null && !"".equals(String.valueOf(numericRaw)) && !".".equals(String.valueOf(numericRaw))) {
                        double num = Double.parseDouble(String.valueOf(numericRaw).replace(",", "."));
                        row.put("OBS_VALUE", num);
                        row.put("value", num);
                        row.put("amount", num);
                        rowOk = true;
                    } else if (tp != null && !String.valueOf(tp).isBlank()) {
                        rowOk = true;
                    }
                } catch (NumberFormatException ex) {
                    rowOk = tp != null && !String.valueOf(tp).isBlank();
                }
                if (rowOk) {
                    collected.add(row);
                }
            }
        }
        return collected;
    }

    @SuppressWarnings("unchecked")
    private static void collectOecdObsRecursive(Object obj, List<Map<String, Object>> collected) {
        if (obj instanceof Map<?, ?> mapRaw) {
            Map<String, Object> map = ConnectorHttpSupport.stringMap(mapRaw);
            if (map.containsKey("@TIME_PERIOD") || map.containsKey("TIME_PERIOD")) {
                Object tp = map.get("@TIME_PERIOD") != null ? map.get("@TIME_PERIOD") : map.get("TIME_PERIOD");
                Object ov = map.containsKey("@OBS_VALUE") ? map.get("@OBS_VALUE") : map.get("OBS_VALUE");
                Map<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    String kk = entry.getKey();
                    if ("Obs".equals(kk)) {
                        continue;
                    }
                    if (kk.startsWith("@")) {
                        String k2 = kk.substring(1);
                        if (!"OBS_VALUE".equalsIgnoreCase(k2)) {
                            row.put(k2, entry.getValue());
                        }
                    } else if (!"OBS_VALUE".equalsIgnoreCase(kk)) {
                        row.put(kk, entry.getValue());
                    }
                }
                if (tp != null && !String.valueOf(tp).isBlank()) {
                    row.put("TIME_PERIOD", String.valueOf(tp));
                    row.put("date", String.valueOf(tp));
                }
                boolean rowOk = false;
                try {
                    if (ov != null && !"".equals(String.valueOf(ov)) && !".".equals(String.valueOf(ov))) {
                        double num = Double.parseDouble(String.valueOf(ov).replace(",", "."));
                        row.put("OBS_VALUE", num);
                        row.put("value", num);
                        row.put("amount", num);
                        rowOk = true;
                    } else if (tp != null && !String.valueOf(tp).isBlank()) {
                        rowOk = true;
                    }
                } catch (NumberFormatException ex) {
                    rowOk = tp != null && !String.valueOf(tp).isBlank();
                }
                if (rowOk) {
                    collected.add(row);
                }
                return;
            }
            for (Object value : map.values()) {
                collectOecdObsRecursive(value, collected);
            }
        } else if (obj instanceof List<?> list) {
            for (Object item : list) {
                collectOecdObsRecursive(item, collected);
            }
        }
    }

    private static Map<String, String> collectSeriesKeyValues(Element seriesEl) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Element child : childElements(seriesEl)) {
            if (!"SeriesKey".equals(localName(child))) {
                continue;
            }
            for (Element valueEl : childElements(child)) {
                if (!"Value".equals(localName(valueEl))) {
                    continue;
                }
                String id = string(valueEl.getAttribute("id"));
                String val = string(valueEl.getAttribute("value"));
                if (!id.isBlank() && !val.isBlank()) {
                    out.put(id, val);
                }
            }
        }
        return out;
    }

    private static List<Element> findObsElements(Element seriesEl) {
        List<Element> out = new ArrayList<>();
        for (Element child : childElements(seriesEl)) {
            if ("Obs".equals(localName(child))) {
                out.add(child);
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        NodeList all = seriesEl.getElementsByTagNameNS("*", "Obs");
        for (int i = 0; i < all.getLength(); i++) {
            if (all.item(i) instanceof Element el) {
                out.add(el);
            }
        }
        return out;
    }

    private static Map<String, Object> parseObsRow(Map<String, String> seriesDims, Element obsEl) {
        Map<String, Object> row = new LinkedHashMap<>(seriesDims);
        String tp = "";
        String obsValRaw = "";
        for (Element sub : allElements(obsEl)) {
            String tag = localName(sub);
            if ("ObsDimension".equals(tag)) {
                String did = string(sub.getAttribute("id"));
                String dimVal = string(sub.getAttribute("value"));
                if (dimVal.isBlank()) {
                    dimVal = string(sub.getTextContent());
                }
                if (!did.isBlank()) {
                    if (did.toUpperCase(Locale.ROOT).contains("TIME_PERIOD")) {
                        tp = dimVal;
                    }
                } else if (!dimVal.isBlank() && tp.isBlank()) {
                    tp = dimVal;
                }
            } else if ("Time".equals(tag)) {
                String timeVal = obsValueFromElement(sub);
                if (!timeVal.isBlank()) {
                    tp = timeVal;
                }
            } else if ("ObsValue".equals(tag)) {
                obsValRaw = string(sub.getAttribute("value"));
                if (obsValRaw.isBlank()) {
                    obsValRaw = string(sub.getTextContent());
                }
            }
        }
        if (tp.isBlank()) {
            tp = string(seriesDims.get("TIME_PERIOD"));
            if (tp.isBlank()) {
                tp = string(seriesDims.get("TIME"));
            }
        }
        row.put("TIME_PERIOD", tp);
        row.put("date", tp);
        if (!obsValRaw.isBlank() && !".".equals(obsValRaw)) {
            try {
                double num = Double.parseDouble(obsValRaw.replace(",", "."));
                row.put("OBS_VALUE", num);
                row.put("value", num);
                row.put("amount", num);
            } catch (NumberFormatException ex) {
                row.put("OBS_VALUE", obsValRaw);
            }
        }
        return row;
    }

    private static String obsValueFromElement(Element parent) {
        for (Element sub : allElements(parent)) {
            if ("ObsValue".equals(localName(sub))) {
                String val = string(sub.getAttribute("value"));
                if (!val.isBlank()) {
                    return val;
                }
                return string(sub.getTextContent());
            }
        }
        return "";
    }

    private static List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el) {
                out.add(el);
            }
        }
        return out;
    }

    private static List<Element> allElements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element el) {
                out.add(el);
            }
        }
        return out;
    }

    private static String localName(Node node) {
        String tag = node.getNodeName();
        int idx = tag.lastIndexOf(':');
        return idx >= 0 ? tag.substring(idx + 1) : tag;
    }

    private static String sdmxId(Map<String, Object> map) {
        String id = string(map.get("id"));
        return id.isBlank() ? string(map.get("value")) : id;
    }

    private static String sdmxName(Map<String, Object> map, String fallback) {
        Object nameObj = map.get("name");
        if (nameObj instanceof String s && !s.isBlank()) {
            return s;
        }
        return fallback;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
