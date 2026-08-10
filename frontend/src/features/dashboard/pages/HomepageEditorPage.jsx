import React, { useCallback, useEffect, useState } from "react";
import { Save, RotateCcw, Eye, Plus, Trash2, ExternalLink, RefreshCw, Palette } from "lucide-react";
import { useLocation, useSearchParams } from "react-router-dom";
import { toast } from "sonner";
import api, { formatApiError } from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import WidgetListEditor from "@/components/editor/WidgetListEditor";
import PreviewModal from "@/components/editor/PreviewModal";
import AdminQuickAddWidget from "@/components/admin/AdminQuickAddWidget";
import SectionIconPicker from "@/components/admin/SectionIconPicker";
import { ALL_FREQS } from "@/components/widgets/aradViewChartFreq";
import { PRIMARY_APPEARANCE_PRESETS, getAppearancePresetById } from "@/theme/appearancePresets";
import { useAuth } from "@/contexts/AuthContext";
import LocalizedTextFields from "@/components/cms/LocalizedTextFields";
import { serializeWidgetForSave } from "@/lib/localizedContent";
import { useTranslation } from "react-i18next";
/** Globální výchozí typ grafu pro widgety bez vlastního `config.chart_type`. */
const PAGE_DEFAULT_CHART_TYPES = [
  { value: "line", label: "Čára" },
  { value: "bar", label: "Sloupce" },
  { value: "area", label: "Plocha" },
  { value: "pie", label: "Kruhový" },
];

const PAGE_DEFAULT_CHART_FREQUENCIES = [
  { value: "", label: "Podle indikátoru (nativní frekvence)" },
  ...ALL_FREQS.map((f) => ({ value: f.code, label: `${f.title} (${f.label})` })),
];

/**
 * Unified data-management editor. Admin picks a "target" at the top —
 * either the public homepage or any custom section (Banky, Pojišťovny, …).
 * Editing the same widgets/metadata happens through the same
 * WidgetListEditor for consistency; we just swap GET/PUT endpoints
 * behind the scenes based on the selected target.
 */
