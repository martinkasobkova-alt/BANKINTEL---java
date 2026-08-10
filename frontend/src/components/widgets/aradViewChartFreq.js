/** Chart frequency constants shared by AradView, admin canvas, editors — no React imports. */

export const ALL_FREQS = [
  { code: "D", label: "D", title: "Denní" },
  { code: "W", label: "W", title: "Týdenní" },
  { code: "M", label: "M", title: "Měsíční" },
  { code: "Q", label: "Q", title: "Čtvrtletní" },
  { code: "H", label: "H", title: "Pololetní" },
  { code: "Y", label: "Y", title: "Roční" },
];

export const FREQ_RANK = { D: 0, W: 1, M: 2, Q: 3, H: 4, Y: 5 };

/** Nejhrubší povolená frekvence z configu widgetu / stránky vůči nativnímu `nativeFreq`. */
export function resolveDefaultTargetFreq(configFreq, pageDefault, nativeFreq) {
  const cur = String(nativeFreq || "")
    .trim()
    .toUpperCase()
    .replace(/^A$/, "Y");
  const sourceRank = cur ? (FREQ_RANK[cur] ?? 99) : 99;
  const candidates = [
    (configFreq || "").trim().toUpperCase(),
    (pageDefault || "").trim().toUpperCase(),
    cur,
  ];
  for (const raw of candidates) {
    if (!raw || FREQ_RANK[raw] === undefined) continue;
    if (!cur || FREQ_RANK[raw] >= sourceRank) return raw;
  }
  return cur;
}
