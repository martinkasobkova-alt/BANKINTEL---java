import { toPng } from "html-to-image";
import pptxgen from "pptxgenjs";
import { getPresentableDashboardWidgets } from "@/lib/dashboardChartContracts";

const SLIDE_W = 13.333;
const SLIDE_H = 7.5;
const MARGIN_X = 0.45;
const TITLE_H = 0.42;
const FOOTER_H = 0.22;

function safeFilename(value) {
  return String(value || "dashboard")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^A-Za-z0-9_-]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .slice(0, 70) || "dashboard";
}

function captureFilter(node) {
  if (!(node instanceof Element)) return true;
  if (node.dataset?.exportIgnore) return false;
  if (node.closest?.("[data-export-ignore='true']")) return false;
  return true;
}

function resolveWidgetCaptureNode(widgetId) {
  if (!widgetId || typeof document === "undefined") return null;
  const root = document.getElementById(`widget-${widgetId}`);
  if (!root) return null;
  return root.querySelector("[data-chart-export-root]") || root;
}

function fitIntoBox(srcW, srcH, boxX, boxY, boxW, boxH) {
  const width = Number(srcW) > 0 ? Number(srcW) : 1200;
  const height = Number(srcH) > 0 ? Number(srcH) : 700;
  const scale = Math.min(boxW / width, boxH / height);
  const w = width * scale;
  const h = height * scale;
  return {
    x: boxX + (boxW - w) / 2,
    y: boxY + (boxH - h) / 2,
    w,
    h,
  };
}

async function captureWidget(widget) {
  const node = resolveWidgetCaptureNode(widget?.id);
  if (!node) return null;
  const rect = node.getBoundingClientRect();
  const dataUrl = await toPng(node, {
    backgroundColor: "#ffffff",
    cacheBust: true,
    filter: captureFilter,
    pixelRatio: Math.min(3, Math.max(2, window.devicePixelRatio || 1.5)),
    skipAutoScale: false,
  });
  return {
    dataUrl,
    width: rect.width || 1200,
    height: rect.height || 700,
  };
}

function addFooter(slide, pageTitle, index, total) {
  slide.addText(`${pageTitle}  |  ${index}/${total}`, {
    x: MARGIN_X,
    y: SLIDE_H - 0.35,
    w: SLIDE_W - MARGIN_X * 2,
    h: FOOTER_H,
    fontSize: 8,
    color: "64748B",
    margin: 0,
  });
}

export async function exportDashboardPageToPptx({ widgets = [], pageTitle = "Dashboard" } = {}) {
  const chartWidgets = getPresentableDashboardWidgets(widgets);
  if (!chartWidgets.length) {
    throw new Error("Dashboard neobsahuje grafy vhodné pro export do prezentace.");
  }

  const pptx = new pptxgen();
  pptx.layout = "LAYOUT_WIDE";
  pptx.author = "Bankoapp";
  pptx.subject = pageTitle;
  pptx.title = pageTitle;
  pptx.company = "Bankoapp";
  pptx.lang = "cs-CZ";
  pptx.theme = {
    headFontFace: "Aptos Display",
    bodyFontFace: "Aptos",
    lang: "cs-CZ",
  };

  const generatedAt = new Date().toLocaleString("cs-CZ", {
    dateStyle: "medium",
    timeStyle: "short",
  });

  const cover = pptx.addSlide();
  cover.background = { color: "F8FAFC" };
  cover.addText(pageTitle, {
    x: 0.75,
    y: 2.35,
    w: 11.85,
    h: 0.8,
    fontSize: 30,
    bold: true,
    color: "0F172A",
    margin: 0,
  });
  cover.addText(`Dashboard jako prezentace | ${chartWidgets.length} grafu | ${generatedAt}`, {
    x: 0.78,
    y: 3.25,
    w: 11.5,
    h: 0.4,
    fontSize: 13,
    color: "475569",
    margin: 0,
  });

  let exported = 0;
  for (const widget of chartWidgets) {
    const capture = await captureWidget(widget);
    if (!capture?.dataUrl) continue;
    exported += 1;
    const slide = pptx.addSlide();
    slide.background = { color: "FFFFFF" };
    const title = String(widget?.title || widget?.data?.title || `Graf ${exported}`).trim();
    slide.addText(title, {
      x: MARGIN_X,
      y: 0.22,
      w: SLIDE_W - MARGIN_X * 2,
      h: TITLE_H,
      fontSize: 15,
      bold: true,
      color: "0F172A",
      margin: 0,
      fit: "shrink",
    });
    const imageBox = fitIntoBox(
      capture.width,
      capture.height,
      MARGIN_X,
      0.78,
      SLIDE_W - MARGIN_X * 2,
      SLIDE_H - 1.18,
    );
    slide.addImage({ data: capture.dataUrl, ...imageBox });
    addFooter(slide, pageTitle, exported, chartWidgets.length);
  }

  if (!exported) {
    throw new Error("Nepodařilo se zachytit žádný graf pro export.");
  }

  await pptx.writeFile({ fileName: `${safeFilename(pageTitle)}.pptx` });
  return { exported, total: chartWidgets.length };
}
