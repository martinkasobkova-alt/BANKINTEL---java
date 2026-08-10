import React, { useCallback, useEffect, useMemo, useState } from "react";
import { createPortal } from "react-dom";
import { Link } from "react-router-dom";
import { Plus, X, Settings2 } from "lucide-react";
import { toast } from "sonner";
import api, { formatApiError, formatApiErrorFromAxios } from "@/lib/api";
import { useAuth } from "@/contexts/AuthContext";
import { WIDGET_TYPES, createEmptyWidget } from "@/lib/widgetCatalog";
import PersonalCatalogChartForm from "@/components/myDashboard/PersonalCatalogChartForm";
import PersonalUploadChartForm from "@/components/myDashboard/PersonalUploadChartForm";
import PersonalComputedInlineForm from "@/components/myDashboard/PersonalComputedInlineForm";
import AdConfigEditor from "@/components/editor/AdConfigEditor";

const EMPTY_AD_DRAFT = Object.freeze({
  kind: "image",
  ad_kind: "image",
  image_mode: "single",
  image_url: "",
  link_url: "",
  alt: "",
  content: "",
  html: "",
  slides: [],
  carousel_interval_sec: 5,
  ad_image_object_fit: "cover",
  ad_image_pos_x: 50,
  ad_image_pos_y: 50,
  ad_image_zoom_pct: 100,
});

/**
 * Plovoucí „+“ pro přihlášeného admina.
 *
 * Režimy:
 *   - `mode="homepage"` / `mode="section"` — na veřejné stránce. S `inlinePanel`
 *     se zobrazí formulářové UX 1:1 jako na osobním dashboardu („Můj dashboard"):
 *
 *       - Text / poznámka       → jednoduchý textový widget (markdown)
 *       - Datový graf / tabulka → PersonalCatalogChartForm (katalog ARAD/ČSÚ/…; na widgetu přepnutí graf↔tabulka)
 *       - Výpočtový graf        → PersonalComputedInlineForm + výběr výpočtu
 *       - Graf z mých dat       → PersonalUploadChartForm (upload + sloupce)
 *       - RSS monitoring        → výběr feedů + filtrů
 *       - Inzerce               → reklamní prostor (bez titulku)
 *
 *     Po aplikaci se config namapuje na engine type (arad_view, eurostat_view,
 *     external_catalog_chart, computed_view, user_upload_chart, rss_monitoring,
 *     markdown, ad) a uloží přes /homepage/config nebo PATCH /sections/{id}.
 *
 *   - `mode="editor"` — na admin editoru: žádné API volání, jen onAddLocal.
 */
