import React, { useState } from "react";
import { ArrowUpCircle, GripVertical, X } from "lucide-react";

function formatRelationshipWeight(value) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric) || numeric <= 0) return null;
  return numeric.toFixed(2);
}

function relatedRowLabel(row) {
  if (!row || typeof row !== "object") return "";
  return String(row.sector_name_cs || row.name_cs || row.name || row.sector_id || row.label || "").trim();
}

function relatedRowBrief(row) {
  const name = relatedRowLabel(row);
  const rel = String(row?.relationship_type || "").trim();
  return rel ? `${name} (${rel})` : name;
}

function sourceBadge(source) {
  if (source === "manual") return "Ručně přidaný";
  if (source === "topic") return "Volné téma";
  return "Doporučený";
}

function normalizeRelatedRows(relatedRows, relatedSegments) {
  const rows = Array.isArray(relatedRows) ? relatedRows : [];
  if (rows.length) {
    return rows
      .map((row) => ({
        key: String(row?.key || row?.sector_id || relatedRowLabel(row) || "").trim(),
        label: relatedRowLabel(row),
        brief: row?.brief || relatedRowBrief(row),
        weight: row?.weight,
        rank: row?.rank,
        priority_tier: row?.priority_tier,
        reason: String(row?.reason_cs || row?.reason || "").trim(),
        source: String(row?.source || "predefined").trim(),
      }))
      .filter((row) => row.label);
  }
  return (Array.isArray(relatedSegments) ? relatedSegments : [])
    .map((label) => ({
      key: String(label || "").trim().toLowerCase(),
      label: String(label || "").trim(),
      brief: String(label || "").trim(),
      weight: null,
      rank: null,
      priority_tier: "",
      reason: "",
      source: "manual",
    }))
    .filter((row) => row.label);
}

