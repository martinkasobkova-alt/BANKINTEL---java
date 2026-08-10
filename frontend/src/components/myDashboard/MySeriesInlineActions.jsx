import React, { useState } from "react";
import { useNavigate } from "react-router-dom";
import { BookmarkPlus, GitCompare } from "lucide-react";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";
import { useAuth } from "@/contexts/AuthContext";
import { useFeatureAccess } from "@/hooks/useFeatureAccess";

/** @param {{ savePayload?: object | null, compareLeft?: object | null, compact?: boolean }} props */
export default function MySeriesInlineActions({ savePayload, compareLeft, compact = false }) {
  const { user, isSubscriber } = useAuth();
  const { allowed: canSaved, ready } = useFeatureAccess("saved_calculations");
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);

  if (!user || !isSubscriber || !ready || !canSaved) return null;
  if (!savePayload && !compareLeft) return null;

  const pad = compact ? "gap-1" : "gap-2";

  const onSave = async () => {
    if (!savePayload) {
      toast.error("Tento graf zatím nelze přímo uložit jako datovou řadu.");
      return;
    }
    setBusy(true);
    try {
      await api.post("/my-series", savePayload);
      toast.success("Řada byla uložena do Moje datové řady.");
    } catch (e) {
      const d = e.response?.data?.detail;
      toast.error(
        typeof d === "string"
          ? d
          : formatApiErrorFromAxios(e) || "Uložení se nepodařilo.",
      );
    } finally {
      setBusy(false);
    }
  };

  const onCompare = () => {
    if (!compareLeft?.ref) {
      toast.error(
        "Tento graf zatím nelze přímo porovnat. Nejdřív uložte konkrétní datovou řadu do Moje datové řady.",
      );
      return;
    }
    navigate("/my-data", {
      state: {
        compareLeft: { mode: "ref", ref: compareLeft.ref, label: compareLeft.label || "" },
      },
    });
  };

  const btn =
    "text-[10px] px-2 py-1 rounded-md border border-border/70 bg-card/90 hover:bg-muted/50 text-foreground/90 inline-flex items-center gap-1 disabled:opacity-50";

  return (
    <div className={`flex flex-wrap items-center ${pad} mt-1`}>
      {savePayload ? (
        <button type="button" className={btn} disabled={busy} onClick={onSave} title="Uložit kopii řady pro výpočty a porovnání">
          <BookmarkPlus className="h-3 w-3 shrink-0" aria-hidden />
          Přidat do mých datových řad
        </button>
      ) : null}
      {compareLeft ? (
        <button type="button" className={btn} disabled={busy} onClick={onCompare} title="Otevřít průvodce porovnání">
          <GitCompare className="h-3 w-3 shrink-0" aria-hidden />
          Porovnat s…
        </button>
      ) : null}
    </div>
  );
}
