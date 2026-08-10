import React, { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Save, Trash2, Upload, FileText, FileSpreadsheet, CheckCircle2, RefreshCw, Play, Eye, AlignLeft, Table as TableIcon, X } from "lucide-react";
import api, { formatApiError, formatApiErrorFromAxios } from "@/lib/api";
import { toast } from "sonner";
import AppShell from "@/components/layout/AppShell";
import SourcePreview from "@/components/sources/SourcePreview";
import { StatusBadge } from "@/components/widgets/WidgetRenderer";
import { effectiveSyncBadgeStatus, buildSyncDetailTooltip } from "@/lib/syncStatus";
import { LoadingInline, LoadingSpinner } from "@/components/ui/loading";
import { buildSourcePreviewParams } from "@/lib/previewRequestParams";

const AUTH_FIELDS = {
  none: [],
  bearer: [{ key: "token", label: "Bearer Token", type: "password" }],
  api_key_header: [
    { key: "api_key", label: "API klíč", type: "password" },
    { key: "header_name", label: "Název hlavičky", type: "text", placeholder: "X-API-Key" },
  ],
  api_key_query: [
    { key: "api_key", label: "API klíč", type: "password" },
    { key: "param_name", label: "Název parametru", type: "text", placeholder: "api_key" },
  ],
  basic: [
    { key: "username", label: "Uživatelské jméno", type: "text" },
    { key: "password", label: "Heslo", type: "password" },
  ],
  custom_header: [],
};

const INITIAL = {
  name: "",
  source_type: "custom",
  base_url: "",
  endpoint: "",
  method: "GET",
  auth_type: "none",
  credentials: {},
  headers: "",
  query_params: "",
  refresh_interval_minutes: 60,
  active: true,
  dataset_name: "",
  excelRecordsJson: "",
  excelGridDirty: false,
  excelStickyManual: false,
};