export default function ManagerSectorHierarchyEditor({
  primarySector = "",
  relatedSegments = [],
  relatedRows = null,
  editable = false,
  onPromoteRelated,
  onRemoveRelated,
  onReorderRelated,
  promoteDisabled = false,
  removeDisabled = false,
  reorderDisabled = false,
  className = "",
}) {
  const primary = String(primarySector || "").trim();
  const items = normalizeRelatedRows(relatedRows, relatedSegments);
  const [draggingKey, setDraggingKey] = useState("");
  const [dragOverKey, setDragOverKey] = useState("");

  const canReorder = editable && typeof onReorderRelated === "function" && !reorderDisabled;

  const finishDrag = () => {
    setDraggingKey("");
    setDragOverKey("");
  };

  const handleDropOn = (targetKey) => {
    const fromKey = draggingKey;
    finishDrag();
    if (!fromKey || !targetKey || fromKey === targetKey) return;
    onReorderRelated(fromKey, targetKey);
  };

  if (!primary && !items.length) return null;

  return (
    <div className={`grid gap-3 md:grid-cols-2 ${className}`.trim()}>
      {primary ? (
        <div className="rounded-xl border border-emerald-200 bg-emerald-50/70 px-4 py-3 space-y-2">
          <div className="text-[11px] font-semibold uppercase tracking-wide text-emerald-800">Hlavní sektor</div>
          <div className="text-sm font-semibold text-emerald-950">{primary}</div>
          <p className="text-[11px] text-emerald-900/90 leading-relaxed">
            Tento sektor je hlavní osa vyhodnocení. Jeho řady a signály mají v analýze nejvyšší váhu.
          </p>
        </div>
      ) : null}
      <div className="rounded-xl border border-sky-200 bg-sky-50/70 px-4 py-3 space-y-2 md:col-span-1">
        <div className="text-[11px] font-semibold uppercase tracking-wide text-sky-800">
          Související segmenty a témata
        </div>
        <p className="text-[11px] text-sky-900/90 leading-relaxed">
          Přetáhněte položky pro změnu priority — rank a váha se přepočítají a vstoupí do finálního skóringu.
        </p>
        {items.length ? (
          <div className="grid gap-2">
            {items.map((row) => {
              const rowKey = row.key || row.label;
              const isDragging = draggingKey === rowKey;
              const isDragOver = dragOverKey === rowKey && draggingKey && draggingKey !== rowKey;
              return (
                <div
                  key={rowKey}
                  className={`rounded-lg border bg-white/90 px-3 py-2 space-y-1.5 transition-colors ${
                    isDragOver
                      ? "border-sky-500 ring-2 ring-sky-200"
                      : isDragging
                        ? "border-sky-300 opacity-60"
                        : "border-sky-200/80"
                  }`}
                  title={row.reason || undefined}
                  onDragOver={(event) => {
                    if (!canReorder) return;
                    event.preventDefault();
                    setDragOverKey(rowKey);
                  }}
                  onDragLeave={() => {
                    if (dragOverKey === rowKey) setDragOverKey("");
                  }}
                  onDrop={(event) => {
                    if (!canReorder) return;
                    event.preventDefault();
                    handleDropOn(rowKey);
                  }}
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex min-w-0 flex-1 items-start gap-2">
                      {canReorder ? (
                        <button
                          type="button"
                          className="mt-0.5 inline-flex h-7 w-7 shrink-0 cursor-grab items-center justify-center rounded-md border border-slate-200 bg-slate-50 text-slate-500 hover:bg-slate-100 active:cursor-grabbing"
                          draggable
                          onDragStart={(event) => {
                            event.dataTransfer.effectAllowed = "move";
                            event.dataTransfer.setData("text/plain", rowKey);
                            setDraggingKey(rowKey);
                          }}
                          onDragEnd={finishDrag}
                          aria-label={`Přesunout ${row.label}`}
                          title="Přetáhnout pro změnu pořadí"
                        >
                          <GripVertical className="h-4 w-4" />
                        </button>
                      ) : null}
                      <div className="min-w-0 flex-1 space-y-1">
                        <div className="text-[11px] font-medium text-sky-950">{row.brief}</div>
                        <span className="inline-flex px-1.5 py-0.5 rounded bg-slate-50 border border-slate-200 text-[10px] text-slate-700">
                          {sourceBadge(row.source)}
                        </span>
                      </div>
                    </div>
                    <div className="flex shrink-0 items-center gap-1">
                      {editable && typeof onPromoteRelated === "function" ? (
                        <button
                          type="button"
                          className="inline-flex items-center gap-1 rounded-lg border border-emerald-300 bg-emerald-50 px-2 py-1 text-[10px] font-medium text-emerald-900 hover:bg-emerald-100 disabled:opacity-50"
                          disabled={promoteDisabled}
                          onClick={() => onPromoteRelated(row.label)}
                          title={`Nastavit „${row.label}“ jako hlavní sektor`}
                        >
                          <ArrowUpCircle className="h-3.5 w-3.5" />
                          Hlavní
                        </button>
                      ) : null}
                      {editable && typeof onRemoveRelated === "function" ? (
                        <button
                          type="button"
                          className="inline-flex h-7 w-7 items-center justify-center rounded-lg border border-slate-300 bg-white text-slate-600 hover:bg-slate-50 disabled:opacity-50"
                          disabled={removeDisabled}
                          onClick={() => onRemoveRelated(row)}
                          aria-label={`Odebrat ${row.label}`}
                          title={`Odebrat „${row.label}“`}
                        >
                          <X className="h-3.5 w-3.5" />
                        </button>
                      ) : null}
                    </div>
                  </div>
                  <div className="flex flex-wrap gap-1.5 text-[10px]">
                    {formatRelationshipWeight(row.weight) ? (
                      <span className="px-1.5 py-0.5 rounded bg-sky-50 border border-sky-200 text-sky-900">
                        váha {formatRelationshipWeight(row.weight)}
                      </span>
                    ) : null}
                    {Number.isInteger(Number(row.rank)) && Number(row.rank) > 0 ? (
                      <span className="px-1.5 py-0.5 rounded bg-slate-50 border border-slate-200 text-slate-700">
                        rank {Number(row.rank)}
                      </span>
                    ) : null}
                    {String(row.priority_tier || "").trim() ? (
                      <span className="px-1.5 py-0.5 rounded bg-indigo-50 border border-indigo-200 text-indigo-900">
                        {String(row.priority_tier).trim()}
                      </span>
                    ) : null}
                  </div>
                </div>
              );
            })}
          </div>
        ) : (
          <p className="text-[11px] text-sky-900/80 italic">
            Žádné související segmenty — analýza poběží jen pro hlavní sektor.
          </p>
        )}
      </div>
    </div>
  );
}
