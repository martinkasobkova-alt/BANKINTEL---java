import { Loader2 } from "lucide-react";

function formatCardValue(card) {
  if (card?.last_value_display) return card.last_value_display;
  const raw = card?.last_value;
  if (raw == null || raw === "") return null;
  if (typeof raw === "number") {
    return String(raw).replace(".", ",");
  }
  return String(raw);
}

function ManagerQuickDataCard({ card }) {
  const title = String(card?.indicator_name || "Ukazatel").trim();
  const geoLabel = String(card?.geo_label || card?.geo || "").trim();
  const sourceLabel = String(card?.source_label || card?.source || "").trim();
  const lastDate = String(card?.last_date || "").trim();
  const valueText = formatCardValue(card);
  const unit = String(card?.unit || "").trim();
  const loading = Boolean(card?.loading) && !card?.fetch_ok;
  const hasValue = valueText != null && card?.fetch_ok;

  return (
    <article className="rounded-xl border border-slate-200 bg-white px-4 py-3 space-y-2 shadow-sm">
      <h4 className="text-sm font-semibold text-slate-900 leading-snug">{title}</h4>
      <p className="text-[11px] text-slate-600">
        {[geoLabel, sourceLabel, lastDate].filter(Boolean).join(" · ")}
      </p>
      {loading ? (
        <div className="flex items-center gap-2 text-sm text-slate-600">
          <Loader2 className="h-4 w-4 animate-spin shrink-0" />
          Data se načítají…
        </div>
      ) : hasValue ? (
        <>
          <p className="text-sm text-slate-800">
            <span className="font-medium">Hodnota:</span> {valueText}
            {unit ? ` ${unit}` : ""}
          </p>
          {card?.change_unavailable ? (
            <p className="text-sm text-slate-600 italic">{card?.change_unavailable_message || "meziroční změna není dostupná"}</p>
          ) : card?.change_display ? (
            <p className="text-sm text-slate-800">
              <span className="font-medium">{card?.change_label || "Změna"}:</span> {card.change_display}
            </p>
          ) : null}
          {card?.interpretation ? (
            <p className="text-sm text-slate-700 leading-relaxed">
              <span className="font-medium">Interpretace:</span> {card.interpretation}
            </p>
          ) : null}
        </>
      ) : (
        <p className="text-sm text-amber-800">
          {card?.status_message || "Data se nepodařilo načíst pro tento ukazatel."}
        </p>
      )}
    </article>
  );
}

export default function ManagerQuickDataPreview({ preview, loading = false, showNotice = true }) {
  const cards = Array.isArray(preview?.cards) ? preview.cards : [];
  if (!cards.length && !loading) return null;

  return (
    <section className="rounded-xl border border-emerald-200/80 bg-emerald-50/40 px-4 py-4 space-y-3">
      <div className="space-y-1">
        <h3 className="text-base font-semibold text-emerald-950">Rychlý datový náhled</h3>
        <p className="text-sm text-emerald-900/90">Co z dat zatím vidíme?</p>
        {showNotice ? (
          <p className="text-[11px] text-emerald-900/75">
            {preview?.notice || "Toto je rychlý datový náhled. Detailní interpretace se dopočítává."}
          </p>
        ) : null}
      </div>
      {loading && !cards.length ? (
        <div className="flex items-center gap-2 text-sm text-slate-700">
          <Loader2 className="h-4 w-4 animate-spin shrink-0" />
          Načítám reálné hodnoty hlavních ukazatelů…
        </div>
      ) : (
        <div className="grid gap-3 md:grid-cols-2">
          {cards.map((card) => (
            <ManagerQuickDataCard key={`${card?.slot_id || card?.indicator_name}-${card?.geo || ""}`} card={card} />
          ))}
        </div>
      )}
    </section>
  );
}
