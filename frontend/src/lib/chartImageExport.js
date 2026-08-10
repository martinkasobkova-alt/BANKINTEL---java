import { toPng, toJpeg } from "html-to-image";

/** Prefer explicit export region (graf + patička), ne celou kartu s toolbar hlavičkou. */
function resolveExportRoot(targetNode) {
  if (!targetNode) return null;
  const el = targetNode instanceof Element ? targetNode : null;
  if (!el) return null;
  return el.closest("[data-chart-export-root]") || el;
}

function chartCaptureFilter(node) {
  if (node instanceof Element && node.dataset?.exportIgnore) return false;
  return true;
}

/**
 * Vrátí data URL snímku oblasti grafu (pro PDF / vlastní stažení).
 * @param {Element} targetNode – např. chartCaptureRef.current (`data-chart-export-root`)
 * @param {"png"|"jpg"} format
 */
export async function captureChartCardAsDataUrl(targetNode, format = "png") {
  const exportRoot = resolveExportRoot(targetNode);
  if (!exportRoot) throw new Error("Graf pro export nebyl nalezen.");

  const dpr = typeof window !== "undefined" && window.devicePixelRatio ? window.devicePixelRatio : 1;
  const pixelRatio = Math.min(4, Math.max(3, dpr * 1.5));
  const opts = {
    pixelRatio,
    filter: chartCaptureFilter,
    skipAutoScale: false,
    ...(format === "jpg" ? { backgroundColor: "#ffffff" } : {}),
  };

  try {
    return format === "jpg"
      ? await toJpeg(exportRoot, { ...opts, quality: 0.98 })
      : await toPng(exportRoot, opts);
  } catch (err) {
    console.warn("html-to-image first attempt failed, retrying…", err);
    return format === "jpg"
      ? await toJpeg(exportRoot, { ...opts, quality: 0.98, cacheBust: true })
      : await toPng(exportRoot, { ...opts, cacheBust: true });
  }
}

/**
 * Exportuje oblast grafu (`data-chart-export-root` / předaný uzel ref), bez hlavičky
 * karty s ovládacími prvky. Vyšší pixelRatio pro ostřejší PNG/JPG.
 *
 * @param {Element} targetNode  – chartCaptureRef.current (chart-body-slot div)
 * @param {"png"|"jpg"} format
 * @param {string} filenameBase
 */
export async function exportChartNodeAsImage(targetNode, format = "png", filenameBase = "chart") {
  const dataUrl = await captureChartCardAsDataUrl(targetNode, format);
  const ext = format === "jpg" ? "jpg" : "png";
  const safeName = (filenameBase || "chart").replace(/[^A-Za-z0-9_-]+/g, "_").slice(0, 60) || "chart";
  const a = document.createElement("a");
  a.href = dataUrl;
  a.download = `${safeName}.${ext}`;
  document.body.appendChild(a);
  a.click();
  a.remove();
}