function kvStringify(obj) {
  if (!obj || typeof obj !== "object") return "";
  return Object.entries(obj).map(([k, v]) => `${k}=${v}`).join("\n");
}
function kvParse(str) {
  const out = {};
  (str || "").split("\n").map((l) => l.trim()).filter(Boolean).forEach((line) => {
    const idx = line.indexOf("=");
    if (idx <= 0) return;
    out[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
  });
  return out;
}

export default function SourceFormPage() {
  const { id } = useParams();
  const isEdit = Boolean(id && id !== "new");
  const nav = useNavigate();
  const [form, setForm] = useState(INITIAL);
  const [loading, setLoading] = useState(isEdit);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState("");

  // Náhled aktuálních dat zdroje (graf + tabulka). Načte se při otevření
  // formuláře v režimu úpravy a polluje se každé 4 s, pokud zrovna běží
  // synchronizace na pozadí — admin tak hned vidí, že nově nastavené
  // parametry vrací relevantní data.
  const [preview, setPreview] = useState(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [sourceStatus, setSourceStatus] = useState(null);
  const [syncing, setSyncing] = useState(false);

  const syncUiStatus = useMemo(
    () => (sourceStatus ? effectiveSyncBadgeStatus(sourceStatus) : null),
    [sourceStatus],
  );

  const previewLimitForSource = (sourceType, indicatorId) => {
    if (indicatorId) return 500;
    const st = String(sourceType || "").trim().toLowerCase();
    if (st === "eurostat") return 500;
    return 80;
  };

  const loadPreview = async (sourceId, indicatorId, indicatorIds = null, geoValues = null, dimensionFilters = null) => {
    setPreviewLoading(true);
    try {
      const sourceType = form?.source_type || sourceStatus?.source_type || "";
      const params = buildSourcePreviewParams({
        sourceType,
        limit: previewLimitForSource(sourceType, indicatorId),
        indicatorId,
        indicatorIds,
        groupField: preview?.group_field || "",
        geoValues,
        dimensionFilters,
      });
      const { data } = await api.get(`/sources/${sourceId}/preview`, { params });
      setPreview(data);
    } catch (e) {
      setPreview({ source: { id: sourceId }, rows: [], fields: [], error: e?.response?.data?.detail || e.message });
    } finally {
      setPreviewLoading(false);
    }
  };

  const refreshStatus = async (sourceId) => {
    try {
      const { data } = await api.get(`/sources/${sourceId}`);
      setSourceStatus(data);
      return data;
    } catch {
      return null;
    }
  };

  useEffect(() => {
    if (!isEdit) return;
    (async () => {
      try {
        const { data } = await api.get(`/sources/${id}`);
        const qp = data.query_params || {};
        const isFile = data.source_type === "file_upload";
        setForm({
          ...INITIAL,
          ...data,
          credentials: {},
          headers: kvStringify(data.headers),
          query_params: kvStringify(data.query_params),
          dataset_name: data.dataset_name || "",
          // file_upload — rozbalíme dílčí parametry z query_params,
          // aby UI mohlo zobrazit aktuální výběr listu / stránek / režimu.
          fileKind: isFile ? (qp.kind || "") : undefined,
          originalName: isFile ? (qp.original_name || "") : undefined,
          fileSheet: isFile ? (qp.sheet || "") : undefined,
          fileHeaderRow: isFile ? (qp.header_row || 1) : undefined,
          pdfMode: isFile ? (qp.pdf_mode || "tables") : undefined,
          pdfPages: isFile ? (qp.pdf_pages || "") : undefined,
          pdfTableIndex: isFile ? (qp.pdf_table_index ?? "") : undefined,
          pdfTextSplit: isFile ? (qp.pdf_text_split || "multi_space") : undefined,
          pdfCustomDelimiter: isFile ? (qp.pdf_custom_delimiter || "") : undefined,
          pdfBbox: isFile ? (qp.pdf_bbox || "") : undefined,
          pdfColumnIndices: isFile ? (qp.pdf_column_indices || "") : undefined,
          pdfManualText: isFile ? (qp.pdf_manual_text || "") : undefined,
          excelRecordsJson: isFile && qp.excel_records_json ? String(qp.excel_records_json) : "",
          excelGridDirty: false,
          excelStickyManual: isFile && !!(qp.excel_records_json && String(qp.excel_records_json).trim()),
        });
        setSourceStatus(data);
        loadPreview(id);
      } finally {
        setLoading(false);
      }
    })();
  }, [id, isEdit]);

  // Polling — když je zdroj ve stavu „running", periodicky obnovujeme
  // status a po dokončení znovu natáhneme náhled, aby admin viděl čerstvá data.
  useEffect(() => {
    if (!isEdit) return undefined;
    if (sourceStatus?.last_sync_status !== "running") return undefined;
    const timer = setInterval(async () => {
      const next = await refreshStatus(id);
      if (next && next.last_sync_status !== "running") {
        loadPreview(id);
        if (next.last_sync_status === "success") {
          window.dispatchEvent(new CustomEvent("banko:datasets-changed"));
        }
      }
    }, 4000);
    return () => clearInterval(timer);
  }, [id, isEdit, sourceStatus?.last_sync_status]);

  const triggerSync = async () => {
    const persisted = sourceStatus?.query_params || {};
    console.log("Running sync for source", { id, name: form.name, ...sourceStatus });
    console.log("source.type", form.source_type);
    console.log("source.file_path", form.endpoint);
    console.log("source.config", persisted);
    setSyncing(true);
    try {
      await api.post(`/sources/${id}/sync`);
      await refreshStatus(id);
    } catch (e) {
      console.warn("sync POST failed", e?.response?.data ?? e?.message);
      toast.error(formatApiErrorFromAxios(e) || "Synchronizaci se nepodařilo spustit.");
    }
    setSyncing(false);
  };

  const authFields = useMemo(() => AUTH_FIELDS[form.auth_type] || [], [form.auth_type]);

  const HELP = {
    arad: {
      title: "ARAD · ČNB REST API",
      lines: [
        "ARAD (ČNB) má od r. 2024 REST API. Pro přístup musíš mít API klíč (v uživatelském účtu ČNB).",
        "Základní URL: https://www.cnb.cz/aradb/api/v1",
        "Endpoint pro data sestavy: /data",
        "Typ autentizace: „API klíč v query parametru“, název parametru: api_key",
        "Query parametry (klíč=hodnota na řádek): set_id=1058   (ID konkrétní sestavy)",
        "Volitelně: lang=CS, period_from=2024-01-01, period_to=2025-12-31",
        "Jak najít set_id: otevři sestavu na www.cnb.cz/arad/ → v URL detailu najdeš set_id nebo selection_id.",
      ],
    },
    eurostat: {
      title: "Eurostat · JSON-stat 2.0 API (zdarma, bez klíče)",
      lines: [
        "Veřejné statistické API EU. Funguje out-of-the-box, žádný klíč nepotřebuješ.",
        "Otestováno: HICP CZ vrátil 348 měsíčních záznamů od r. 1997. Nezaměstnanost CZ přes 21 000 záznamů.",
        'Tip: nejjednodušší je kliknout níže na „▶ Vyplnit ukázku" a uložit. Pak Test → Synch.',
        "Vlastní dataset: najdi kód v https://ec.europa.eu/eurostat/databrowser a dej ho do Endpointu (např. /une_rt_m).",
        "Filtruj přes query parametry (geo=CZ, coicop=CP00, …). Bez filtrů stáhne data za všechny země.",
      ],
    },
    custom: {
      title: "Vlastní API · libovolný REST endpoint (pokročilé)",
      lines: [
        'POZOR · tato volba je pro IT specialisty. Pokud chceš jen nahrát Excel/PDF/OLAP JSON, vyber „Vlastní soubor (Excel/PDF/OLAP)" výše.',
        "Co to dělá: aplikace každých X minut zavolá tvoje URL přes HTTP, vezme JSON odpověď a uloží řádky do databáze.",
        "Co potřebuješ vědět: 1) URL portálu, 2) jaký formát vrací (musí být JSON), 3) jestli chce přihlášení (token, API klíč…).",
        'Příklad zdarma na vyzkoušení (žádný klíč nepotřeba) — viz tlačítko „Vyplnit ukázku" níže.',
        "Data musí být JSON: buď pole objektů [{...}, {...}], nebo objekt s polem `data`/`records`/`items`/`rows`.",
        'Po uložení klikni v seznamu zdrojů na „Test" — okamžitě uvidíš, co API vrátí.',
      ],
    },
    alphavantage: {
      title: "ALPHA VANTAGE — akcie / indexy (časové řady)",
      lines: [
        "Tržní data přes oficiální REST API (function=TIME_SERIES_DAILY, WEEKLY, MONTHLY a adjusted varianty).",
        "Na serveru musí být nastavená proměnná ALPHAVANTAGE_API_KEY (viz https://www.alphavantage.co/support/#api-key) — klíč se neukládá do záznamu zdroje v aplikaci.",
        'Povinné query parametry (klíč=hodnota na řádek): symbol=AAPL   a volitelně function=TIME_SERIES_DAILY, outputsize=compact|full.',
        "Interval synchronizace doporučujeme ≥ 1440 minut (free tier má nízké limity počtu volání).",
        "Dokumentace funkcí: https://www.alphavantage.co/documentation/",
      ],
    },
    file_upload: {
      title: "Vlastní soubor · Excel (.xlsx), PDF nebo OLAP JSON",
      lines: [
        "Nahraj vlastní Excel (.xlsx / .xlsm), PDF nebo OLAP JSON a aplikace ho přečte jako datový zdroj.",
        "Excel: vyber list a řádek s hlavičkami sloupců (výchozí: 1. řádek).",
        "PDF: auto-tabulky (pdfplumber), plain text nebo OCR (Tesseract — nutná instalace na serveru).",
        "OLAP JSON: aplikace rozpozná fact_values a dimenze a převede je do řádků pro grafy a analýzy.",
        "Pokročilé: oblast stránky (bbox), oddělovače sloupců, výběr sloupců, ruční úprava textu po náhledu.",
        "Číselné hodnoty s českým formátem (1 234,56) se převedou na číslo automaticky.",
        "Maximální velikost souboru: 16 MB.",
      ],
    },
  };
  const help = HELP[form.source_type];

  const isFileUpload = form.source_type === "file_upload";

  const submit = async (e) => {
    e.preventDefault();
    setSaving(true);
    setErr("");

    let payload;
    if (isFileUpload) {
      if (!form.endpoint) {
        setErr("Nejprve nahraj soubor (Excel, PDF nebo OLAP JSON).");
        setSaving(false);
        return;
      }
      const queryParams = {
        kind: form.fileKind || "",
        original_name: form.originalName || "",
      };
      if (form.fileSheet) queryParams.sheet = form.fileSheet;
      if (form.fileHeaderRow) queryParams.header_row = String(form.fileHeaderRow);
      if (form.fileKind === "pdf") {
        if (form.pdfMode) queryParams.pdf_mode = form.pdfMode;
        if (form.pdfPages) queryParams.pdf_pages = form.pdfPages;
        if (form.pdfTableIndex !== "" && form.pdfTableIndex !== undefined && form.pdfTableIndex !== null) {
          queryParams.pdf_table_index = String(form.pdfTableIndex);
        }
        queryParams.pdf_text_split = form.pdfTextSplit || "multi_space";
        if (form.pdfCustomDelimiter?.trim()) {
          queryParams.pdf_custom_delimiter = form.pdfCustomDelimiter.trim();
        }
        if (form.pdfBbox?.trim()) {
          queryParams.pdf_bbox = form.pdfBbox.trim();
        }
        if (form.pdfColumnIndices?.trim()) {
          queryParams.pdf_column_indices = form.pdfColumnIndices.trim();
        }
        if (form.pdfManualText?.trim()) {
          queryParams.pdf_manual_text = form.pdfManualText;
        }
      }
      if (
        (form.fileKind === "xlsx" || form.fileKind === "xlsm") &&
        (form.excelGridDirty || form.excelStickyManual) &&
        form.excelRecordsJson &&
        String(form.excelRecordsJson).trim()
      ) {
        queryParams.excel_records_json = form.excelRecordsJson;
      }
      payload = {
        name: form.name,
        source_type: "file_upload",
        base_url: "",
        endpoint: form.endpoint,
        method: "GET",
        auth_type: "none",
        credentials: {},
        headers: {},
        query_params: queryParams,
        refresh_interval_minutes: Number(form.refresh_interval_minutes) || 1440,
        active: !!form.active,
        dataset_name: form.dataset_name || form.name,
      };
    } else if (form.source_type === "alphavantage") {
      payload = {
        name: form.name,
        source_type: "alphavantage",
        base_url: "https://www.alphavantage.co",
        endpoint: "/query",
        method: "GET",
        auth_type: "none",
        credentials: {},
        headers: kvParse(form.headers),
        query_params: kvParse(form.query_params),
        refresh_interval_minutes: Number(form.refresh_interval_minutes) || 1440,
        active: !!form.active,
        dataset_name: form.dataset_name || form.name,
      };
    } else {
      payload = {
        name: form.name,
        source_type: form.source_type,
        base_url: form.base_url || "",
        endpoint: form.endpoint || "",
        method: form.method,
        auth_type: form.auth_type,
        credentials: form.credentials || {},
        headers: kvParse(form.headers),
        query_params: kvParse(form.query_params),
        refresh_interval_minutes: Number(form.refresh_interval_minutes) || 60,
        active: !!form.active,
        dataset_name: form.dataset_name || form.name,
      };
    }

    try {
      if (isEdit) {
        await api.patch(`/sources/${id}`, payload);
        // Po uložení rovnou aktualizujeme stav i náhled, aby admin
        // hned viděl, že po změně parametrů (např. set_id) startuje
        // automatická synchronizace a brzy se zobrazí čerstvá data.
        await refreshStatus(id);
        await loadPreview(id);
      } else {
        await api.post(`/sources`, payload);
        nav("/sources");
      }
    } catch (e) {
      const raw = formatApiError(e.response?.data?.detail) || e.message;
      if (e.response?.status === 401 || /not authenticated/i.test(raw)) {
        setErr("Vypršelo přihlášení. Přihlas se znovu jako administrátor (tlačítko vpravo nahoře) a zkus to znovu.");
      } else {
        setErr(raw);
      }
    }
    setSaving(false);
  };

  const del = async () => {
    if (!window.confirm("Opravdu smazat tento zdroj?")) return;
    try {
      await api.delete(`/sources/${id}`);
      nav("/sources");
    } catch (e) {
      if (e?.response?.status === 401) return;
      setErr(formatApiError(e.response?.data?.detail) || e.message);
    }
  };

  const set = (k, v) => setForm((f) => ({ ...f, [k]: v }));

  return (
    <AppShell
      title={isEdit ? "Úprava zdroje" : "Nový zdroj"}
      subtitle={isEdit ? "Změna konfigurace konektoru" : "Registrace externího datového portálu"}
    >
      {loading ? (
        <div className="text-sm text-slate-500 font-mono">Načítání…</div>
      ) : (
        <div className="w-full flex flex-col items-center gap-6">
        <div className="source-form-panel-scope w-full max-w-3xl rounded-2xl border border-border/80 bg-white text-slate-800 shadow-xl overflow-hidden">
          <div className="flex items-center justify-end gap-2 px-4 py-3 border-b border-border/60 bg-gradient-to-b from-slate-50/95 to-white shrink-0">
            <button
              type="button"
              data-testid="source-form-close"
              aria-label="Zavřít a vrátit se ke zdrojům"
              onClick={() => nav("/sources")}
              className="h-9 w-9 grid place-items-center rounded-xl border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 hover:text-slate-900 shadow-sm"
            >
              <X className="h-5 w-5" strokeWidth={2} />
            </button>
          </div>
        <form onSubmit={submit} data-testid="source-form" className="grid grid-cols-1 md:grid-cols-2 gap-5 p-6 sm:p-8">
          {help && (
            <div className="md:col-span-2 border-l-2 border-slate-900 bg-slate-50 px-4 py-3 rounded-xl">
              <div className="text-[10px] uppercase tracking-[0.14em] text-slate-500 font-semibold">
                Nápověda
              </div>
              <div className="font-serif text-base mt-0.5">{help.title}</div>
              <ul className="mt-2 space-y-1 text-xs text-slate-700 font-mono leading-relaxed">
                {help.lines.map((l, i) => (
                  <li key={i} className="flex gap-2">
                    <span className="text-slate-400">›</span>
                    <span>{l}</span>
                  </li>
                ))}
              </ul>
              {form.source_type === "custom" && (
                <button
                  type="button"
                  data-testid="src-custom-fill-example"
                  onClick={() =>
                    setForm((f) => ({
                      ...f,
                      name: f.name || "Ukázka · JSONPlaceholder posts",
                      source_type: "custom",
                      base_url: "https://jsonplaceholder.typicode.com",
                      endpoint: "/posts",
                      method: "GET",
                      auth_type: "none",
                      credentials: {},
                      headers: "Accept=application/json",
                      query_params: "userId=1",
                      dataset_name: f.dataset_name || "ukazka_posts",
                    }))
                  }
                  className="mt-3 inline-flex items-center gap-2 px-3 h-8 text-xs rounded-sm border border-[hsl(var(--primary))]/40 bg-white text-[hsl(var(--primary-deep))] hover:bg-[hsl(var(--primary-soft))] font-mono"
                >
                  ▶ Vyplnit ukázku (zdarma, bez klíče)
                </button>
              )}
              {form.source_type === "alphavantage" && (
                <button
                  type="button"
                  data-testid="src-alphavantage-fill-example"
                  onClick={() =>
                    setForm((f) => ({
                      ...f,
                      name: f.name || "Alpha Vantage · AAPL (denní)",
                      source_type: "alphavantage",
                      base_url: "https://www.alphavantage.co",
                      endpoint: "/query",
                      method: "GET",
                      auth_type: "none",
                      credentials: {},
                      headers: "",
                      query_params: "symbol=AAPL\nfunction=TIME_SERIES_DAILY\noutputsize=compact",
                      dataset_name: f.dataset_name || "alphavantage_aapl_daily",
                    }))
                  }
                  className="mt-3 inline-flex items-center gap-2 px-3 h-8 text-xs rounded-sm border border-[hsl(var(--primary))]/40 bg-white text-[hsl(var(--primary-deep))] hover:bg-[hsl(var(--primary-soft))] font-mono"
                >
                  ▶ Vyplnit ukázku (AAPL, denní řada)
                </button>
              )}
              {form.source_type === "eurostat" && (
                <button
                  type="button"
                  data-testid="src-eurostat-fill-example"
                  onClick={() =>
                    setForm((f) => ({
                      ...f,
                      name: f.name || "Eurostat · HICP CZ (roční změna)",
                      source_type: "eurostat",
                      base_url: "https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data",
                      endpoint: "/prc_hicp_manr",
                      method: "GET",
                      auth_type: "none",
                      credentials: {},
                      headers: "",
                      query_params: "geo=CZ\ncoicop=CP00\nformat=JSON\nlang=EN",
                      dataset_name: f.dataset_name || "eurostat_hicp_cz",
                    }))
                  }
                  className="mt-3 inline-flex items-center gap-2 px-3 h-8 text-xs rounded-sm border border-[hsl(var(--primary))]/40 bg-white text-[hsl(var(--primary-deep))] hover:bg-[hsl(var(--primary-soft))] font-mono"
                >
                  ▶ Vyplnit ukázku (HICP Česko, žádný klíč nepotřeba)
                </button>
              )}
            </div>
          )}

          <Field label="Název zdroje" col={2}>
            <input data-testid="src-name" required value={form.name} onChange={(e) => set("name", e.target.value)} className="input" />
          </Field>

          <Field label="Typ zdroje">
            <select data-testid="src-type" value={form.source_type} onChange={(e) => set("source_type", e.target.value)} className="input">
              <option value="arad">ČNB - ARAD</option>
              <option value="eurostat">Eurostat</option>
              <option value="alphavantage">ALPHA VANTAGE — akcie / indexy</option>
              <option value="custom">Vlastní API</option>
              <option value="file_upload">Vlastní soubor (Excel / PDF / OLAP)</option>
            </select>
          </Field>

          {!isFileUpload && (
            <Field label="HTTP metoda">
              <select data-testid="src-method" value={form.method} onChange={(e) => set("method", e.target.value)} className="input">
                <option>GET</option>
                <option>POST</option>
              </select>
            </Field>
          )}

          {isFileUpload && (
            <FileUploader form={form} setForm={setForm} />
          )}

          {!isFileUpload && (
            <>
              <Field label="Základní URL" col={2}>
                <input data-testid="src-base-url" value={form.base_url} onChange={(e) => set("base_url", e.target.value)} placeholder="https://api.portal.cz" className="input" />
              </Field>

              <Field label="Endpoint" col={2}>
                <input data-testid="src-endpoint" value={form.endpoint} onChange={(e) => set("endpoint", e.target.value)} placeholder="/v1/data" className="input" />
              </Field>

              <Field label="Typ autentizace">
                <select data-testid="src-auth-type" value={form.auth_type} onChange={(e) => set("auth_type", e.target.value)} className="input">
                  <option value="none">Bez autentizace</option>
                  <option value="bearer">Bearer token</option>
                  <option value="api_key_header">API klíč v hlavičce</option>
                  <option value="api_key_query">API klíč v query parametru</option>
                  <option value="basic">Basic Auth</option>
                  <option value="custom_header">Vlastní hlavičky</option>
                </select>
              </Field>
            </>
          )}

          <Field label={isFileUpload ? "Interval obnovy (minuty, např. 1440 = 1× denně)" : "Interval obnovy (minuty)"}>
            <input data-testid="src-refresh" type="number" min={1} value={form.refresh_interval_minutes} onChange={(e) => set("refresh_interval_minutes", e.target.value)} className="input" />
          </Field>

          {!isFileUpload && authFields.length > 0 && (
            <div className="md:col-span-2 border border-dashed border-border rounded-sm p-5 bg-slate-50/60">
              <div className="text-[10px] uppercase tracking-[0.14em] text-slate-500 font-semibold mb-3">
                Přihlašovací údaje (ukládáno šifrovaně)
              </div>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {authFields.map((f) => (
                  <Field key={f.key} label={f.label}>
                    <input
                      data-testid={`src-cred-${f.key}`}
                      type={f.type}
                      placeholder={f.placeholder || ""}
                      value={form.credentials?.[f.key] || ""}
                      onChange={(e) => set("credentials", { ...form.credentials, [f.key]: e.target.value })}
                      className="input"
                    />
                  </Field>
                ))}
              </div>
            </div>
          )}

          {!isFileUpload && form.auth_type === "custom_header" && (
            <Field label="Vlastní hlavičky (klíč=hodnota na řádek)" col={2}>
              <textarea
                data-testid="src-cred-custom-headers"
                rows={3}
                value={form.credentials?.__raw || ""}
                onChange={(e) => {
                  const raw = e.target.value;
                  set("credentials", { headers: kvParse(raw), __raw: raw });
                }}
                className="input font-mono text-xs"
                placeholder="Authorization=Token abc123&#10;X-Tenant=acme"
              />
            </Field>
          )}

          {!isFileUpload && (
            <>
              <Field label="Další hlavičky (klíč=hodnota na řádek)" col={2}>
                <textarea data-testid="src-headers" rows={2} value={form.headers} onChange={(e) => set("headers", e.target.value)} className="input font-mono text-xs" placeholder="Accept=application/json" />
              </Field>

              <Field label="Query parametry (klíč=hodnota na řádek)" col={2}>
                <textarea data-testid="src-query-params" rows={2} value={form.query_params} onChange={(e) => set("query_params", e.target.value)} className="input font-mono text-xs" placeholder="from=2026-01-01" />
              </Field>
            </>
          )}

          <Field label="Cílová datová sada" col={2}>
            <input data-testid="src-dataset-name" value={form.dataset_name} onChange={(e) => set("dataset_name", e.target.value)} placeholder="Výchozí je název zdroje" className="input" />
          </Field>

          <Field label="Aktivní" col={2}>
            <label className="inline-flex items-center gap-2 text-sm text-slate-700">
              <input data-testid="src-active" type="checkbox" checked={!!form.active} onChange={(e) => set("active", e.target.checked)} />
              Povolit automatickou synchronizaci
            </label>
          </Field>

          {err && (
            <div className="md:col-span-2 border border-destructive/40 bg-destructive/5 text-destructive text-sm p-3 rounded-sm">
              {err}
            </div>
          )}

          <div className="md:col-span-2 flex items-center justify-between pt-4 border-t border-border/60">
            <div>
              {isEdit && (
                <button type="button" data-testid="src-delete-btn" onClick={del} className="flex items-center gap-2 px-3 h-9 text-sm border border-red-200 text-red-700 rounded-xl hover:bg-red-50">
                  <Trash2 className="h-4 w-4" /> Smazat
                </button>
              )}
            </div>
            <button type="submit" data-testid="src-save-btn" disabled={saving} className="btn-mint flex items-center gap-2 px-4 h-9 text-sm disabled:opacity-60">
              <Save className="h-4 w-4" />
              {saving ? "Ukládání…" : "Uložit zdroj"}
            </button>
          </div>
        </form>
        </div>

      {isEdit && !loading && (
        <div
          className="source-form-panel-scope w-full max-w-3xl rounded-2xl border border-border/80 bg-white shadow-sm p-4 sm:p-5 space-y-3"
          data-testid="source-form-preview"
        >
          <div className="flex items-center justify-between gap-3 flex-wrap">
            <div className="flex items-center gap-3 min-w-0">
              <h2 className="text-sm font-semibold text-slate-700 uppercase tracking-[0.12em]">
                Náhled aktuálních dat
              </h2>
              {sourceStatus?.last_sync_status && (
                <span className="scale-90 origin-left inline-block max-w-[220px]" title={buildSyncDetailTooltip(sourceStatus) || undefined}>
                  <StatusBadge status={syncUiStatus} />
                </span>
              )}
            </div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => loadPreview(id)}
                disabled={previewLoading}
                aria-busy={previewLoading ? "true" : undefined}
                className="inline-flex items-center gap-1.5 h-8 px-3 text-xs border border-border rounded-sm bg-white hover:bg-slate-50 disabled:opacity-60"
                title="Znovu načíst náhled"
              >
                {previewLoading ? (
                  <LoadingSpinner suppressAria size="xs" aria-label="" />
                ) : (
                  <RefreshCw className="h-3.5 w-3.5" strokeWidth={1.8} />
                )}
                Načíst znovu
              </button>
              <button
                type="button"
                onClick={triggerSync}
                disabled={syncing || syncUiStatus === "running"}
                aria-busy={syncUiStatus === "running" ? "true" : undefined}
                className="btn-mint inline-flex items-center gap-1.5 h-8 px-3 text-xs disabled:opacity-60"
                title={syncUiStatus === "running" ? "Synchronizace už běží (čerstvá)" : "Spustit synchronizaci"}
              >
                {syncUiStatus === "running" ? (
                  <LoadingSpinner suppressAria size="xs" aria-label="" className="!border-t-white !border-white/30" />
                ) : (
                  <Play className="h-3.5 w-3.5" />
                )}
                {syncUiStatus === "running" ? "Probíhá…" : "Spustit synchronizaci"}
              </button>
            </div>
          </div>
          {sourceStatus?.last_sync_status === "error" && sourceStatus?.last_sync_message ? (
            <p className="text-xs text-red-600 font-mono">{sourceStatus.last_sync_message}</p>
          ) : null}
          <SourcePreview
            preview={preview || { source: { name: form.name } }}
            loading={previewLoading}
            compact
            onIndicatorChange={(indicatorId) => loadPreview(id, indicatorId)}
            onIndicatorSelectionChange={(indicatorIds) => loadPreview(id, "", indicatorIds)}
            onGeoSelectionChange={(geoIds) =>
              loadPreview(
                id,
                preview?.selected_indicator || "",
                preview?.selected_indicators || null,
                geoIds,
              )
            }
            onDimensionFiltersApply={(dimensionFilters) =>
              loadPreview(
                id,
                preview?.selected_indicator || "",
                preview?.selected_indicators || null,
                null,
                dimensionFilters,
              )
            }
          />
        </div>
      )}

        </div>
      )}

      <style>{`.input{width:100%;height:38px;border:1px solid hsl(var(--border));border-radius:0.75rem;padding:0 10px;font-size:13px;font-family:'JetBrains Mono',monospace;background:white;color:hsl(218 28% 14%)}
        textarea.input{height:auto;padding:8px 10px}
        .input:focus{outline:none;box-shadow:0 0 0 1px hsl(var(--ring))}`}</style>
    </AppShell>
  );
}

function Field({ label, col = 1, children }) {
  return (
    <div className={col === 2 ? "md:col-span-2" : ""}>
      <label className="text-[11px] uppercase tracking-[0.1em] text-slate-500 font-medium">{label}</label>
      <div className="mt-1.5">{children}</div>
    </div>
  );
}

function normalizeExcelPreviewRows(rows) {
  if (!Array.isArray(rows)) return [];
  return rows.map((r) =>
    (Array.isArray(r) ? r : [r]).map((c) => (c == null ? "" : String(c))),
  );
}

function gridToExcelRecords(grid, headerRow1) {
  const hr = Math.max(1, parseInt(String(headerRow1), 10) || 1);
  if (!grid.length || hr > grid.length) return [];
  const headerCells = grid[hr - 1] || [];
  const headers = headerCells.map((c, i) => {
    const s = String(c ?? "").trim();
    return s || `col_${i + 1}`;
  });
  const out = [];
  for (let r = hr; r < grid.length; r += 1) {
    const row = grid[r] || [];
    if (row.every((c) => c == null || String(c).trim() === "")) continue;
    const rec = {};
    headers.forEach((h, i) => {
      const v = row[i];
      rec[h] = v == null || String(v).trim() === "" ? null : String(v);
    });
    out.push(rec);
  }
  return out;
}

function FileUploader({ form, setForm }) {
  const inputRef = useRef(null);
  const [uploading, setUploading] = useState(false);
  const [uploadErr, setUploadErr] = useState("");
  const [meta, setMeta] = useState(null);
  const [metaLoading, setMetaLoading] = useState(false);
  const [expandedPage, setExpandedPage] = useState(null);
  const [extractPreview, setExtractPreview] = useState(null);
  const [extractLoading, setExtractLoading] = useState(false);
  const [extractErr, setExtractErr] = useState("");
  const [excelGrid, setExcelGrid] = useState(null);
  const excelBaselineRef = useRef("");
  const excelGridRef = useRef(null);
  const [roundDecimals, setRoundDecimals] = useState("");

  const set = (k, v) => setForm((f) => ({ ...f, [k]: v }));

  const pdfExtractQueryParams = () => {
    const q = {
      header_row: String(form.fileHeaderRow || 1),
      pdf_mode: form.pdfMode || "tables",
      pdf_text_split: form.pdfTextSplit || "multi_space",
    };
    if (form.pdfPages) q.pdf_pages = form.pdfPages;
    if (form.pdfTableIndex !== "" && form.pdfTableIndex !== undefined && form.pdfTableIndex !== null) {
      q.pdf_table_index = String(form.pdfTableIndex);
    }
    if (form.pdfCustomDelimiter?.trim()) q.pdf_custom_delimiter = form.pdfCustomDelimiter.trim();
    if (form.pdfBbox?.trim()) q.pdf_bbox = form.pdfBbox.trim();
    if (form.pdfColumnIndices?.trim()) q.pdf_column_indices = form.pdfColumnIndices.trim();
    if (form.pdfManualText?.trim()) q.pdf_manual_text = form.pdfManualText;
    return q;
  };

  const runPdfExtractPreview = async () => {
    if (!form.endpoint) return;
    setExtractLoading(true);
    setExtractErr("");
    try {
      const { data } = await api.post("/sources/pdf-extract-preview", {
        path: form.endpoint,
        query_params: pdfExtractQueryParams(),
      });
      setExtractPreview(data);
    } catch (e) {
      const d = e?.response?.data?.detail;
      setExtractErr(typeof d === "string" ? d : e.message);
      setExtractPreview(null);
    } finally {
      setExtractLoading(false);
    }
  };

  const onPick = () => inputRef.current?.click();

  const isXlsxKind = form.fileKind === "xlsx" || form.fileKind === "xlsm";
  const isPdfKind = form.fileKind === "pdf";
  const isJsonKind = form.fileKind === "json" || form.fileKind === "olap";

  // Inspekce souboru: Excel znovu při změně listu (delší náhled), PDF/JSON po nahrání / změně cesty.
  useEffect(() => {
    if (!form.endpoint || (!isXlsxKind && !isPdfKind && !isJsonKind)) return;
    let cancelled = false;
    (async () => {
      setMetaLoading(true);
      setUploadErr("");
      try {
        const params = { path: form.endpoint };
        if (isXlsxKind) {
          if (form.fileSheet) params.sheet = form.fileSheet;
          params.max_preview_rows = 8000;
        }
        const { data } = await api.get(`/sources/file-meta`, { params });
        if (!cancelled) setMeta(data?.meta || {});
      } catch (e) {
        if (!cancelled) {
          const detail = e?.response?.data?.detail;
          setUploadErr(typeof detail === "string" ? detail : "Nepodařilo se načíst metadata souboru");
          setMeta({});
        }
      } finally {
        if (!cancelled) setMetaLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [form.endpoint, form.fileSheet, form.fileKind, isXlsxKind, isPdfKind, isJsonKind]);

  useEffect(() => {
    excelGridRef.current = excelGrid;
  }, [excelGrid]);

  useEffect(() => {
    if (!isXlsxKind || !meta?.preview_rows?.length) {
      setExcelGrid(null);
      excelBaselineRef.current = "";
      return;
    }
    const norm = normalizeExcelPreviewRows(meta.preview_rows);
    setExcelGrid(norm);
    excelBaselineRef.current = JSON.stringify(norm);
    setForm((f) => ({ ...f, excelGridDirty: false }));
  }, [isXlsxKind, meta, setForm]);

  const pushExcelGrid = (nextGrid) => {
    setExcelGrid(nextGrid);
    const dirty = JSON.stringify(nextGrid) !== excelBaselineRef.current;
    const records = gridToExcelRecords(nextGrid, form.fileHeaderRow || 1);
    setForm((f) => {
      if (dirty) {
        return {
          ...f,
          excelGridDirty: true,
          excelStickyManual: false,
          excelRecordsJson: JSON.stringify(records),
        };
      }
      return {
        ...f,
        excelGridDirty: false,
        excelRecordsJson: f.excelStickyManual ? f.excelRecordsJson : "",
      };
    });
  };

  // Po změně řádku hlaviček přepočítat export (stejná mřížka, jiná interpretace sloupců).
  useEffect(() => {
    if (!isXlsxKind) return;
    const grid = excelGridRef.current;
    if (!grid || !grid.length) return;
    const dirty = JSON.stringify(grid) !== excelBaselineRef.current;
    if (!dirty && !form.excelStickyManual) return;
    const records = gridToExcelRecords(grid, form.fileHeaderRow || 1);
    setForm((f) => ({
      ...f,
      excelRecordsJson: JSON.stringify(records),
      ...(dirty ? { excelGridDirty: true, excelStickyManual: false } : {}),
    }));
  }, [form.fileHeaderRow, form.excelStickyManual, isXlsxKind, setForm]);

  const onFile = async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setUploading(true);
    setUploadErr("");
    setMeta(null);
    try {
      const fd = new FormData();
      fd.append("file", file);
      const { data } = await api.post("/sources/upload-file", fd, {
        headers: { "Content-Type": "multipart/form-data" },
      });
      const uploadedKind = data.meta?.kind || data.kind;
      setForm((f) => ({
        ...f,
        endpoint: data.path,
        originalName: data.original_name,
        fileKind: uploadedKind,
        fileSheet: data.meta?.preview_sheet || "",
        fileHeaderRow: 1,
        pdfMode: uploadedKind === "pdf" ? "tables" : f.pdfMode,
        pdfPages: "",
        pdfTableIndex: "",
        pdfTextSplit: "multi_space",
        pdfCustomDelimiter: "",
        pdfBbox: "",
        pdfColumnIndices: "",
        pdfManualText: "",
        excelRecordsJson: "",
        excelGridDirty: false,
        excelStickyManual: false,
        name: f.name || data.original_name.replace(/\.[^.]+$/, ""),
        dataset_name: f.dataset_name || data.original_name.replace(/\.[^.]+$/, ""),
      }));
      setMeta(null);
    } catch (err) {
      const detail = err.response?.data?.detail;
      setUploadErr(typeof detail === "string" ? detail : err.message);
    } finally {
      setUploading(false);
      if (inputRef.current) inputRef.current.value = "";
    }
  };

  const isXlsx = isXlsxKind;
  const isPdf = isPdfKind;
  const isJson = isJsonKind;
  const pdfPages = Array.isArray(meta?.pages) ? meta.pages : [];
  const pdfMode = form.pdfMode || "tables";

  // Helpery pro práci s page-range stringem (např. "1-3,5,7"). Ukládáme
  // ho jako prostý string aby se snadno přenášel do query_params.
  const pagesSet = useMemo(() => parsePagesString(form.pdfPages, meta?.page_count), [form.pdfPages, meta?.page_count]);
  const togglePage = (n) => {
    const next = new Set(pagesSet);
    if (next.has(n)) next.delete(n); else next.add(n);
    set("pdfPages", formatPagesString(next, meta?.page_count));
  };
  const allPages = () => set("pdfPages", "");
  const noPages = () => set("pdfPages", "0"); // 0 = explicitně nic (žádná validní stránka)

  return (
    <div className="md:col-span-2 soft-card p-6 flex flex-col gap-4">
      <div className="flex items-center justify-between gap-4">
        <div>
          <div className="text-[11px] uppercase tracking-[0.14em] font-semibold" style={{ color: "hsl(var(--primary))" }}>
            Datový soubor
          </div>
          <div className="text-sm text-slate-600 mt-1">
            {form.endpoint
              ? <>Aktuálně: <span className="font-mono text-[hsl(var(--primary-deep))]">{form.originalName || form.endpoint}</span></>
              : "Nahraj Excel (.xlsx), PDF s tabulkami nebo OLAP JSON."}
          </div>
        </div>
        <button
          type="button"
          onClick={onPick}
          disabled={uploading}
          aria-busy={uploading ? "true" : undefined}
          className="btn-mint flex items-center gap-2 px-4 h-10 text-sm disabled:opacity-60"
        >
          {uploading ? <LoadingSpinner suppressAria size="sm" aria-label="" className="!border-t-white !border-white/30" /> : <Upload className="h-4 w-4" strokeWidth={1.8} />}
          {uploading ? "Nahrávám…" : form.endpoint ? "Nahradit soubor" : "Vybrat soubor"}
        </button>
        <input
          ref={inputRef}
          type="file"
          accept=".xlsx,.xlsm,.pdf,.json,application/pdf,application/json,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          onChange={onFile}
          className="hidden"
          data-testid="src-file-input"
        />
      </div>

      {uploadErr && (
        <div className="text-sm chip-rose px-3 py-2 rounded-md">{uploadErr}</div>
      )}

      {form.endpoint && metaLoading && !meta && (
        <LoadingInline label="Načítám obsah souboru…" size="sm" muted className="py-1 italic" />
      )}

      {form.endpoint && meta && (
        <div className="border border-border/60 rounded-lg p-4 bg-[hsl(205_55%_97%)]">
          <div className="flex items-center gap-2 text-sm font-semibold mb-3" style={{ color: "hsl(var(--primary-deep))" }}>
            {isXlsx ? <FileSpreadsheet className="h-4 w-4" /> : <FileText className="h-4 w-4" />}
            {isXlsx
              ? `Excel · ${meta.sheets?.length || 0} listů`
              : isPdf
                ? `PDF · ${meta.page_count || 0} stránek, ${meta.tables_found || 0} tabulek celkem`
                : isJson
                  ? `${form.fileKind === "olap" || meta.kind === "olap" ? "OLAP kostka" : "JSON"} · ${meta.fact_rows ?? meta.row_count ?? meta.preview_rows?.length ?? 0} řádků`
                  : "Soubor"}
            <CheckCircle2 className="h-4 w-4 ml-auto" style={{ color: "hsl(var(--primary))" }} />
          </div>

          {isXlsx && (meta.sheets?.length || 0) > 0 && (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              <Field label="List (sheet)">
                <select
                  className="input"
                  value={form.fileSheet || meta.preview_sheet || ""}
                  onChange={(e) => set("fileSheet", e.target.value)}
                >
                  {meta.sheets.map((s) => (
                    <option key={s} value={s}>{s}</option>
                  ))}
                </select>
              </Field>
              <Field label="Řádek s hlavičkami (1-based)">
                <input
                  type="number"
                  min={1}
                  className="input"
                  value={form.fileHeaderRow || 1}
                  onChange={(e) => set("fileHeaderRow", e.target.value)}
                />
              </Field>
            </div>
          )}

          {isPdf && (
            <div className="space-y-4">
              <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                <Field label="Režim parsování">
                  <select
                    className="input"
                    value={pdfMode}
                    onChange={(e) => set("pdfMode", e.target.value)}
                  >
                    <option value="tables">Tabulky (auto-detekce)</option>
                    <option value="text">Plain text</option>
                    <option value="ocr">OCR (Tesseract)</option>
                  </select>
                </Field>
                <Field label="Stránky (např. 1-3,5,7)">
                  <input
                    type="text"
                    className="input font-mono"
                    placeholder={`Vše (${meta.page_count || "?"})`}
                    value={form.pdfPages || ""}
                    onChange={(e) => set("pdfPages", e.target.value)}
                  />
                </Field>
                <Field label="Řádek s hlavičkami (1-based)">
                  <input
                    type="number"
                    min={1}
                    className="input"
                    value={form.fileHeaderRow || 1}
                    onChange={(e) => set("fileHeaderRow", e.target.value)}
                  />
                </Field>
              </div>

              {pdfMode === "tables" && (
                <Field label="Index tabulky na stránce (volitelné, 0-based)">
                  <input
                    type="text"
                    className="input font-mono"
                    placeholder="Vše"
                    value={form.pdfTableIndex ?? ""}
                    onChange={(e) => set("pdfTableIndex", e.target.value.replace(/[^\d]/g, ""))}
                  />
                </Field>
              )}

              <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                <Field label="Rozdělení sloupců (text / OCR / ruční vstup)">
                  <select
                    className="input"
                    value={form.pdfTextSplit || "multi_space"}
                    onChange={(e) => set("pdfTextSplit", e.target.value)}
                  >
                    <option value="multi_space">Dvě+ mezery nebo tab</option>
                    <option value="tab">Pouze tabulátor</option>
                    <option value="custom">Vlastní oddělovač</option>
                  </select>
                </Field>
                {(form.pdfTextSplit || "multi_space") === "custom" && (
                  <Field label="Vlastní oddělovač">
                    <input
                      type="text"
                      className="input font-mono"
                      placeholder="např. | nebo ;"
                      value={form.pdfCustomDelimiter || ""}
                      onChange={(e) => set("pdfCustomDelimiter", e.target.value)}
                    />
                  </Field>
                )}
                <Field label="Oblast stránky bbox (volitelně)">
                  <input
                    type="text"
                    className="input font-mono text-xs"
                    placeholder="x0, top, x1, bottom (body PDF)"
                    value={form.pdfBbox || ""}
                    onChange={(e) => set("pdfBbox", e.target.value)}
                  />
                </Field>
              </div>

              <Field label="Výběr sloupců (volitelně, indexy od 0, čárkou)">
                <input
                  type="text"
                  className="input font-mono"
                  placeholder="např. 0,1,3 — prázdné = všechny"
                  value={form.pdfColumnIndices || ""}
                  onChange={(e) => set("pdfColumnIndices", e.target.value)}
                />
              </Field>

              <Field label="Ruční text (přepíše binární extrakci, pokud není prázdný)" col={2}>
                <textarea
                  className="input min-h-[100px] text-xs font-mono"
                  value={form.pdfManualText || ""}
                  onChange={(e) => set("pdfManualText", e.target.value)}
                  placeholder="Prázdné = číst ze souboru. Po „Náhled extrakce“ můžete vložit upravený surový text."
                />
              </Field>

              <div className="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  onClick={runPdfExtractPreview}
                  disabled={!form.endpoint || extractLoading}
                  className="inline-flex items-center gap-1.5 px-3 h-9 rounded-md border border-border/70 bg-white text-xs font-semibold hover:bg-slate-50 disabled:opacity-50"
                >
                  <Eye className="h-3.5 w-3.5" />
                  {extractLoading ? "Náhled…" : "Náhled extrakce"}
                </button>
                <button
                  type="button"
                  onClick={() => extractPreview?.raw_text != null && set("pdfManualText", extractPreview.raw_text)}
                  disabled={!extractPreview?.raw_text}
                  className="px-3 h-9 rounded-md border border-border/70 bg-white text-xs hover:bg-slate-50 disabled:opacity-50"
                >
                  Vložit surový text z náhledu
                </button>
                <button
                  type="button"
                  onClick={() => set("pdfManualText", "")}
                  className="px-3 h-9 rounded-md border border-border/70 bg-white text-xs hover:bg-slate-50"
                >
                  Vymazat ruční text
                </button>
              </div>
              {extractErr && (
                <div className="text-xs chip-rose px-3 py-2 rounded-md">{extractErr}</div>
              )}
              {extractPreview && (
                <div className="rounded-md border border-border/60 bg-white p-3 space-y-2">
                  <div className="text-[11px] font-semibold text-slate-700">
                    Náhled: {extractPreview.row_count} řádků
                    {extractPreview.fields?.length ? ` · ${extractPreview.fields.join(" · ")}` : ""}
                  </div>
                  {extractPreview.truncated_raw && (
                    <div className="text-[11px] text-amber-800">Surový text byl zkrácen na bezpečný limit.</div>
                  )}
                  {Array.isArray(extractPreview.rows_sample) && extractPreview.rows_sample.length > 0 && (
                    <div className="overflow-x-auto max-h-56 overflow-y-auto border border-border/40 rounded">
                      <table className="w-full text-[10px] font-mono">
                        <thead>
                          <tr className="bg-slate-50">
                            {extractPreview.fields.map((h, hi) => (
                              <th key={`${hi}-${h}`} className="text-left px-2 py-1 border-b border-border/50 font-semibold whitespace-nowrap">
                                {h}
                              </th>
                            ))}
                          </tr>
                        </thead>
                        <tbody>
                          {extractPreview.rows_sample.map((row, ri) => (
                            <tr key={ri}>
                              {extractPreview.fields.map((h, hi) => (
                                <td key={`${ri}-${hi}-${h}`} className="px-2 py-1 border-b border-border/30 align-top">
                                  {row[h] == null ? "" : String(row[h])}
                                </td>
                              ))}
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>
              )}

              <div className="rounded-md border border-border/60 bg-white">
                <div className="flex items-center justify-between px-3 py-2 border-b border-border/60 bg-slate-50">
                  <div className="text-[11px] uppercase tracking-[0.14em] text-slate-600 font-semibold flex items-center gap-1.5">
                    {pdfMode === "tables" ? <TableIcon className="h-3.5 w-3.5" /> : <AlignLeft className="h-3.5 w-3.5" />}
                    Stránky souboru
                  </div>
                  <div className="flex items-center gap-1.5 text-[11px]">
                    <button type="button" onClick={allPages} className="px-2 h-6 rounded bg-white border border-border/70 hover:bg-slate-50">Vše</button>
                    <button type="button" onClick={noPages} className="px-2 h-6 rounded bg-white border border-border/70 hover:bg-slate-50">Nic</button>
                  </div>
                </div>
                <div className="max-h-[260px] overflow-auto divide-y divide-border/50">
                  {pdfPages.length === 0 && (
                    <div className="px-3 py-3 text-xs text-slate-500 italic">Inspekce stránek nedostupná.</div>
                  )}
                  {pdfPages.map((p) => {
                    const checked = pagesSet.has(p.page) || (pagesSet.size === 0 && !p.skipped);
                    const tablesLabel = p.skipped
                      ? "Stránka mimo limit inspekce"
                      : p.tables.length > 0
                        ? `${p.tables.length} tab. (${p.tables.map((t) => `${t.rows}×${t.cols}`).join(", ")})`
                        : `${p.text_chars || 0} zn. textu, žádná tabulka`;
                    return (
                      <div key={p.page} className="px-3 py-2 text-xs flex flex-col gap-1.5">
                        <div className="flex items-center gap-2">
                          <input
                            type="checkbox"
                            checked={checked}
                            disabled={p.skipped}
                            onChange={() => togglePage(p.page)}
                            className="h-3.5 w-3.5"
                          />
                          <span className="font-mono font-semibold text-slate-700 w-16">str. {p.page}</span>
                          <span className="flex-1 text-slate-600">{tablesLabel}</span>
                          {!p.skipped && (
                            <button
                              type="button"
                              onClick={() => setExpandedPage(expandedPage === p.page ? null : p.page)}
                              className="inline-flex items-center gap-1 px-2 h-6 rounded border border-border/70 text-[10px] hover:bg-slate-50"
                            >
                              <Eye className="h-3 w-3" />
                              {expandedPage === p.page ? "Skrýt" : "Náhled"}
                            </button>
                          )}
                        </div>
                        {expandedPage === p.page && !p.skipped && (
                          <div className="ml-6 mt-1 space-y-2">
                            {p.tables.length === 0 && p.text_preview && (
                              <pre className="whitespace-pre-wrap text-[10.5px] leading-snug text-slate-700 bg-slate-50 border border-border/40 rounded p-2 max-h-40 overflow-auto">
                                {p.text_preview}
                                {(p.text_chars || 0) > p.text_preview.length ? "…" : ""}
                              </pre>
                            )}
                            {p.tables.map((t) => (
                              <div key={t.index} className="border border-border/50 rounded">
                                <div className="px-2 py-1 bg-slate-50 text-[10px] text-slate-600 font-semibold">
                                  Tabulka #{t.index} · {t.rows}×{t.cols}
                                </div>
                                <div className="overflow-x-auto">
                                  <table className="w-full text-[10.5px] font-mono">
                                    <tbody>
                                      {t.preview.map((row, ri) => (
                                        <tr key={ri} className={ri === 0 ? "bg-[hsl(var(--primary-soft))]/40" : ""}>
                                          {row.map((cell, ci) => (
                                            <td key={ci} className="px-1.5 py-1 border-b border-border/30 align-top">
                                              {cell == null ? "" : String(cell)}
                                            </td>
                                          ))}
                                        </tr>
                                      ))}
                                    </tbody>
                                  </table>
                                </div>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>

              <div className="text-[11px] text-slate-500 italic">
                Tip: U naskenovaných PDF použij <strong>OCR</strong> (na serveru musí být Tesseract, volitelně jazyky
                <span className="font-mono"> ces+eng</span> přes proměnnou <span className="font-mono">TESSERACT_LANG</span>).
                Bbox omezí oblast tabulky (souřadnice jako v pdfplumber). U rozbitého kódování pomůže náhled + úprava
                ručního textu nebo sloupcové dělení.
              </div>
            </div>
          )}

          {isJson && (
            <div className="space-y-3">
              <div className="text-xs text-slate-600">
                {form.fileKind === "olap" || meta.kind === "olap"
                  ? "Rozpoznaná OLAP kostka. Fact table a dimenze se při synchronizaci převedou do řádkového datasetu pro grafy, exporty a AI analýzy."
                  : "Rozpoznaný JSON dataset. Aplikace načte pole objektů nebo objekt s data/records/items/rows."}
              </div>
              {Array.isArray(meta.preview_rows) && meta.preview_rows.length > 0 && (() => {
                const headers = Object.keys(meta.preview_rows[0] || {}).slice(0, 10);
                return (
                  <div className="overflow-auto rounded-lg border border-border/60 bg-white max-h-72">
                    <table className="w-full text-[11px] font-mono">
                      <thead>
                        <tr className="bg-slate-50">
                          {headers.map((h) => (
                            <th key={h} className="text-left px-2 py-1.5 border-b border-border/50 font-semibold whitespace-nowrap">
                              {h}
                            </th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {meta.preview_rows.map((row, ri) => (
                          <tr key={ri}>
                            {headers.map((h) => (
                              <td key={`${ri}-${h}`} className="px-2 py-1.5 border-b border-border/30 align-top whitespace-nowrap">
                                {row?.[h] == null ? "" : String(row[h])}
                              </td>
                            ))}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                );
              })()}
            </div>
          )}

          {!isPdf && isXlsx && excelGrid && excelGrid.length > 0 && (() => {
            const nCols = Math.max(0, ...excelGrid.map((r) => r.length));
            const padRow = (r) => {
              const copy = [...(r || [])];
              while (copy.length < nCols) copy.push("");
              return copy;
            };
            return (
              <div className="mt-4 space-y-2">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="text-[10px] uppercase tracking-[0.14em] text-slate-500 font-semibold">
                    Náhled listu (scroll, úpravy buněk)
                  </div>
                  <div className="flex flex-wrap items-center gap-2 text-[11px]">
                    {meta.preview_truncated && (
                      <span className="text-amber-800 font-medium">
                        Zobrazeno max. {meta.preview_max_rows ?? "?"} řádků — v souboru jich může být víc.
                      </span>
                    )}
                    {form.excelStickyManual && (
                      <span className="text-slate-600">Uložené ruční úpravy z minula (po „Obnovit“ se použije znovu soubor).</span>
                    )}
                    <div className="flex items-center gap-1">
                      <label className="text-[11px] text-slate-500 whitespace-nowrap">Zaokrouhlení:</label>
                      <select
                        value={roundDecimals}
                        onChange={(e) => setRoundDecimals(e.target.value)}
                        className="h-8 rounded-md border border-border/70 bg-white text-[11px] px-1.5 text-slate-700"
                        title="Počet desetinných míst pro zaokrouhlení čísel"
                      >
                        <option value="">— bez změny —</option>
                        <option value="0">0 míst (celá čísla)</option>
                        <option value="1">1 místo</option>
                        <option value="2">2 místa</option>
                        <option value="3">3 místa</option>
                        <option value="4">4 místa</option>
                        <option value="5">5 míst</option>
                      </select>
                      <button
                        type="button"
                        disabled={roundDecimals === ""}
                        className="inline-flex items-center gap-1 px-2.5 h-8 rounded-md border border-border/70 bg-white hover:bg-slate-50 text-slate-700 disabled:opacity-40"
                        title="Zaokrouhlit všechna čísla v tabulce"
                        onClick={() => {
                          const dp = parseInt(roundDecimals, 10);
                          if (isNaN(dp)) return;
                          const next = excelGrid.map((row) =>
                            row.map((cell) => {
                              const s = String(cell ?? "").trim();
                              if (s === "") return cell;
                              const n = Number(s.replace(/\s/g, "").replace(",", "."));
                              if (!isFinite(n) || isNaN(n)) return cell;
                              return String(parseFloat(n.toFixed(dp)));
                            })
                          );
                          pushExcelGrid(next);
                        }}
                      >
                        Použít
                      </button>
                    </div>
                    <button
                      type="button"
                      className="inline-flex items-center gap-1 px-2.5 h-8 rounded-md border border-border/70 bg-white hover:bg-slate-50 text-slate-700"
                      onClick={() => {
                        if (!meta?.preview_rows?.length) return;
                        const norm = normalizeExcelPreviewRows(meta.preview_rows);
                        excelBaselineRef.current = JSON.stringify(norm);
                        setExcelGrid(norm);
                        setForm((f) => ({
                          ...f,
                          excelRecordsJson: "",
                          excelGridDirty: false,
                          excelStickyManual: false,
                        }));
                      }}
                    >
                      <RefreshCw className="h-3.5 w-3.5" />
                      Obnovit ze souboru
                    </button>
                  </div>
                </div>
                <div
                  className="excel-preview-scroll rounded-md border border-border/60 h-[min(70vh,720px)] min-h-[220px] w-full overflow-x-auto overflow-y-scroll bg-white shadow-inner overscroll-y-contain [scrollbar-gutter:stable]"
                >
                  <table className="w-full text-[11px] font-mono border-collapse min-w-max">
                    <thead>
                      <tr>
                        <th className="sticky top-0 left-0 z-30 w-9 bg-slate-100 border border-border/50 p-0 shadow-[1px_1px_0_0_rgba(0,0,0,0.06)]" />
                        {Array.from({ length: nCols }, (_, ci) => (
                          <th
                            key={ci}
                            className="sticky top-0 z-20 bg-slate-100 border border-border/50 p-0 min-w-[5.5rem] shadow-[0_1px_0_0_rgba(0,0,0,0.06)]"
                          >
                            <button
                              type="button"
                              className="w-full h-7 flex items-center justify-center text-rose-600 hover:bg-rose-50"
                              title="Smazat sloupec"
                              onClick={() => {
                                const next = excelGrid.map((row) => row.filter((_, j) => j !== ci));
                                pushExcelGrid(next);
                              }}
                            >
                              <X className="h-3.5 w-3.5" />
                            </button>
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {excelGrid.map((row, ri) => (
                        <tr key={ri}>
                          <td className="sticky left-0 z-10 bg-slate-50 border border-border/50 p-0 align-middle shadow-[1px_0_0_0_rgba(0,0,0,0.06)]">
                            <button
                              type="button"
                              className="w-9 min-h-[2rem] flex items-center justify-center text-rose-600 hover:bg-rose-50"
                              title="Smazat řádek"
                              onClick={() => {
                                const next = excelGrid.filter((_, i) => i !== ri);
                                pushExcelGrid(next);
                              }}
                            >
                              <X className="h-3.5 w-3.5" />
                            </button>
                          </td>
                          {padRow(row).map((cell, ci) => (
                            <td key={ci} className="border border-border/40 p-0 align-top bg-white">
                              <input
                                className="w-full min-w-[4.5rem] px-2 py-1.5 text-[11px] font-mono border-0 bg-transparent focus:bg-[hsl(var(--primary-soft))]/25 focus:outline-none focus:ring-1 focus:ring-inset focus:ring-[hsl(var(--ring))]"
                                value={cell}
                                onChange={(e) => {
                                  const v = e.target.value;
                                  const next = excelGrid.map((r, i) => {
                                    if (i !== ri) return r;
                                    const pr = padRow(r);
                                    pr[ci] = v;
                                    return pr;
                                  });
                                  pushExcelGrid(next);
                                }}
                              />
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <p className="text-[10px] text-slate-500 leading-snug">
                  Řádek s hlavičkami výše určuje názvy polí při synchronizaci. Po úpravách uložte zdroj — při změně mřížky se místo celého Excelu odešlou upravená data z náhledu (max. počet řádků jako v náhledu).
                  {meta.preview_truncated ? " Proto u zkráceného náhledu může synchronizace obsahovat jen viditelnou část — upravte soubor a nahrajte znovu, nebo zvyšte limit na serveru." : ""}
                </p>
              </div>
            );
          })()}
        </div>
      )}
    </div>
  );
}

// Pomocné funkce pro práci s page-range stringem ("1-3,5,7" ↔ Set<number>).
// Vrací prázdný Set pokud je string prázdný = "vše vybráno" (uložíme prázdno).
function parsePagesString(spec, pageCount) {
  const out = new Set();
  if (!spec) return out;
  const max = Number(pageCount) || Infinity;
  String(spec).split(/[,;]/).forEach((part) => {
    const chunk = part.trim();
    if (!chunk) return;
    const m = chunk.match(/^(\d+)\s*-\s*(\d+)$/);
    if (m) {
      let a = parseInt(m[1], 10);
      let b = parseInt(m[2], 10);
      if (a > b) [a, b] = [b, a];
      for (let n = a; n <= b; n++) {
        if (n >= 1 && n <= max) out.add(n);
      }
    } else if (/^\d+$/.test(chunk)) {
      const n = parseInt(chunk, 10);
      if (n >= 1 && n <= max) out.add(n);
    }
  });
  return out;
}
function formatPagesString(set, pageCount) {
  const arr = [...set].sort((a, b) => a - b);
  if (arr.length === 0) return "0"; // explicitní „nic" — vyhne se ambivalenci s „vše"
  if (pageCount && arr.length === pageCount) return ""; // všechny vybrané ⇒ smaž string
  const ranges = [];
  let start = arr[0], prev = arr[0];
  for (let i = 1; i < arr.length; i++) {
    if (arr[i] === prev + 1) { prev = arr[i]; continue; }
    ranges.push(start === prev ? `${start}` : `${start}-${prev}`);
    start = arr[i]; prev = arr[i];
  }
  ranges.push(start === prev ? `${start}` : `${start}-${prev}`);
  return ranges.join(",");
}
