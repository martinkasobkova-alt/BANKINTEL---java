import React, { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Megaphone, Save, Eye, EyeOff } from "lucide-react";
import { toast } from "sonner";
import api, { formatApiError } from "@/lib/api";
import AppShell from "@/components/layout/AppShell";
import AdConfigEditor from "@/components/editor/AdConfigEditor";
import AdWidget from "@/components/widgets/AdWidget";
import { LoadingBlock } from "@/components/ui/loading";

/**
 * Admin · konfigurace globálních reklamních slotů v menu.
 *
 * Aplikace má dva globální sloty, které jsou viditelné na **všech** veřejných
 * stránkách (homepage, sekce, vyhledávání, …):
 *
 *   - `sidebar` … pod navigací v levé liště (ne vedle lišty)
 *   - `topbar`  … pruh v horní hlavičce (nad titulkem / vyhledáváním)
 *
 * Každý slot má vlastní zapínač + obsah (obrázek / markdown / HTML snippet),
 * uloženo v MongoDB v `settings.ad_slots`. Frontend AppShell si je tahá
 * z `GET /api/ad-slots` při bootu.
 */
const SLOTS = [
  {
    id: "sidebar",
    title: "Reklama pod levým menu",
    description:
      "Zobrazí se v dolní části levé lišty pod navigací. Obrázek se vejde do rámečku (nepřetahuje se na bok). Vhodné spíš širší/nižší banner (např. 280×120).",
    layout: "sidebar",
    previewClass: "h-52 w-full max-w-[260px]",
  },
  {
    id: "topbar",
    title: "Horní banner (v hlavičce)",
    description:
      "Pás nahoře v hlavní lištce (nad názvem stránky a hledáním). Vhodné: 970×90 nebo 728×90.",
    layout: "horizontal",
    previewClass: "h-[104px] w-full max-w-3xl",
  },
];

const EMPTY_SLOT = {
  enabled: false,
  kind: "image",
  image_url: "",
  link_url: "",
  alt: "",
  content: "",
  html: "",
  image_mode: "single",
  slides: [],
  carousel_interval_sec: 5,
};

export default function AdSlotsPage() {
  const { t } = useTranslation();
  const [slots, setSlots] = useState({ sidebar: { ...EMPTY_SLOT }, topbar: { ...EMPTY_SLOT } });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { data } = await api.get("/ad-slots");
        if (cancelled) return;
        setSlots({
          sidebar: { ...EMPTY_SLOT, ...(data?.sidebar || {}) },
          topbar: { ...EMPTY_SLOT, ...(data?.topbar || {}) },
        });
      } catch (e) {
        toast.error(formatApiError(e?.response?.data?.detail) || e.message);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const patchSlot = (id, patch) => {
    setSlots((prev) => ({ ...prev, [id]: { ...prev[id], ...patch } }));
  };

  const save = async (id) => {
    setSaving(id);
    try {
      const { data } = await api.put(`/ad-slots/${id}`, slots[id]);
      setSlots({
        sidebar: { ...EMPTY_SLOT, ...(data?.sidebar || {}) },
        topbar: { ...EMPTY_SLOT, ...(data?.topbar || {}) },
      });
      toast.success("Slot uložen.");
    } catch (e) {
      toast.error(formatApiError(e?.response?.data?.detail) || e.message);
    } finally {
      setSaving(null);
    }
  };

  return (
    <AppShell title={t("pages.admin.adsTitle")} subtitle={t("pages.admin.adsSubtitle")}>
      <div className="max-w-6xl mx-auto space-y-8 copper-text-fix-scope">
        <div className="rounded-2xl border border-border bg-card/80 p-5 flex items-start gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-violet-500/15 text-violet-700 shrink-0">
            <Megaphone className="h-5 w-5" />
          </div>
          <div className="text-sm text-foreground/90">
            <div className="font-semibold text-foreground">Globální reklamní prostor</div>
            <div className="text-xs text-muted-foreground mt-1">
              Tyto dva sloty se zobrazují na všech veřejných stránkách. Pokud potřebujete
              reklamu jen na konkrétní stránce, přidejte „Inzerce" jako widget v editoru.
            </div>
          </div>
        </div>

        {loading ? (
          <LoadingBlock label="Načítám konfiguraci slotů…" minHeightClass="min-h-[120px]" showSkeletonLines skeletonRows={3} />
        ) : (
          SLOTS.map((slotMeta) => {
            const slot = slots[slotMeta.id];
            const dirty = saving === slotMeta.id;
            return (
              <section
                key={slotMeta.id}
                className="rounded-2xl border border-border bg-card/85 p-5 shadow-sm"
              >
                <header className="flex items-start justify-between gap-4 flex-wrap pb-4 border-b border-border/60">
                  <div className="min-w-0">
                    <h2 className="font-serif text-xl text-foreground">{slotMeta.title}</h2>
                    <div className="text-xs text-muted-foreground mt-1">{slotMeta.description}</div>
                  </div>
                  <div className="flex items-center gap-3">
                    <label className="inline-flex items-center gap-2 text-xs font-medium text-foreground/90 cursor-pointer">
                      <input
                        type="checkbox"
                        className="h-4 w-4 accent-[hsl(var(--primary-deep))]"
                        checked={!!slot.enabled}
                        onChange={(e) => patchSlot(slotMeta.id, { enabled: e.target.checked })}
                      />
                      {slot.enabled ? (
                        <span className="inline-flex items-center gap-1 text-emerald-700">
                          <Eye className="h-3.5 w-3.5" /> Aktivní
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1 text-muted-foreground">
                          <EyeOff className="h-3.5 w-3.5" /> Skrytý
                        </span>
                      )}
                    </label>
                    <button
                      type="button"
                      onClick={() => save(slotMeta.id)}
                      disabled={dirty}
                      className="inline-flex items-center gap-1.5 h-9 px-4 text-sm rounded-md bg-[hsl(var(--primary-deep))] text-white hover:opacity-95 disabled:opacity-60"
                    >
                      <Save className="h-3.5 w-3.5" />
                      {dirty ? "Ukládám…" : "Uložit"}
                    </button>
                  </div>
                </header>

                <div className="grid grid-cols-1 md:grid-cols-12 gap-6 pt-5">
                  <div className="md:col-span-7">
                    <AdConfigEditor
                      cfg={slot}
                      onPatch={(patch) => patchSlot(slotMeta.id, patch)}
                    />
                  </div>
                  <div className="md:col-span-5">
                    <div className="text-[10px] uppercase tracking-[0.14em] text-muted-foreground font-semibold mb-2">
                      Náhled (jak to uvidí návštěvník)
                    </div>
                    <div
                      className={`rounded-xl border border-border bg-muted/32 overflow-hidden mx-auto ${slotMeta.previewClass}`}
                    >
                      <AdWidget
                        data={slot}
                        slotMode
                        layout={slotMeta.id === "sidebar" ? "sidebar" : "horizontal"}
                      />
                    </div>
                  </div>
                </div>
              </section>
            );
          })
        )}
      </div>
    </AppShell>
  );
}
