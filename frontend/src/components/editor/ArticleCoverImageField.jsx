import React, { useRef, useState } from "react";
import { ImageIcon, Trash2, Upload } from "lucide-react";
import { toast } from "sonner";
import api, { formatApiErrorFromAxios } from "@/lib/api";

/**
 * Náhledový obrázek zprávy — upload nebo ruční URL.
 */
export default function ArticleCoverImageField({ value, onChange }) {
  const inputRef = useRef(null);
  const [uploading, setUploading] = useState(false);
  const url = String(value || "").trim();

  const uploadFile = async (file) => {
    if (!file) return;
    const formData = new FormData();
    formData.append("file", file);
    setUploading(true);
    try {
      const { data } = await api.post("/media/upload", formData, {
        headers: { "Content-Type": "multipart/form-data" },
      });
      if (!data?.url) throw new Error("Nahrání nevrátilo URL.");
      onChange(data.url);
      toast.success("Náhledový obrázek byl nahrán.");
    } catch (e) {
      toast.error(formatApiErrorFromAxios(e) || e.message || "Obrázek se nepodařilo nahrát.");
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="block text-sm">
      <span className="font-medium text-slate-700">Náhledový obrázek (volitelně)</span>
      <p className="mt-0.5 text-xs text-slate-500">
        Zobrazí se v seznamu a v detailu (web i mobilní aplikace).
      </p>
      <div className="mt-2 flex flex-wrap items-start gap-3">
        {url ? (
          <div className="relative shrink-0">
            <img
              src={url}
              alt="Náhled zprávy"
              className="h-28 w-44 rounded-lg border border-slate-200 object-cover bg-slate-100"
            />
            <button
              type="button"
              onClick={() => onChange("")}
              className="absolute -right-2 -top-2 inline-flex h-7 w-7 items-center justify-center rounded-full border border-slate-200 bg-white text-slate-600 shadow-sm hover:bg-red-50 hover:text-red-700"
              title="Odebrat obrázek"
            >
              <Trash2 className="h-3.5 w-3.5" />
            </button>
          </div>
        ) : (
          <div
            className="flex h-28 w-44 items-center justify-center rounded-lg border border-dashed border-slate-300 bg-slate-50 text-slate-400"
          >
            <ImageIcon className="h-8 w-8" strokeWidth={1.25} />
          </div>
        )}
        <div className="min-w-[200px] flex-1 space-y-2">
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              disabled={uploading}
              onClick={() => inputRef.current?.click()}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3 py-2 text-xs font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-60"
            >
              <Upload className="h-3.5 w-3.5" />
              {uploading ? "Nahrávám…" : "Nahrát obrázek"}
            </button>
          </div>
          <input
            className="w-full rounded-lg border border-slate-300 px-3 py-2 text-xs font-mono"
            placeholder="nebo vložte URL obrázku"
            value={url}
            onChange={(e) => onChange(e.target.value)}
          />
          <input
            ref={inputRef}
            type="file"
            accept="image/png,image/jpeg,image/webp,image/gif,image/svg+xml"
            className="hidden"
            onChange={(e) => {
              uploadFile(e.target.files?.[0]);
              e.target.value = "";
            }}
          />
        </div>
      </div>
    </div>
  );
}