export default function HomepageEditorPage() {
  const { t } = useTranslation();
  const { isAdmin } = useAuth();
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  const initialTarget = searchParams.get("target") || "homepage";
  const expandWidgetId = location.state?.expandWidgetId || null;

  const [target, setTarget] = useState(initialTarget);
  const [sections, setSections] = useState([]); // list for dropdown
  const [doc, setDoc] = useState(null); // loaded homepage or section
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [creatingSection, setCreatingSection] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  // ID widgetu nově přidaného z plovoucího „+" (AdminQuickAddWidget) — předá
  // se WidgetListEditoru, aby se daný widget automaticky rozbalil k editaci.
  const [localExpandId, setLocalExpandId] = useState(null);
  /** Když GET /homepage/config nebo sekce selže (backend neběží), zobrazíme prázdný rozvrh + banner. */
  const [loadError, setLoadError] = useState(null);
  const [reloadNonce, setReloadNonce] = useState(0);

  // Výchozí barevné schéma pro anonymní uživatele
  const [defaultAppearanceId, setDefaultAppearanceId] = useState("blue");
  const [savingAppearance, setSavingAppearance] = useState(false);

  // Load list of sections once (used for the target dropdown and new-section form).
  const loadSections = useCallback(async () => {
    try {
      const { data } = await api.get("/sections");
      setSections(Array.isArray(data) ? data : []);
    } catch {
      setSections([]);
    }
  }, []);

  useEffect(() => {
    loadSections();
  }, [loadSections]);

  useEffect(() => {
    api.get("/app-settings")
      .then((res) => setDefaultAppearanceId(res.data?.default_appearance_id || "blue"))
      .catch(() => {});
  }, []);

  const saveDefaultAppearance = async (id) => {
    setSavingAppearance(true);
    try {
      const { data } = await api.patch("/app-settings", { default_appearance_id: id });
      setDefaultAppearanceId(data.default_appearance_id);
      toast.success("Výchozí téma uloženo.");
    } catch (e) {
      toast.error(formatApiError(e.response?.data?.detail) || "Nepodařilo se uložit téma.");
    }
    setSavingAppearance(false);
  };

  // Whenever target changes, load the corresponding document.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      setDoc(null);
      setLoadError(null);
      try {
        if (target === "homepage") {
          const { data } = await api.get("/homepage/config");
          if (!cancelled) setDoc({ kind: "homepage", ...data });
        } else {
          const { data } = await api.get(`/sections/${target}`);
          if (!cancelled) setDoc({ kind: "section", ...data, section_pages: Array.isArray(data?.section_pages) ? data.section_pages : [] });
        }
      } catch (e) {
        if (!cancelled) {
          const msg =
            formatApiError(e.response?.data?.detail) ||
            e.message ||
            "Backend neodpovídá. Spusťte API server a zkontrolujte REACT_APP_BACKEND_URL.";
          toast.error(msg);
          setLoadError(msg);
          // Aby šlo přidávat widgety i bez běžícího backendu; uložení vyžaduje API.
          if (target === "homepage") {
            setDoc({
              kind: "homepage",
              title: "",
              subtitle: "",
              default_chart_type: "line",
              default_chart_frequency: null,
              widgets: [],
            });
          } else {
            setDoc({
              kind: "section",
              id: "",
              slug: target,
              name: "",
              icon: "Folder",
              subtitle: "",
              default_chart_type: "line",
              default_chart_frequency: null,
              section_pages: [],
              widgets: [],
            });
          }
        }
      }
      if (!cancelled) setLoading(false);
    })();
    return () => {
      cancelled = true;
    };
  }, [target, reloadNonce]);

  const setTargetAndUrl = (next) => {
    setTarget(next);
    const params = new URLSearchParams(searchParams);
    if (next === "homepage") params.delete("target");
    else params.set("target", next);
    setSearchParams(params, { replace: true });
  };

  const setField = (key, value) => setDoc((d) => (d ? { ...d, [key]: value } : d));

  const save = async () => {
    if (!doc) return;
    if (doc.kind === "section" && !doc.id) {
      toast.error("Sekci se nepodařilo načíst z API — bez ID ji nelze uložit. Spusťte backend a klikněte „Znovu načíst“.");
      return;
    }
    setSaving(true);
    try {
      const widgets = (doc.widgets || []).map((w) => serializeWidgetForSave(w));
      if (doc.kind === "homepage") {
        await api.put("/homepage/config", {
          title: doc.title || "",
          title_en: doc.title_en || "",
          subtitle: doc.subtitle || "",
          subtitle_en: doc.subtitle_en || "",
          default_chart_type: doc.default_chart_type || "line",
          default_chart_frequency: doc.default_chart_frequency ?? null,
          widgets,
        });
        toast.success("Homepage uložena");
      } else {
        await api.patch(`/sections/${doc.id}`, {
          name: doc.name || "",
          name_en: doc.name_en || "",
          subtitle: doc.subtitle || "",
          subtitle_en: doc.subtitle_en || "",
          icon: doc.icon || "Folder",
          default_chart_type: doc.default_chart_type || "line",
          default_chart_frequency: doc.default_chart_frequency ?? null,
          section_pages: (doc.section_pages || []).map((p, idx) => ({
            id: p.id || undefined,
            title: String(p.title || "").trim(),
            title_en: String(p.title_en || "").trim(),
            slug: String(p.slug || "").trim(),
            order: (idx + 1) * 10,
            is_visible: p.is_visible !== false,
          })).filter((p) => p.title && p.slug),
          widgets,
        });
        toast.success(`Sekce „${doc.name}" uložena`);
        await loadSections();
      }
    } catch (e) {
      toast.error(formatApiError(e.response?.data?.detail) || e.message);
    }
    setSaving(false);
  };

  const reset = async () => {
    if (doc?.kind !== "homepage") return;
    if (!window.confirm("Obnovit výchozí widgety?")) return;
    const { data } = await api.post("/homepage/config/reset");
    setDoc({ kind: "homepage", ...data });
    toast.success("Obnoveno");
  };

  const deleteSection = async () => {
    if (doc?.kind !== "section") return;
    if (!window.confirm(`Opravdu smazat sekci „${doc.name}"?`)) return;
    try {
      await api.delete(`/sections/${doc.id}`);
      toast.success("Sekce smazána");
      await loadSections();
      setTargetAndUrl("homepage");
    } catch (e) {
      toast.error(formatApiError(e.response?.data?.detail) || e.message);
    }
  };

  const subtitle =
    doc?.kind === "section"
      ? `Sekce v menu · URL /s/${doc.slug}`
      : "Vyberte, co uvidí uživatelé na úvodní stránce (Přehled) nebo v některé sekci";

  return (
    <AppShell
      title="Widgety"
      subtitle={subtitle}
      actions={
        <div className="flex items-center gap-1.5 flex-wrap justify-end text-foreground/90">
          <button
            onClick={() => setPreviewOpen(true)}
            disabled={!doc || !!loadError}
            className="flex items-center gap-1.5 px-2.5 h-8 text-xs border border-border rounded-sm hover:bg-muted/60 disabled:opacity-50"
            title={
              loadError
                ? "Náhled potřebuje běžící backend"
                : "Zobrazit náhled aktuálních (neuložených) widgetů"
            }
            data-testid="preview-btn"
          >
            <Eye className="h-3.5 w-3.5" /> Náhled
          </button>
          {isAdmin && doc?.kind === "homepage" && (
            <button
              onClick={reset}
              className="flex items-center gap-1.5 px-2.5 h-8 text-xs border border-border rounded-sm hover:bg-muted/60"
            >
              <RotateCcw className="h-3.5 w-3.5" /> Výchozí
            </button>
          )}
          {isAdmin && doc?.kind === "section" && (
            <button
              onClick={deleteSection}
              disabled={!doc?.id}
              className="flex items-center gap-1.5 px-2.5 h-8 text-xs border border-destructive/40 text-destructive rounded-sm hover:bg-destructive/5 disabled:opacity-50"
            >
              <Trash2 className="h-3.5 w-3.5" /> Smazat sekci
            </button>
          )}
          <button
            onClick={save}
            disabled={saving || !doc || (doc?.kind === "section" && !doc?.id)}
            className="btn-mint flex items-center gap-1.5 px-3 h-8 text-xs disabled:opacity-60"
            title={
              doc?.kind === "section" && !doc?.id
                ? "Nejprve načtěte sekci z API (backend musí běžet)"
                : undefined
            }
          >
            <Save className="h-3.5 w-3.5" /> {saving ? "Ukládání…" : "Uložit"}
          </button>
        </div>
      }
    >
      <div className="homepage-editor-scope copper-text-fix-scope min-w-0">
      {/* Target selector bar */}
      <div className="mb-5 flex items-start gap-3 flex-wrap rounded-2xl border border-border/90 bg-gradient-to-br from-slate-50 via-white to-sky-50/30 p-4 shadow-sm ring-1 ring-slate-200/60">
        <div className="flex-1 min-w-[260px]">
          <label className="text-xs font-semibold tracking-wide text-foreground/90">
            Upravit data sekce
          </label>
          <select
            className="input mt-2"
            value={target}
            onChange={(e) => {
              const v = e.target.value;
              if (v === "__new__") {
                if (!isAdmin) return;
                setCreatingSection(true);
                return;
              }
              setTargetAndUrl(v);
            }}
            data-testid="editor-target"
          >
            <optgroup label="Homepage">
              <option value="homepage">🏠 Přehled (úvodní stránka)</option>
            </optgroup>
            {sections.length > 0 && (
              <optgroup label="Sekce menu">
                {sections.map((s) => (
                  <option key={s.id} value={s.slug}>
                    {s.name} · /s/{s.slug}
                  </option>
                ))}
              </optgroup>
            )}
            {isAdmin ? (
              <optgroup label="Akce">
                <option value="__new__">+ Vytvořit novou sekci…</option>
              </optgroup>
            ) : null}
          </select>
          <div className="mt-2 text-xs text-muted-foreground leading-relaxed">
            {doc?.kind === "section"
              ? "Upravuješ vlastní sekci – změny se projeví v menu i na veřejné URL."
              : "Upravuješ veřejnou homepage – uvidí ji všichni uživatelé."}
          </div>
        </div>
        {doc?.kind === "section" && (
          <div className="flex items-end">
            <a
              href={`/s/${doc.slug}`}
              target="_blank"
              rel="noreferrer"
              className="flex items-center gap-1.5 h-9 px-3 text-xs font-medium border border-border bg-card rounded-xl shadow-sm hover:bg-muted/50 hover:border-border transition-colors"
            >
              <ExternalLink className="h-3.5 w-3.5" /> Otevřít sekci v novém panelu
            </a>
          </div>
        )}
      </div>

      {creatingSection && (
        <CreateSectionModal
          onClose={() => setCreatingSection(false)}
          onCreated={async (slug) => {
            setCreatingSection(false);
            await loadSections();
            setTargetAndUrl(slug);
            toast.success("Sekce vytvořena");
          }}
        />
      )}

      {loading ? (
        <div className="text-sm text-muted-foreground font-mono">Načítání…</div>
      ) : !doc ? (
        <div className="text-sm text-rose-700 font-mono border border-rose-200 bg-rose-50/80 rounded-sm p-4">
          Konfiguraci se nepodařilo připravit. Zkuste obnovit stránku.
        </div>
      ) : (
        <>
          {loadError && (
            <div className="mb-4 flex flex-col sm:flex-row sm:items-center gap-3 border border-amber-300 bg-amber-50 canvas-dark:bg-amber-950/35 text-amber-950 canvas-dark:text-amber-50 rounded-sm px-4 py-3 text-sm">
              <div className="flex-1 min-w-0">
                <div className="font-medium">Nepodařilo se načíst uloženou konfiguraci z API.</div>
                <div className="text-xs font-mono mt-1 opacity-90 break-words">{loadError}</div>
                <div className="text-xs mt-2 text-amber-900/90">
                  Můžete přidávat widgety v editoru; uložení a náhled vyžadují běžící backend. U sekce bez načteného ID nelze
                  ukládat, dokud API neodpoví.
                </div>
              </div>
              <button
                type="button"
                onClick={() => setReloadNonce((n) => n + 1)}
                className="shrink-0 inline-flex items-center gap-1.5 px-3 h-9 text-xs border border-amber-600/40 rounded-sm bg-card hover:bg-amber-100/80"
              >
                <RefreshCw className="h-3.5 w-3.5" /> Znovu načíst
              </button>
            </div>
          )}
          {/* Metadata */}
          <div className="mb-6 rounded-2xl border border-border/90 bg-card/90 p-4 shadow-sm ring-1 ring-border/40 md:p-5">
          <div className="grid grid-cols-1 md:grid-cols-12 gap-4">
            {doc.kind === "homepage" ? (
              <>
                <div className="md:col-span-6">
                  <LocalizedTextFields
                    labelCs={t("cms.titleCs")}
                    labelEn={t("cms.titleEn")}
                    valueCs={doc.title || ""}
                    valueEn={doc.title_en || ""}
                    onChangeCs={(v) => setField("title", v)}
                    onChangeEn={(v) => setField("title_en", v)}
                  />
                </div>
                <div className="md:col-span-6">
                  <LocalizedTextFields
                    labelCs={t("cms.subtitleCs")}
                    labelEn={t("cms.subtitleEn")}
                    valueCs={doc.subtitle || ""}
                    valueEn={doc.subtitle_en || ""}
                    onChangeCs={(v) => setField("subtitle", v)}
                    onChangeEn={(v) => setField("subtitle_en", v)}
                  />
                </div>
                <div className="md:col-span-6">
                  <Field label="Výchozí typ grafu (návštěvník bez vlastního nastavení widgetu)">
                    <select
                      className="input"
                      value={doc.default_chart_type || "line"}
                      onChange={(e) => setField("default_chart_type", e.target.value)}
                    >
                      {PAGE_DEFAULT_CHART_TYPES.map((o) => (
                        <option key={o.value} value={o.value}>
                          {o.label}
                        </option>
                      ))}
                    </select>
                  </Field>
                </div>
                <div className="md:col-span-6">
                  <Field label="Výchozí frekvence grafu (D až Y, hrubší = jiný vzhled dat)">
                    <select
                      className="input"
                      value={doc.default_chart_frequency ?? ""}
                      onChange={(e) =>
                        setField("default_chart_frequency", e.target.value || null)
                      }
                    >
                      {PAGE_DEFAULT_CHART_FREQUENCIES.map((o) => (
                        <option key={o.value || "native"} value={o.value}>
                          {o.label}
                        </option>
                      ))}
                    </select>
                  </Field>
                </div>
              </>
            ) : (
              <>
                <div className="md:col-span-5">
                  <LocalizedTextFields
                    labelCs={t("cms.nameCs")}
                    labelEn={t("cms.nameEn")}
                    valueCs={doc.name || ""}
                    valueEn={doc.name_en || ""}
                    onChangeCs={(v) => setField("name", v)}
                    onChangeEn={(v) => setField("name_en", v)}
                    placeholderCs="Banky"
                  />
                </div>
                <div className="md:col-span-3">
                  <Field label="Ikona">
                    <SectionIconPicker value={doc.icon || "Folder"} onChange={(v) => setField("icon", v)} />
                  </Field>
                </div>
                <div className="md:col-span-4">
                  <Field label="URL (slug) – neměnné">
                    <input className="input font-mono" value={`/s/${doc.slug}`} readOnly />
                  </Field>
                </div>
                <div className="md:col-span-12">
                  <LocalizedTextFields
                    labelCs={t("cms.subtitleCs")}
                    labelEn={t("cms.subtitleEn")}
                    valueCs={doc.subtitle || ""}
                    valueEn={doc.subtitle_en || ""}
                    onChangeCs={(v) => setField("subtitle", v)}
                    onChangeEn={(v) => setField("subtitle_en", v)}
                    placeholderCs="např. Klíčová data o bankovním sektoru"
                  />
                </div>
                <div className="md:col-span-6">
                  <Field label="Výchozí typ grafu (návštěvník bez vlastního nastavení widgetu)">
                    <select
                      className="input"
                      value={doc.default_chart_type || "line"}
                      onChange={(e) => setField("default_chart_type", e.target.value)}
                    >
                      {PAGE_DEFAULT_CHART_TYPES.map((o) => (
                        <option key={o.value} value={o.value}>
                          {o.label}
                        </option>
                      ))}
                    </select>
                  </Field>
                </div>
                <div className="md:col-span-6">
                  <Field label="Výchozí frekvence grafu (D až Y)">
                    <select
                      className="input"
                      value={doc.default_chart_frequency ?? ""}
                      onChange={(e) =>
                        setField("default_chart_frequency", e.target.value || null)
                      }
                    >
                      {PAGE_DEFAULT_CHART_FREQUENCIES.map((o) => (
                        <option key={o.value || "native"} value={o.value}>
                          {o.label}
                        </option>
                      ))}
                    </select>
                  </Field>
                </div>
                <div className="md:col-span-12">
                  <Field label="Podstránky sekce (záložky pro uživatele)">
                    <SectionPagesEditor
                      sectionSlug={doc.slug}
                      value={doc.section_pages || []}
                      onChange={(nextPages) => setField("section_pages", nextPages)}
                    />
                  </Field>
                </div>
              </>
            )}
          </div>
          </div>

          <WidgetListEditor
            widgets={doc.widgets || []}
            onChange={(widgets) => setDoc((c) => ({ ...c, widgets }))}
            expandWidgetId={localExpandId || expandWidgetId}
          />
        </>
      )}

      {/* Plovoucí „+" pro přidání widgetu (jednotné UX se zbytkem aplikace).
          V editor režimu se widget přidá lokálně do rozdělaného doc.widgets a
          označí se k automatickému rozbalení (uloží admin sám tlačítkem nahoře). */}
      {!loading && doc && (
        <AdminQuickAddWidget
          mode="editor"
          onAddLocal={(fresh) => {
            setDoc((c) => ({ ...(c || {}), widgets: [...((c && c.widgets) || []), fresh] }));
            setLocalExpandId(fresh.id);
          }}
        />
      )}

      {/* Výchozí barevné schéma pro anonymní / nové návštěvníky */}
      <div className="mt-6 rounded-2xl border border-border/90 bg-gradient-to-br from-slate-50 via-white to-sky-50/30 p-5 shadow-sm ring-1 ring-slate-200/60">
        <div className="flex items-center gap-2 mb-1">
          <Palette className="h-4 w-4 text-muted-foreground" />
          <span className="text-sm font-semibold text-foreground/90">Výchozí barevné schéma</span>
        </div>
        <p className="text-xs text-muted-foreground mb-4">
          Zvolené schéma se zobrazí anonymním návštěvníkům a přihlášeným uživatelům, kteří si ještě sami nic nevybrali. Uživatel může kdykoli přepnout vlastní schéma a to se mu uloží.
        </p>
        <div className="flex flex-wrap gap-2">
          {PRIMARY_APPEARANCE_PRESETS.map((preset) => {
            const isActive = defaultAppearanceId === preset.id;
            return (
              <button
                key={preset.id}
                onClick={() => saveDefaultAppearance(preset.id)}
                disabled={savingAppearance}
                title={preset.label}
                className={`flex items-center gap-2 px-3 h-9 rounded-xl border text-xs font-medium transition-all disabled:opacity-50 ${
                  isActive
                    ? "border-sky-500 bg-sky-50 text-sky-700 shadow-sm"
                    : "border-border bg-card text-foreground hover:bg-muted/50"
                }`}
              >
                <span
                  className="inline-block h-4 w-4 rounded-full border border-border/40 shrink-0"
                  style={{ background: getAppearancePresetById(preset.id).swatch }}
                />
                {preset.label}
                {isActive && <span className="ml-1 text-sky-600 font-bold">✓</span>}
              </button>
            );
          })}
        </div>
      </div>

      </div>

      <style>{`
        .input{
          box-sizing:border-box;width:100%;min-height:36px;
          border:1px solid hsl(var(--border));
          border-radius:0.75rem;padding:0 12px;
          font-size:13px;line-height:1.35;
          font-family:ui-sans-serif,system-ui,sans-serif;
          background:#fff;
          color:hsl(218 30% 16%);
          transition:border-color .15s ease, box-shadow .15s ease;
        }
        .input::placeholder{color:hsl(218 16% 42%)}
        .input:hover{border-color:rgb(148 163 184 / 0.85)}
        .input:focus{
          outline:none;
          border-color:hsl(215 45% 55%);
          box-shadow:0 0 0 3px hsl(215 45% 55% / 0.15);
        }
        textarea.input{height:auto;min-height:88px;padding:10px 12px;line-height:1.45;font-family:'JetBrains Mono',ui-monospace,monospace;font-size:12px}
      `}</style>

      <PreviewModal open={previewOpen} onClose={() => setPreviewOpen(false)} doc={doc} />
    </AppShell>
  );
}

