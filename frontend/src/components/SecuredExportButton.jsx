import React, { useState } from "react";
import { Link } from "react-router-dom";
import api from "@/lib/api";
import { useFeatureAccess } from "@/hooks/useFeatureAccess";
import { Lock } from "lucide-react";

/**
 * GET export pod /api (např. export/dataset/ID.xlsx) přes axios + blob — respektuje cookies a 403.
 */
export async function downloadExportGet(relativePath, filename) {
  const path = String(relativePath || "").replace(/^\//, "");
  const res = await api.get(path, { responseType: "blob" });
  const blob = new Blob([res.data], { type: res.headers["content-type"] || "application/octet-stream" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename || "export";
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 500);
}

/**
 * Tlačítko místo přímého <a href> na /api/export/…
 * @param {string} relativePath — např. "export/dataset/xxx.xlsx"
 */
export default function SecuredExportButton({ relativePath, filename, className, children, title, testid }) {
  const { allowed, message, loading, ready } = useFeatureAccess("export_data");
  const [busy, setBusy] = useState(false);
  const disabled = loading || !allowed || busy;

  const onClick = async (e) => {
    e.preventDefault();
    if (disabled) return;
    setBusy(true);
    try {
      await downloadExportGet(relativePath, filename);
    } catch (err) {
      const st = err?.response?.status;
      if (st === 403) {
        window.alert("Export není s vaším účtem k dispozici. Více v aplikaci v sekci Předplatné (/predplatne).");
      } else {
        window.alert("Export se nepodařilo stáhnout.");
      }
    } finally {
      setBusy(false);
    }
  };

  if (ready && !allowed) {
    return (
      <span className="inline-flex flex-col gap-0.5">
        <button
          type="button"
          disabled
          className={className + " opacity-50 cursor-not-allowed"}
          title={message || "Export není k dispozici"}
          data-testid={testid}
        >
          <Lock className="h-3.5 w-3.5 inline-block mr-1 text-amber-600" />
          {children}
        </button>
        <span className="text-[9px] text-slate-500 max-w-[200px] leading-tight">
          {message}{" "}
          <Link to="/predplatne" className="text-[hsl(var(--primary))] underline">
            Předplatné
          </Link>
        </span>
      </span>
    );
  }

  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={className}
      title={title || (loading ? message || "Kontrola oprávnění…" : "Stáhnout")}
      data-testid={testid}
    >
      {children}
    </button>
  );
}
