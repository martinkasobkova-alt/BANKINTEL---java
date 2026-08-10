/**
 * Mapování záznamů ze /sources/catalog-stubs na klíče podle katalogu
 * (stejná logika jako dříve v GlobalCatalogSearchPage).
 */

export function buildExistingKeys(def, sources) {
  const out = new Set();
  for (const s of sources || []) {
    if (s.source_type !== def.sourceType) continue;
    if (def.id === "arad" && s.query_params?.set_id) out.add(String(s.query_params.set_id));
    else if (def.id === "eurostat") {
      const k = s.eurostat_dataset_code || (s.endpoint || "").replace(/^\//, "");
      const geo = String(s.query_params?.geo || "").trim().toUpperCase();
      if (k) out.add(geo ? `${String(k)}__${geo}` : String(k));
    } else if (def.id === "csu") {
      const k = s.csu_selection_code || ((s.endpoint || "").split("/").pop() || "").trim();
      if (k) out.add(String(k));
    } else if (def.id === "ecb") {
      const flow = s.ecb_flow || "";
      const key = s.ecb_series_key || "";
      if (flow && key) out.add(`${flow}/${key}`);
    } else if (def.id === "fred" && s.fred_series_id) out.add(String(s.fred_series_id));
    else if (def.id === "worldbank") {
      const k = `${s.wb_indicator || ""}__${s.wb_country || ""}`;
      if (k && k !== "__") out.add(k);
    } else if (def.id === "bis") {
      const canon = (s.bis_catalog_set_id || "").trim();
      if (canon) out.add(String(canon));
      else {
        const flow = s.bis_dataflow || "";
        const key = s.bis_series_key || "";
        if (flow && key) out.add(`${flow}/${key}`);
      }
    } else if (def.id === "imf" || def.id === "imf2") {
      const ds = s.imf_database || "";
      const q = s.imf_series_query || "";
      if (ds && q) out.add(`${ds}/${q}`);
    } else if (def.id === "data360") {
      const ds = s.data360_database_id || "";
      const ind = s.data360_indicator || "";
      const extra = (s.query_params?.REF_AREA || "").trim();
      const base = ds && ind ? `${ds}|${ind}` : "";
      if (base) out.add(extra ? `${base}|${extra}` : base);
    } else if (def.id === "oecd" || def.id === "oecd2" || def.id === "oecd4") {
      const qp = s.query_params || {};
      if (qp.oecd4_key && qp.ref_area && qp.oecd4_measure) {
        out.add(`${qp.oecd4_key}/${qp.ref_area}/${qp.oecd4_measure}/${qp.activity || qp.ACTIVITY || "_"}/${qp.freq || "A"}`);
      } else if (s.oecd_catalog_set_id) {
        out.add(String(s.oecd_catalog_set_id));
      } else {
        const ds = s.oecd_dataset || "";
        const fl = s.oecd_filter || "";
        if (ds && fl) out.add(`${ds}/${fl}`);
      }
    } else if (def.id === "alphavantage" && s.id) {
      out.add(String(s.id));
    } else if (def.id === "commodities") {
      const code = s.query_params?.pink_sheet_code || s.query_params?.commodity_code || "";
      if (code) out.add(String(code));
      else if (s.pink_sheet_code) out.add(String(s.pink_sheet_code));
    }
  }
  return out;
}

export function buildSourceByKey(def, sources) {
  const out = new Map();
  for (const s of sources || []) {
    if (s.source_type !== def.sourceType) continue;
    if (def.id === "arad" && s.query_params?.set_id) out.set(String(s.query_params.set_id), s);
    else if (def.id === "eurostat") {
      const k = s.eurostat_dataset_code || (s.endpoint || "").replace(/^\//, "");
      const geo = String(s.query_params?.geo || "").trim().toUpperCase();
      if (k) out.set(geo ? `${String(k)}__${geo}` : String(k), s);
    } else if (def.id === "csu") {
      const k = s.csu_selection_code || ((s.endpoint || "").split("/").pop() || "").trim();
      if (k) out.set(String(k), s);
    } else if (def.id === "ecb") {
      const flow = s.ecb_flow || "";
      const key = s.ecb_series_key || "";
      if (flow && key) out.set(`${flow}/${key}`, s);
    } else if (def.id === "fred" && s.fred_series_id) out.set(String(s.fred_series_id), s);
    else if (def.id === "worldbank") {
      const k = `${s.wb_indicator || ""}__${s.wb_country || ""}`;
      if (k && k !== "__") out.set(k, s);
    } else if (def.id === "bis") {
      const canon = (s.bis_catalog_set_id || "").trim();
      const flow = s.bis_dataflow || "";
      const key = s.bis_series_key || "";
      const legacy = flow && key ? `${flow}/${key}` : "";
      const k = canon || legacy;
      if (k) out.set(k, s);
    } else if (def.id === "imf" || def.id === "imf2") {
      const ds = s.imf_database || "";
      const q = s.imf_series_query || "";
      if (ds && q) out.set(`${ds}/${q}`, s);
    } else if (def.id === "data360") {
      const ds = s.data360_database_id || "";
      const ind = s.data360_indicator || "";
      const extra = (s.query_params?.REF_AREA || "").trim();
      const base = ds && ind ? `${ds}|${ind}` : "";
      if (base) out.set(extra ? `${base}|${extra}` : base, s);
    } else if (def.id === "oecd" || def.id === "oecd2" || def.id === "oecd4") {
      const qp = s.query_params || {};
      const oecd4Key =
        qp.oecd4_key && qp.ref_area && qp.oecd4_measure
          ? `${qp.oecd4_key}/${qp.ref_area}/${qp.oecd4_measure}/${qp.activity || qp.ACTIVITY || "_"}/${qp.freq || "A"}`
          : "";
      if (oecd4Key) out.set(oecd4Key, s);
      else if (s.oecd_catalog_set_id) out.set(String(s.oecd_catalog_set_id), s);
      else {
        const ds = s.oecd_dataset || "";
        const fl = s.oecd_filter || "";
        if (ds && fl) out.set(`${ds}/${fl}`, s);
      }
    } else if (def.id === "alphavantage" && s.id) {
      out.set(String(s.id), s);
    } else if (def.id === "commodities") {
      const code = s.query_params?.pink_sheet_code || s.query_params?.commodity_code || s.pink_sheet_code || "";
      if (code) out.set(String(code), s);
    }
  }
  return out;
}

export function rowExistingKey(def, row, wbCountry) {
  if (def.id === "eurostat") {
    const geo = String(row?.eurostat_geo || row?.query_params?.geo || "").trim().toUpperCase();
    const sid = String(row?.set_id ?? "");
    return geo ? `${sid}__${geo}` : sid;
  }
  if (def.needsCountry) return `${row.set_id}__${wbCountry}`;
  return String(row.set_id);
}