function slugifyPageLabel(value) {
  return String(value || "")
    .trim()
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 80);
}

function SectionPagesEditor({ value, onChange, sectionSlug }) {
  const { t } = useTranslation();
  const pages = Array.isArray(value) ? value : [];

  const setAt = (idx, patch) => {
    const next = pages.map((p, i) => (i === idx ? { ...p, ...patch } : p));
    onChange(next);
  };

  const move = (idx, dir) => {
    const to = idx + dir;
    if (to < 0 || to >= pages.length) return;
    const next = [...pages];
    const [row] = next.splice(idx, 1);
    next.splice(to, 0, row);
    onChange(next);
  };

  const remove = (idx) => {
    const next = pages.filter((_, i) => i !== idx);
    onChange(next);
  };

  const addPage = () => {
    const used = new Set(pages.map((p) => String(p.slug || "").trim().toLowerCase()).filter(Boolean));
    const base = "podstranka";
    let slug = base;
    let n = 2;
    while (used.has(slug)) {
      slug = `${base}-${n}`;
      n += 1;
    }
    onChange([
      ...pages,
      {
        id: `tmp-${Math.random().toString(36).slice(2, 10)}`,
        title: "Nová podstránka",
        slug,
        is_visible: true,
      },
    ]);
  };

  return (
    <div className="rounded-xl border border-border/70 bg-muted/20 p-3 space-y-2">
      <div className="text-[11px] text-muted-foreground">
        Hlavní stránka sekce zůstává vždy na <span className="font-mono">/s/{sectionSlug || "sekce"}</span>. Níže spravujete další rozklikávací podstránky.
      </div>
      {pages.length === 0 ? (
        <div className="text-xs text-slate-500 border border-dashed border-border/70 rounded-lg px-3 py-2">
          Zatím bez podstránek.
        </div>
      ) : (
        <div className="space-y-2">
          {pages.map((p, idx) => (
            <div key={p.id || `${p.slug}-${idx}`} className="rounded-lg border border-border/70 bg-white p-2.5 grid grid-cols-1 md:grid-cols-12 gap-2">
              <div className="md:col-span-4">
                <label className="text-[10px] text-slate-500">{t("cms.titleCs")}</label>
                <input
                  className="input mt-1"
                  value={p.title || ""}
                  onChange={(e) => {
                    const title = e.target.value;
                    const currentSlug = String(p.slug || "").trim();
                    const auto = currentSlug === "" || currentSlug === slugifyPageLabel(String(p.title || ""));
                    setAt(idx, {
                      title,
                      ...(auto ? { slug: slugifyPageLabel(title) } : {}),
                    });
                  }}
                />
                <label className="text-[10px] text-slate-500 mt-2 block">{t("cms.titleEn")}</label>
                <input
                  className="input mt-1"
                  value={p.title_en || ""}
                  onChange={(e) => setAt(idx, { title_en: e.target.value })}
                  placeholder={p.title || ""}
                />
              </div>
              <div className="md:col-span-3">
                <label className="text-[10px] text-slate-500">Slug</label>
                <input
                  className="input mt-1 font-mono"
                  value={p.slug || ""}
                  onChange={(e) => setAt(idx, { slug: slugifyPageLabel(e.target.value) })}
                />
              </div>
              <div className="md:col-span-2 flex items-end">
                <label className="inline-flex items-center gap-2 text-xs text-slate-700">
                  <input
                    type="checkbox"
                    checked={p.is_visible !== false}
                    onChange={(e) => setAt(idx, { is_visible: e.target.checked })}
                  />
                  Veřejná
                </label>
              </div>
              <div className="md:col-span-3 flex items-end justify-end gap-1.5">
                <button type="button" className="px-2 h-8 text-xs border border-border rounded-lg" onClick={() => move(idx, -1)} title="Posunout nahoru">↑</button>
                <button type="button" className="px-2 h-8 text-xs border border-border rounded-lg" onClick={() => move(idx, 1)} title="Posunout dolů">↓</button>
                <button type="button" className="px-2 h-8 text-xs border border-rose-300 text-rose-700 rounded-lg" onClick={() => remove(idx)} title="Smazat podstránku">Smazat</button>
              </div>
              <div className="md:col-span-12 text-[10px] text-slate-500 font-mono">
                URL: /s/{sectionSlug || "sekce"}/{p.slug || "slug"}
              </div>
            </div>
          ))}
        </div>
      )}
      <div>
        <button
          type="button"
          onClick={addPage}
          className="inline-flex items-center gap-1.5 px-2.5 h-8 text-xs border border-border rounded-lg hover:bg-muted/60"
        >
          <Plus className="h-3.5 w-3.5" /> Přidat podstránku
        </button>
      </div>
    </div>
  );
}