export default function AdminQuickAddWidget({
  mode,
  sectionSlug,
  sectionId,
  sectionPageId = null,
  onAdded,
  onAddLocal,
  inlinePanel = false,
  /** Na prázdné podstránce sekce rovnou otevře inline panel (admin nemusí hledat plovoucí +). */
  autoExpandInlinePanel = false,
}) {
  const { user, isAdmin, ready } = useAuth();
  const [open, setOpen] = useState(false);
  /** True = panel z plovoucího „+“ — zobrazit v portálu uprostřed obrazovky, ne nahoře v dokumentu. */
  const [openedViaFab, setOpenedViaFab] = useState(false);
  const [busy, setBusy] = useState(false);

  // ---- formulářový stav (jako MyDashboardPage) ----
  const FORM_OPTIONS = useMemo(
    () => [
      { value: "text", label: "Text / poznámka" },
      { value: "chart", label: "Datový graf / tabulka (z katalogu)" },
      { value: "computed_chart", label: "Výpočtový graf" },
      { value: "uploaded_data_chart", label: "Graf z mých dat" },
      { value: "rss_monitoring", label: "RSS monitoring (novinky)" },
      { value: "ad", label: "Inzerce · reklamní prostor (bez titulku)" },
    ],
    []
  );
  const [selectedType, setSelectedType] = useState("text");
  const [draftTitle, setDraftTitle] = useState("");
  const [draftDescription, setDraftDescription] = useState("");
  const [draftTextBody, setDraftTextBody] = useState("");
  const [adDraft, setAdDraft] = useState(() => ({ ...EMPTY_AD_DRAFT }));

  // computed
  const [computedList, setComputedList] = useState([]);
  const [computedId, setComputedId] = useState("");
  const [computedChartType, setComputedChartType] = useState("line");
  const [computedLimit, setComputedLimit] = useState(0);

  // uploads
  const [uploads, setUploads] = useState([]);

  // rss
  const [rssFeeds, setRssFeeds] = useState([]);
  const [rssSelected, setRssSelected] = useState([]);
  const [rssItemLimit, setRssItemLimit] = useState(15);
  const [rssDays, setRssDays] = useState("");
  const [rssQ, setRssQ] = useState("");

  /** Pro režim seznamu (modal / non-inline) — text + inzerce + raw widget typy. */
  const typesOrdered = useMemo(() => {
    const list = [...WIDGET_TYPES];
    const md = list.find((t) => t.value === "markdown");
    const ad = list.find((t) => t.value === "ad");
    const rest = list.filter((t) => t.value !== "markdown" && t.value !== "ad");
    return [md, ad, ...rest].filter(Boolean);
  }, []);

  useEffect(() => {
    const canInline =
      (mode === "homepage" || mode === "section") && inlinePanel && mode !== "editor";
    if (!canInline || !autoExpandInlinePanel) return;
    setOpen(true);
  }, [mode, inlinePanel, autoExpandInlinePanel, sectionPageId]);

  useEffect(() => {
    if (!open) {
      setOpenedViaFab(false);
      return;
    }
    setSelectedType("text");
    setDraftTitle("");
    setDraftDescription("");
    setDraftTextBody("");
    setAdDraft({ ...EMPTY_AD_DRAFT });
    setComputedId("");
    setComputedChartType("line");
    setComputedLimit(0);
    setRssSelected([]);
    setRssQ("");
    setRssDays("");
    setRssItemLimit(15);
  }, [open, mode]);

  const loadComputedList = useCallback(async () => {
    try {
      const { data } = await api.get("/computed");
      const list = Array.isArray(data) ? data : [];
      setComputedList(list);
      setComputedId((prev) => {
        // Neztratit už vybraný/vytvořený výpočet při asynchronním refreshi seznamu.
        if (prev) return prev;
        return list[0]?.id || "";
      });
    } catch {
      setComputedList([]);
      // Zachovat aktuální volbu i při dočasném selhání načtení seznamu.
    }
  }, []);

  const loadUploads = useCallback(async () => {
    try {
      const { data } = await api.get("/me/uploads");
      setUploads(Array.isArray(data) ? data : []);
    } catch {
      setUploads([]);
    }
  }, []);

  const loadRssFeeds = useCallback(async () => {
    try {
      const { data } = await api.get("/rss/feeds");
      setRssFeeds(Array.isArray(data) ? data : []);
    } catch {
      setRssFeeds([]);
    }
  }, []);

  useEffect(() => {
    if (!open) return;
    if (selectedType === "computed_chart") loadComputedList();
    if (selectedType === "uploaded_data_chart") loadUploads();
    if (selectedType === "rss_monitoring") loadRssFeeds();
  }, [open, selectedType, loadComputedList, loadUploads, loadRssFeeds]);

  if (!ready || !isAdmin) return null;

  const isEditorMode = mode === "editor";
  const isPublicSurface = mode === "homepage" || mode === "section";
  const useInlineForm = isPublicSurface && inlinePanel && !isEditorMode;
  const showInlineFormInDocument = useInlineForm && open && !openedViaFab;
  const showInlineFormInPortal = useInlineForm && open && openedViaFab;
  const adminHref =
    mode === "homepage"
      ? "/admin/homepage"
      : `/admin/homepage?target=${encodeURIComponent(sectionSlug || "")}`;

  /** Vyrobí widget pro non-chart typy z formulářových polí. */
  const buildFreshWidget = (formType) => {
    const title = String(draftTitle || "").trim();
    const desc = String(draftDescription || "").trim();
    const note = String(draftTextBody || "").trim();
    if (formType === "text") {
      const fresh = createEmptyWidget("markdown");
      fresh.title = title || "";
      fresh.config = {
        ...(fresh.config || {}),
        heading: title || "",
        subheading: desc || "",
        content: note || "",
      };
      return fresh;
    }
    if (formType === "ad") {
      const fresh = createEmptyWidget("ad");
      fresh.title = "";
      fresh.config = {
        ...(fresh.config || {}),
        ...adDraft,
      };
      return fresh;
    }
    if (formType === "computed_chart") {
      if (!computedId) {
        toast.error("Vyberte vlastní výpočet ze seznamu (nebo ho nejdřív vytvořte v sekci „Vlastní výpočty“).");
        return null;
      }
      return {
        id: `tmp-${Math.random().toString(36).slice(2, 9)}`,
        type: "computed_view",
        title: title || "Výpočtový graf",
        width: "full",
        config: {
          computed_id: computedId,
          view: "chart",
          chart_type: computedChartType,
          limit: computedLimit > 0 ? computedLimit : 0,
          ...(desc ? { caption: desc } : {}),
        },
      };
    }
    if (formType === "rss_monitoring") {
      const daysNum = rssDays === "" ? null : Number(rssDays);
      return {
        id: `tmp-${Math.random().toString(36).slice(2, 9)}`,
        type: "rss_monitoring",
        title: title || "RSS novinky",
        width: "full",
        config: {
          selected_feed_ids: rssSelected,
          categories: [],
          q: rssQ.trim(),
          item_limit: rssItemLimit > 0 ? rssItemLimit : 15,
          days: Number.isFinite(daysNum) && daysNum > 0 ? daysNum : null,
          ...(desc ? { caption: desc } : {}),
        },
      };
    }
    // fallback (raw type) — používá se v modal seznamu
    const fresh = createEmptyWidget(formType);
    if (title) fresh.title = title;
    if (desc) {
      fresh.config = { ...(fresh.config || {}), caption: desc };
    }
    return fresh;
  };

  /** Mapuje source_type z katalogového configu na engine type pro public widget. */
  const mapSourceTypeToEngine = (st) => {
    const s = String(st || "").trim().toLowerCase();
    switch (s) {
      case "arad":
        return "arad_view";
      case "eurostat":
        return "eurostat_view";
      case "csu":
        return "csu_view";
      case "ecb":
        return "ecb_view";
      case "fred":
        return "fred_view";
      case "alphavantage":
      case "alpha_vantage":
        return "alphavantage_view";
      case "worldbank":
      case "world_bank":
        return "worldbank_view";
      case "world_bank_data360":
        return "world_bank_data360_view";
      case "bis":
        return "bis_view";
      case "imf":
        return "imf_view";
      case "oecd":
        return "oecd_view";
      default:
        return "external_catalog_chart";
    }
  };

  /** Společná logika uložení widgetu na backend (homepage / section). */
  const persistWidget = async (fresh) => {
    const normalizedFresh = normalizeWidgetForSave({
      ...fresh,
      section_page_id: fresh?.section_page_id ?? sectionPageId ?? undefined,
    });
    if (mode === "homepage") {
      const { data: cfg } = await api.get("/homepage/config");
      const base = (cfg.widgets || []).map(normalizeWidgetForSave);
      await api.put("/homepage/config", {
        title: cfg.title || "",
        subtitle: cfg.subtitle || "",
        widgets: [...base, normalizedFresh],
      });
    } else if (mode === "section") {
      if (!sectionSlug || !sectionId) {
        throw new Error("Chybí identifikace sekce.");
      }
      const { data: sec } = await api.get(`/sections/${sectionSlug}`);
      const base = (sec.widgets || []).map(normalizeWidgetForSave);
      await api.patch(`/sections/${sectionId}`, { widgets: [...base, normalizedFresh] });
    }
  };

  const add = async (type, explicitFresh = null) => {
    setBusy(true);
    try {
      const fresh = explicitFresh || createEmptyWidget(type);
      if (isEditorMode) {
        onAddLocal?.(fresh);
        setOpen(false);
        toast.success("Widget přidán — nezapomeňte stránku uložit.");
        setBusy(false);
        return;
      }
      await persistWidget(fresh);
      setOpen(false);
      onAdded?.(fresh);
      toast.success("Widget přidán.");
    } catch (e) {
      toast.error(formatApiError(e?.response?.data?.detail) || e.message);
    }
    setBusy(false);
  };

  /** „Použít" v PersonalCatalogChartForm — vyrobíme widget se správným engine typem. */
  const handleCatalogChartApply = async ({ title, description, config, widgetType }) => {
    setBusy(true);
    try {
      const sourceType = String(config?.source_type || "").toLowerCase();
      let engineType;
      if (widgetType === "external_catalog_chart" || sourceType === "external_catalog") {
        engineType = "external_catalog_chart";
      } else {
        engineType = mapSourceTypeToEngine(sourceType);
      }
      const cleanConfig = { ...(config || {}) };
      const captionFromForm = String(description || draftDescription || "").trim();
      if (captionFromForm) cleanConfig.caption = captionFromForm;
      const fresh = {
        id: `tmp-${Math.random().toString(36).slice(2, 9)}`,
        type: engineType,
        title: String(title || draftTitle || "").trim() || "",
        width: "full",
        config: cleanConfig,
      };
      if (isEditorMode) {
        onAddLocal?.(fresh);
        setOpen(false);
        toast.success("Widget přidán — nezapomeňte stránku uložit.");
        setBusy(false);
        return;
      }
      await persistWidget(fresh);
      setOpen(false);
      onAdded?.(fresh);
      toast.success("Datový widget přidán.");
    } catch (e) {
      toast.error(formatApiError(e?.response?.data?.detail) || e.message);
    }
    setBusy(false);
  };

  /** „Použít" v PersonalUploadChartForm — uloží se s odkazem na admin upload. */
  const handleUploadChartApply = async ({ title, description, config }) => {
    if (!user?.id) {
      toast.error("Chybí identifikace přihlášeného uživatele.");
      return;
    }
    setBusy(true);
    try {
      const cleanConfig = {
        ...(config || {}),
        owner_user_id: user.id,
      };
      const captionFromForm = String(description || draftDescription || "").trim();
      if (captionFromForm) cleanConfig.caption = captionFromForm;
      const fresh = {
        id: `tmp-${Math.random().toString(36).slice(2, 9)}`,
        type: "user_upload_chart",
        title: String(title || draftTitle || "").trim() || "Graf z mých dat",
        width: "full",
        config: cleanConfig,
      };
      if (isEditorMode) {
        onAddLocal?.(fresh);
        setOpen(false);
        toast.success("Widget přidán — nezapomeňte stránku uložit.");
        setBusy(false);
        return;
      }
      await persistWidget(fresh);
      setOpen(false);
      onAdded?.(fresh);
      toast.success("Graf z nahraných dat přidán.");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || e.message);
    }
    setBusy(false);
  };

  // -------- modal (seznam) -- pro editor / non-inline --
  const panelInner = (
    <div
      className="bg-card text-card-foreground border border-border rounded-sm shadow-xl w-full max-w-lg max-h-[min(80vh,520px)] flex flex-col"
      onClick={(e) => e.stopPropagation()}
    >
      <div className="flex items-start justify-between gap-3 p-4 border-b border-border">
        <div>
          <h2 id="quick-add-widget-title" className="font-serif text-lg text-card-foreground">
            Přidat widget
          </h2>
          <p className="text-xs text-muted-foreground mt-1">
            {isEditorMode
              ? "Vyberte typ widgetu — přidá se na konec seznamu a hned ho budete moci nakonfigurovat. Nezapomeňte stránku po úpravě uložit."
              : "Widget se uloží hned na server a objeví se v mřížce níže."}
          </p>
        </div>
        <button
          type="button"
          onClick={() => !busy && setOpen(false)}
          className="p-1 rounded-sm text-muted-foreground hover:bg-muted/60"
          aria-label="Zavřít"
        >
          <X className="h-5 w-5" />
        </button>
      </div>
      <div className="overflow-y-auto p-2 flex-1 min-h-0">
        <ul className="space-y-0.5">
          {typesOrdered.map((t) => (
            <li key={t.value}>
              <button
                type="button"
                disabled={busy}
                onClick={() => add(t.value)}
                className="w-full text-left px-3 py-2.5 text-sm rounded-sm border border-transparent hover:border-border hover:bg-muted/50 font-mono text-foreground disabled:opacity-50"
              >
                {t.label}
              </button>
            </li>
          ))}
        </ul>
      </div>
      <div className="p-3 border-t border-border bg-muted/30 flex flex-wrap items-center gap-2 justify-between">
        {!isEditorMode ? (
          <Link
            to={adminHref}
            className="inline-flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground"
            onClick={() => setOpen(false)}
          >
            <Settings2 className="h-3.5 w-3.5" /> Pokročilý editor (celá stránka)
          </Link>
        ) : (
          <span />
        )}
        <button
          type="button"
          onClick={() => setOpen(false)}
          className="text-xs px-2 py-1 border border-border rounded-sm bg-card hover:bg-muted/60 text-foreground"
        >
          Zavřít
        </button>
      </div>
    </div>
  );

  /** Společné textové vstupy „Název" + „Popisek" pro typy bez vlastního subformuláře. */
  const renderCommonTextFields = () => (
    <>
      <div>
        <label className="block text-xs text-muted-foreground mb-1">Název</label>
        <input
          className="w-full border border-border rounded-lg px-2 py-1.5 bg-card text-card-foreground"
          value={draftTitle}
          onChange={(e) => setDraftTitle(e.target.value)}
          disabled={busy}
        />
      </div>
      <div>
        <label className="block text-xs text-muted-foreground mb-1">Popisek (volitelné)</label>
        <textarea
          className="w-full border border-border rounded-lg px-2 py-1.5 min-h-[64px] bg-card text-card-foreground"
          value={draftDescription}
          onChange={(e) => setDraftDescription(e.target.value)}
          disabled={busy}
        />
      </div>
    </>
  );

  const renderSubForm = () => {
    if (selectedType === "chart") {
      return (
        <PersonalCatalogChartForm
          onApply={(payload) => handleCatalogChartApply(payload)}
          disabled={busy}
          ensureGlobalCatalogSource={isPublicSurface}
        />
      );
    }
    if (selectedType === "uploaded_data_chart") {
      return (
        <PersonalUploadChartForm
          uploads={uploads}
          onUploadsRefresh={loadUploads}
          onApply={(payload) => handleUploadChartApply(payload)}
          disabled={busy}
        />
      );
    }
    if (selectedType === "computed_chart") {
      return (
        <>
          {renderCommonTextFields()}
          <div className="space-y-2 border border-border/60 rounded-xl p-3 bg-muted/30">
            <div className="flex items-start justify-between gap-2">
              <div>
                <div className="text-xs font-medium text-foreground">Vlastní výpočet</div>
                <p className="text-[11px] text-muted-foreground mt-0.5 leading-snug">
                  Výpočet můžete vytvořit rovnou zde (stejně jako v Můj dashboard), nebo použít
                  dříve uložený ze seznamu.
                </p>
              </div>
              <Link
                to="/computed"
                className="shrink-0 inline-flex items-center gap-1 text-xs px-2 py-1 rounded-md border border-border bg-card hover:bg-muted/50 text-foreground"
                onClick={() => setOpen(false)}
                title="Otevřít editor vlastních výpočtů"
              >
                <Settings2 className="h-3.5 w-3.5" /> Editor výpočtů
              </Link>
            </div>
            <PersonalComputedInlineForm
              onCreated={(c) => {
                if (c?.id) setComputedId(c.id);
                loadComputedList();
              }}
              disabled={busy}
            />
            <div className="text-[11px] text-muted-foreground pt-1 border-t border-border/40">
              Nebo vyberte dříve uložený výpočet:
            </div>
            <label className="block text-[11px] text-foreground">
              Výběr výpočtu
              <div className="flex items-center gap-2 mt-1">
                <select
                  className="flex-1 border border-border rounded-lg px-2 py-1.5 text-sm bg-card text-card-foreground"
                  value={computedId}
                  onChange={(e) => setComputedId(e.target.value)}
                  disabled={busy}
                >
                  <option value="">
                    {computedList.length === 0
                      ? "— žádný výpočet zatím nemáte —"
                      : "— vyberte —"}
                  </option>
                  {computedList.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.name || c.id}
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  onClick={() => loadComputedList()}
                  className="text-xs px-2 py-1 border border-border rounded-md bg-card hover:bg-muted/50 text-foreground"
                  title="Znovu načíst seznam"
                  disabled={busy}
                >
                  Obnovit
                </button>
              </div>
            </label>
            <label className="block text-[11px] text-foreground">
              Typ grafu
              <select
                className="mt-1 w-full border border-border rounded-lg px-2 py-1.5 text-sm bg-card text-card-foreground"
                value={computedChartType}
                onChange={(e) => setComputedChartType(e.target.value)}
                disabled={busy}
              >
                <option value="line">Čára</option>
                <option value="bar">Sloupce</option>
                <option value="area">Plocha</option>
              </select>
            </label>
            <label className="block text-[11px] text-foreground">
              Limit bodů (0 = vše)
              <input
                type="number"
                min={0}
                className="mt-1 w-full border border-border rounded-lg px-2 py-1.5 text-sm bg-card text-card-foreground"
                value={computedLimit}
                onChange={(e) => setComputedLimit(Number(e.target.value) || 0)}
                disabled={busy}
              />
            </label>
          </div>
          <div className="pt-1">
            <button
              type="button"
              onClick={() => {
                const f = buildFreshWidget(selectedType);
                if (!f) return;
                add(selectedType, f);
              }}
              disabled={busy || !computedId}
              className="btn-primary text-sm py-1.5 px-3 disabled:opacity-60"
            >
              Přidat widget
            </button>
          </div>
        </>
      );
    }
    if (selectedType === "rss_monitoring") {
      return (
        <>
          {renderCommonTextFields()}
          <div className="space-y-2 border border-border/60 rounded-xl p-3 bg-muted/30">
            <div className="text-xs font-medium text-foreground">
              Zdroje (prázdné = všechny dostupné)
            </div>
            <div className="max-h-40 overflow-y-auto border border-border rounded-lg p-2 bg-card space-y-1">
              {rssFeeds.length === 0 ? (
                <span className="text-xs text-muted-foreground">
                  Žádné feedy — nejdřív je přidejte v sekci RSS monitoring.
                </span>
              ) : (
                rssFeeds.map((f) => (
                  <label key={f.id} className="flex items-center gap-2 text-xs cursor-pointer text-card-foreground">
                    <input
                      type="checkbox"
                      checked={rssSelected.includes(f.id)}
                      onChange={(e) => {
                        if (e.target.checked) setRssSelected((s) => [...s, f.id]);
                        else setRssSelected((s) => s.filter((x) => x !== f.id));
                      }}
                      disabled={busy}
                    />
                    <span className="truncate" title={f.url}>
                      {f.name} ({f.scope === "global" ? "globální" : "vlastní"})
                    </span>
                  </label>
                ))
              )}
            </div>
            <label className="block text-[11px] text-foreground">
              Max. položek (1–50)
              <input
                type="number"
                min={1}
                max={50}
                className="mt-1 w-full border border-border rounded-lg px-2 py-1.5 text-sm bg-card text-card-foreground"
                value={rssItemLimit}
                onChange={(e) => setRssItemLimit(Number(e.target.value) || 15)}
                disabled={busy}
              />
            </label>
            <label className="block text-[11px] text-foreground">
              Posledních X dní (volitelné)
              <input
                type="number"
                min={1}
                className="mt-1 w-full border border-border rounded-lg px-2 py-1.5 text-sm bg-card text-card-foreground"
                placeholder="např. 30"
                value={rssDays}
                onChange={(e) => setRssDays(e.target.value)}
                disabled={busy}
              />
            </label>
            <label className="block text-[11px] text-foreground">
              Klíčová slova (title / souhrn)
              <input
                className="mt-1 w-full border border-border rounded-lg px-2 py-1.5 text-sm bg-card text-card-foreground"
                value={rssQ}
                onChange={(e) => setRssQ(e.target.value)}
                disabled={busy}
              />
            </label>
          </div>
          <div className="pt-1">
            <button
              type="button"
              onClick={() => {
                const f = buildFreshWidget(selectedType);
                if (!f) return;
                add(selectedType, f);
              }}
              disabled={busy}
              className="btn-primary text-sm py-1.5 px-3 disabled:opacity-60"
            >
              Přidat widget
            </button>
          </div>
        </>
      );
    }
    // text + ad
    return (
      <>
        {selectedType !== "ad" && renderCommonTextFields()}
        {selectedType === "text" && (
          <div>
            <label className="block text-xs text-muted-foreground mb-1">Text / poznámka</label>
            <textarea
              className="w-full border border-border rounded-lg px-2 py-1.5 min-h-[100px] bg-card text-card-foreground"
              placeholder="Nadpis stránky, komentář, poznámka..."
              value={draftTextBody}
              onChange={(e) => setDraftTextBody(e.target.value)}
              disabled={busy}
            />
          </div>
        )}
        {selectedType === "ad" && (
          <div className="space-y-2">
            <div className="text-xs text-muted-foreground">
              Nastavte reklamu rovnou zde (obrázek / text / HTML). Titulek se u inzerce nepoužívá.
            </div>
            <div className="border border-border/60 rounded-xl p-3 bg-muted/20">
              <AdConfigEditor
                cfg={adDraft}
                onPatch={(patch) => setAdDraft((prev) => ({ ...prev, ...(patch || {}) }))}
              />
            </div>
          </div>
        )}
        <div className="pt-1">
          <button
            type="button"
            onClick={() => {
              const f = buildFreshWidget(selectedType);
              if (!f) return;
              add(selectedType, f);
            }}
            disabled={busy}
            className="btn-primary text-sm py-1.5 px-3 disabled:opacity-60"
          >
            Přidat widget
          </button>
        </div>
      </>
    );
  };

  const renderInlineWidgetForm = () => (
    <div className="soft-card border-border/80 shadow-sm w-full max-w-3xl p-4 space-y-3 relative">
      <button
        type="button"
        onClick={() => !busy && setOpen(false)}
        className="absolute top-2 right-2 p-1 rounded-md text-muted-foreground hover:bg-muted/60 hover:text-foreground z-10"
        aria-label="Zavřít přidávání widgetu"
        title="Zavřít"
        disabled={busy}
      >
        <X className="h-5 w-5" />
      </button>
      <div className="pr-8">
        <h2 id="quick-add-inline-widget-title" className="font-serif text-lg text-foreground">
          Widgety
        </h2>
        <p className="text-xs text-muted-foreground mt-0.5">
          Přidejte widget — u položky z katalogu jde o stejná data jako u grafu; na hotovém widgetu lze přepínat mezi{" "}
          <span className="text-foreground/90">grafem a tabulkou</span>. U výpočtů a uploadů vyberte zdroj jako u Můj dashboard.
        </p>
      </div>

      <div>
        <label className="block text-xs text-muted-foreground mb-1">Typ widgetu</label>
        <select
          className="w-full border border-border rounded-lg px-2 py-1.5 bg-card text-card-foreground"
          value={selectedType}
          onChange={(e) => setSelectedType(e.target.value)}
          disabled={busy}
        >
          {FORM_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </div>

      {renderSubForm()}

      <div className="flex items-center justify-between gap-2 pt-2 border-t border-border/60">
        <Link
          to={adminHref}
          className="inline-flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground"
          onClick={() => setOpen(false)}
        >
          <Settings2 className="h-3.5 w-3.5" /> Pokročilý editor (celá stránka)
        </Link>
        <button
          type="button"
          onClick={() => setOpen(false)}
          className="text-xs px-2 py-1 border border-border rounded-sm bg-card hover:bg-muted/60 text-foreground"
          disabled={busy}
        >
          Zavřít
        </button>
      </div>
    </div>
  );

  return (
    <>
      {showInlineFormInDocument ? <div className="mb-4">{renderInlineWidgetForm()}</div> : null}

      <button
        type="button"
        onClick={() => {
          if (open && openedViaFab) {
            setOpen(false);
            return;
          }
          if (open && !openedViaFab) {
            setOpenedViaFab(true);
            return;
          }
          setOpenedViaFab(true);
          setOpen(true);
        }}
        disabled={busy}
        className="fixed bottom-24 right-5 z-[35] flex h-16 w-16 items-center justify-center rounded-full border border-border bg-card text-card-foreground shadow-lg transition hover:bg-muted/60 hover:shadow-md md:bottom-8"
        title="Přidat widget (jen administrátor)"
        aria-label="Přidat datový widget"
        data-testid="admin-quick-add-widget"
      >
        <Plus className="h-8 w-8" strokeWidth={2} />
      </button>

      {open && !useInlineForm &&
        createPortal(
          <div
            className="fixed inset-0 z-[60] flex items-end justify-center sm:items-center p-4 bg-slate-900/40 backdrop-blur-sm"
            role="dialog"
            aria-modal="true"
            aria-labelledby="quick-add-widget-title"
            onClick={() => !busy && setOpen(false)}
          >
            {panelInner}
          </div>,
          document.body
        )}

      {showInlineFormInPortal &&
        createPortal(
          <div
            className="fixed inset-0 z-[60] flex items-center justify-center p-4 sm:p-6 bg-slate-900/40 backdrop-blur-sm"
            role="dialog"
            aria-modal="true"
            aria-labelledby="quick-add-inline-widget-title"
            onClick={() => !busy && setOpen(false)}
          >
            <div
              className="w-full max-w-3xl max-h-[min(90dvh,760px)] overflow-y-auto overscroll-contain"
              onClick={(e) => e.stopPropagation()}
            >
              {renderInlineWidgetForm()}
            </div>
          </div>,
          document.body
        )}
    </>
  );
}

function normalizeWidgetForSave(w) {
  const rowSpanRaw = w?.rowSpan ?? w?.row_span ?? w?.config?.rowSpan ?? w?.config?.row_span;
  const rowSpanNum = rowSpanRaw == null || rowSpanRaw === "" ? null : Number(rowSpanRaw);
  const out = {
    id: w.id,
    type: w.type,
    title: w.title || "",
    width: w.width || "full",
    config: w.config || {},
  };
  const sectionPageId = String(w?.section_page_id || "").trim();
  if (sectionPageId) out.section_page_id = sectionPageId;
  if (Number.isFinite(rowSpanNum) && rowSpanNum >= 1 && rowSpanNum <= 10) {
    out.rowSpan = rowSpanNum;
  }
  return out;
}
