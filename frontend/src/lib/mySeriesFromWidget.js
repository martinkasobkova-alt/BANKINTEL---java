/**
 * Sestaví kontext pro uložení řady do „Moje datové řady“ z widgetu / náhledu.
 * Vrací null, pokud nelze bezpečně vyjádřit (např. chybí zdroj + indikátor / X+Y).
 *
 * @param {object} opts
 * @param {string} [opts.viewType]
 * @param {object} [opts.config]
 * @param {object} [opts.data]
 * @param {string} [opts.title]
 * @returns {null | {
 *   title: string,
 *   source: string,
 *   sourceType: string,
 *   sourceSeriesId: string,
 *   resolver_payload: object,
 *   unit: string,
 *   frequency: string,
 * }}
 */
export function buildMySeriesSavePayloadFromWidget({ viewType = "", config = {}, data = {}, title = "" }) {
  const c = config || {};
  const d = data || {};
  const uploadId = String(c.user_upload_id || "").trim();
  const xfU = String(c.x_field || d.x_field || "").trim();
  const yfU = String(c.y_field || d.y_field || "").trim();

  if (uploadId && xfU && yfU) {
    return {
      title: String(title || c.title || "Nahraná řada").trim() || "Nahraná řada",
      source: "Můj upload",
      source_type: "user_upload",
      source_series_id: `${xfU}:${yfU}`,
      resolver_payload: {
        kind: "user_upload",
        user_upload_id: uploadId,
        x_field: xfU,
        y_field: yfU,
        sheet: c.sheet ?? null,
        header_row: c.header_row ?? 1,
      },
      unit: String(c.unit || "").trim(),
      frequency: String(c.frequency || "").trim(),
    };
  }

  const sourceId = String(c.source_id || c.sync_source_id || d.source?.id || d.source_id || "").trim();
  if (!sourceId) return null;

  const sourceType = String(
    c.source_type || d.source?.source_type || d.source_type || viewType.replace("_view", "").replace("_chart", "") || ""
  ).trim();

  const indicatorId = String(
    c.indicator_id || c.series_id || c.selected_indicator || c.ecb_series_key || c.set_id || ""
  ).trim();

  const xf = String(c.x_field || d.x_field || "").trim();
  const yf = String(c.y_field || d.y_field || "").trim();

  if (indicatorId) {
    return {
      title: String(title || c.title || indicatorId).trim() || indicatorId,
      source: String(d.source?.name || c.source_name || sourceType || sourceId).trim() || sourceId,
      source_type: sourceType,
      source_series_id: indicatorId,
      resolver_payload: {
        kind: "source_indicator",
        source_id: sourceId,
        indicator_id: indicatorId,
      },
      unit: String(c.unit || d.unit || "").trim(),
      frequency: String(c.frequency || c.freq || d.frequency || "").trim(),
    };
  }

  if (xf && yf) {
    return {
      title: String(title || c.title || `${xf} / ${yf}`).trim(),
      source: String(d.source?.name || c.source_name || sourceType || sourceId).trim() || sourceId,
      source_type: sourceType,
      source_series_id: `${xf}:${yf}`,
      resolver_payload: {
        kind: "source_indicator",
        source_id: sourceId,
        x_field: xf,
        y_field: yf,
      },
      unit: String(c.unit || "").trim(),
      frequency: String(c.frequency || "").trim(),
    };
  }

  return null;
}

/**
 * Kontext pro „Porovnat s…“ (řada A z grafu — ještě nemusí být uložená).
 * @returns {null | { kind: 'ref', ref: object, label: string }}
 */
export function buildCompareLeftRefFromWidget(args) {
  const payload = buildMySeriesSavePayloadFromWidget(args);
  if (!payload?.resolver_payload) return null;
  const rp = payload.resolver_payload;
  if (rp.kind === "user_upload") {
    return {
      kind: "ref",
      label: payload.title,
      ref: {
        source_id: "",
        indicator_id: "",
        saved_series_id: "",
        x_field: "",
        y_field: "",
        name: payload.title,
        _upload: {
          user_upload_id: rp.user_upload_id,
          x_field: rp.x_field,
          y_field: rp.y_field,
          sheet: rp.sheet,
          header_row: rp.header_row,
        },
      },
    };
  }
  if (rp.kind === "source_indicator" && rp.source_id && (rp.indicator_id || (rp.x_field && rp.y_field))) {
    return {
      kind: "ref",
      label: payload.title,
      ref: {
        source_id: rp.source_id,
        indicator_id: rp.indicator_id || "",
        x_field: rp.x_field || "",
        y_field: rp.y_field || "",
        saved_series_id: "",
        name: payload.title,
      },
    };
  }
  return null;
}