function CreateSectionModal({ onClose, onCreated }) {
  const { t } = useTranslation();
  const [name, setName] = useState("");
  const [nameEn, setNameEn] = useState("");
  const [icon, setIcon] = useState("Folder");
  const [subtitle, setSubtitle] = useState("");
  const [subtitleEn, setSubtitleEn] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  const submit = async () => {
    if (!name.trim()) {
      setErr("Zadejte název sekce.");
      return;
    }
    setBusy(true);
    setErr("");
    try {
      const { data } = await api.post("/sections", {
        name: name.trim(),
        name_en: nameEn.trim(),
        icon,
        subtitle,
        subtitle_en: subtitleEn.trim(),
        widgets: [],
      });
      onCreated(data.slug);
    } catch (e) {
      setErr(formatApiError(e.response?.data?.detail) || e.message || "Nepodařilo se vytvořit sekci");
    }
    setBusy(false);
  };

  return (
    <div className="fixed inset-0 bg-slate-900/40 backdrop-blur-sm z-40 grid place-items-center p-4">
      <div className="bg-card border border-border rounded-sm w-full max-w-lg shadow-xl">
        <div className="p-5 border-b border-border">
          <div className="kpi-label">Nová sekce</div>
          <h3 className="font-serif text-xl mt-1">Vytvořit sekci v menu</h3>
          <p className="text-xs text-muted-foreground mt-1">Např. „Banky", „Pojišťovny", „Družstevní záložny"…</p>
        </div>
        <div className="p-5 space-y-4">
          <LocalizedTextFields
            labelCs={t("cms.nameCs")}
            labelEn={t("cms.nameEn")}
            valueCs={name}
            valueEn={nameEn}
            onChangeCs={setName}
            onChangeEn={setNameEn}
            placeholderCs="Banky"
          />
          <div className="grid grid-cols-2 gap-3">
            <Field label="Ikona">
              <SectionIconPicker value={icon} onChange={setIcon} />
            </Field>
            <div className="md:col-span-1" />
          </div>
          <LocalizedTextFields
            labelCs={t("cms.subtitleCs")}
            labelEn={t("cms.subtitleEn")}
            valueCs={subtitle}
            valueEn={subtitleEn}
            onChangeCs={setSubtitle}
            onChangeEn={setSubtitleEn}
          />
          {err && <div className="border border-destructive/40 bg-destructive/5 text-destructive text-sm p-3 rounded-sm">{err}</div>}
        </div>
        <div className="p-5 border-t border-border flex justify-end gap-2">
          <button onClick={onClose} className="px-3 h-9 text-sm border border-border rounded-sm">Zrušit</button>
          <button onClick={submit} disabled={busy} className="btn-mint flex items-center gap-1.5 px-4 h-9 text-sm disabled:opacity-60">
            <Plus className="h-4 w-4" /> {busy ? "Vytvářím…" : "Vytvořit"}
          </button>
        </div>
      </div>
    </div>
  );
}

function Field({ label, children }) {
  return (
    <div>
      <label className="mb-1.5 block text-xs font-semibold text-foreground/90 tracking-wide">{label}</label>
      <div>{children}</div>
    </div>
  );
}
