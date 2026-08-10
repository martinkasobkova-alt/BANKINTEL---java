import {
  LineChart as LineIcon,
  BarChart3 as BarIcon,
  AreaChart as AreaIcon,
  PieChart as PieIcon,
  CircleDot,
  Globe,
  Users,
  Smile,
} from "lucide-react";

/** Všechny typy grafů v AradView / dashboardu. */
export const CHART_KINDS = [
  { id: "line", label: "Čára", Icon: LineIcon, title: "Spojnicový graf" },
  { id: "bar", label: "Sloupec", Icon: BarIcon, title: "Sloupcový graf" },
  { id: "area", label: "Plocha", Icon: AreaIcon, title: "Plošný graf" },
  { id: "pie", label: "Koláč", Icon: PieIcon, title: "Koláčový graf (podíl po obdobích)" },
  { id: "dot", label: "Body", Icon: CircleDot, title: "Graf s body (dot chart)" },
  { id: "geo_map", label: "Mapa", Icon: Globe, title: "Mapa zemí (ČR / Evropa / svět)" },
  { id: "pictogram", label: "Pictogram", Icon: Users, title: "Pictogram — ikony reprezentují množství" },
  { id: "icon_chart", label: "Ikony", Icon: Smile, title: "Graf s emotikony / ikonami u kategorií" },
];

export const CHART_KIND_IDS = new Set(CHART_KINDS.map((k) => k.id));

/** Typy mimo Recharts (vlastní renderer). */
export const CUSTOM_CHART_KINDS = new Set(["geo_map", "pictogram", "icon_chart"]);

export const MAP_REGION_OPTIONS = [
  { id: "cz", label: "Česko" },
  { id: "europe", label: "Evropa" },
  { id: "world", label: "Svět" },
];

export function normalizeChartKind(value, fallback = "line") {
  const k = String(value || "").trim().toLowerCase();
  if (k === "scatter") return "dot";
  return CHART_KIND_IDS.has(k) ? k : fallback;
}

/** Směr řady ikon v icon_chart / pictogram. */
export const ICON_ORIENTATIONS = [
  { id: "horizontal", label: "Vodorovně" },
  { id: "vertical", label: "Svisle" },
];

export function normalizeIconOrientation(value, fallback = "horizontal") {
  return String(value || "").toLowerCase() === "vertical" ? "vertical" : fallback;
}

export function isCustomChartKind(kind) {
  return CUSTOM_CHART_KINDS.has(String(kind || "").toLowerCase());
}
