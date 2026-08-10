/**
 * Centrální registry vzhledu aplikace (pozadí + design tokeny).
 * Hodnoty --* bez obalu hsl() — formát jako v index.css („H S% L%“).
 */

export const APPEARANCE_STORAGE_KEY = "bankoapp.backgroundTheme";

export const DEFAULT_APPEARANCE_ID = "blue";

/**
 * Migrace starých uložených id na nová kurátorská schémata (stejné rozhraní, nové kombinace).
 */
const STORED_ID_ALIASES = {
  cream: "navy-champagne",
  rose: "blush-cream",
  "light-blue": "soft-blue-ivory",
  "ice-blue": "slate-ice-blue",
  mint: "mint-sand",
  "sand-champagne": "navy-champagne",
  "blush-pink": "blush-cream",
  lavender: "lavender-pearl",
  "pearl-grey": "lilac-silver",
  "soft-rose": "dusty-pink-taupe",
  "neutral-frost": "soft-blue-ivory",
  slate: "slate-ice-blue",
  "sky-mint": "mint-sand",
  "blue-pink": "nude-rose-gold",
  "sand-rose": "nude-rose-gold",
  "mint-blue": "mint-sand",
  "lavender-pink": "lilac-silver",
  "white-blue-tint": "soft-blue-ivory",
  "pearl-mint": "mint-sand",
  "frost-lavender": "lavender-pearl",
};

export const APPEARANCE_ROOT_DEFAULTS = {
  background: "205 70% 90%",
  foreground: "218 55% 16%",
  card: "0 0% 100%",
  "card-foreground": "218 55% 16%",
  popover: "205 45% 98%",
  "popover-foreground": "218 55% 16%",
  primary: "202 90% 52%",
  "primary-foreground": "0 0% 100%",
  "primary-deep": "218 65% 18%",
  title: "208 80% 38%",
  "primary-soft": "205 78% 88%",
  secondary: "208 75% 62%",
  "secondary-foreground": "0 0% 100%",
  muted: "205 48% 88%",
  "muted-foreground": "218 25% 38%",
  accent: "36 85% 90%",
  "accent-foreground": "28 55% 28%",
  destructive: "354 70% 58%",
  "destructive-foreground": "0 0% 100%",
  border: "205 42% 72%",
  input: "205 42% 72%",
  ring: "202 90% 52%",
  success: "165 70% 45%",
  "success-foreground": "0 0% 100%",
  warning: "36 95% 58%",
  "warning-foreground": "0 0% 100%",
  "chart-1": "202 90% 52%",
  "chart-2": "218 65% 28%",
  "chart-3": "208 75% 48%",
  "chart-4": "205 65% 78%",
  "chart-5": "225 55% 65%",
  "chart-6": "188 65% 52%",
  "overlay-backdrop": "218 30% 22%",
  "table-header-bg": "205 76% 96%",
  /** Popisek hlavičky tabulky; u tmavého canvasu přepíše světlý `--primary-deep` měděným odstínem. */
  "table-header-fg": "218 65% 18%",
  "table-body-text": "218 30% 24%",
};

/** @typedef {"solid"|"gradient"|"mixed"} AppearanceThemeType */

/**
 * @typedef {object} AppearancePreset
 * @property {string} id
 * @property {string} label
 * @property {AppearanceThemeType} type
 * @property {string} swatch
 * @property {string} page
 * @property {string} panel
 * @property {Partial<typeof APPEARANCE_ROOT_DEFAULTS>} [vars]
 * @property {"light"|"dark"} [canvasTone] — `"dark"`: světlý `--foreground` na pozadí; nastaví `documentElement` `data-bankoapp-canvas=dark` pro čitelnost světlých panelů v editorech.
 */

/** Hlavní výběr v UI — výrazně odlišené kombinace (ne jen pastelové kolečka). */
export const PRIMARY_APPEARANCE_PRESETS = [
  {
    id: "blue",
    label: "Klasická modrá",
    type: "gradient",
    swatch: "linear-gradient(135deg, hsl(205 70% 90%), hsl(205 58% 84%))",
    page: "linear-gradient(180deg, hsl(205 70% 90%) 0%, hsl(205 58% 84%) 100%)",
    panel: "linear-gradient(180deg, hsl(205 76% 96%) 0%, hsl(205 78% 94%) 88%)",
    vars: {},
  },
  {
    id: "soft-blue-ivory",
    label: "Jemná modrá · slonovina",
    type: "gradient",
    swatch: "linear-gradient(135deg, hsl(207 62% 97%), hsl(205 54% 90%))",
    page: "linear-gradient(178deg, hsl(210 52% 98%) 0%, hsl(207 52% 91%) 100%)",
    panel: "linear-gradient(180deg, hsl(207 62% 99%) 0%, hsl(210 52% 95%) 92%)",
    vars: {
      background: "210 52% 95%",
      foreground: "220 42% 16%",
      card: "40 52% 99%",
      "card-foreground": "220 38% 16%",
      popover: "210 42% 99%",
      primary: "210 74% 48%",
      "primary-foreground": "0 0% 99%",
      "primary-deep": "218 72% 20%",
      title: "213 76% 32%",
      "primary-soft": "208 74% 90%",
      secondary: "200 72% 45%",
      "secondary-foreground": "0 0% 99%",
      muted: "207 42% 90%",
      "muted-foreground": "220 28% 36%",
      accent: "40 74% 90%",
      "accent-foreground": "28 52% 24%",
      border: "207 38% 80%",
      input: "207 38% 80%",
      ring: "210 74% 48%",
      "table-header-bg": "210 62% 97%",
    },
  },
  {
    id: "nude-rose-gold",
    label: "Nude · růže · zlato",
    type: "mixed",
    swatch: "linear-gradient(125deg, hsl(22 38% 94%), hsl(350 48% 90%) 55%, hsl(38 72% 88%))",
    page: "linear-gradient(145deg, hsl(24 40% 95%) 0%, hsl(350 52% 91%) 50%, hsl(40 76% 90%) 100%)",
    panel: "linear-gradient(135deg, hsl(26 62% 98%) 0%, hsl(350 58% 96%) 100%)",
    vars: {
      background: "28 42% 93%",
      foreground: "342 42% 16%",
      primary: "350 76% 44%",
      "primary-foreground": "0 0% 98%",
      "primary-deep": "342 74% 20%",
      title: "350 74% 32%",
      "primary-soft": "350 92% 90%",
      secondary: "38 94% 48%",
      "secondary-foreground": "28 60% 12%",
      accent: "328 74% 88%",
      "accent-foreground": "325 72% 24%",
      muted: "32 54% 88%",
      "muted-foreground": "330 30% 32%",
      border: "332 42% 80%",
      input: "332 42% 80%",
      ring: "38 94% 48%",
      "chart-2": "38 94% 45%",
      "table-header-bg": "32 60% 95%",
    },
  },
  {
    id: "carnival-pop",
    label: "Karneval · pop-art",
    type: "mixed",
    swatch:
      "linear-gradient(125deg, hsl(334 95% 56%), hsl(20 100% 58%) 30%, hsl(48 100% 62%) 52%, hsl(166 82% 46%) 74%, hsl(198 100% 52%) 100%)",
    page:
      "linear-gradient(145deg, hsl(332 100% 96%) 0%, hsl(24 100% 91%) 28%, hsl(49 100% 88%) 52%, hsl(167 74% 88%) 76%, hsl(196 96% 90%) 100%)",
    panel: "linear-gradient(135deg, hsl(0 0% 100%) 0%, hsl(330 100% 98%) 48%, hsl(190 100% 98%) 100%)",
    vars: {
      background: "334 88% 95%",
      foreground: "244 46% 16%",
      card: "0 0% 100%",
      "card-foreground": "244 46% 16%",
      popover: "330 100% 98%",
      "popover-foreground": "244 46% 16%",
      primary: "334 95% 50%",
      "primary-foreground": "0 0% 100%",
      "primary-deep": "244 72% 20%",
      title: "255 80% 28%",
      "primary-soft": "329 100% 89%",
      secondary: "24 100% 52%",
      "secondary-foreground": "0 0% 100%",
      accent: "187 92% 88%",
      "accent-foreground": "192 92% 22%",
      muted: "44 100% 90%",
      "muted-foreground": "252 28% 34%",
      border: "326 70% 80%",
      input: "326 70% 80%",
      ring: "198 100% 48%",
      "chart-1": "334 95% 50%",
      "chart-2": "24 100% 52%",
      "chart-3": "48 100% 48%",
      "chart-4": "166 82% 42%",
      "chart-5": "198 100% 48%",
      "chart-6": "268 88% 58%",
      "table-header-bg": "330 100% 97%",
    },
  },
  {
    id: "pastel-rainbow",
    label: "Pastelová duha",
    type: "gradient",
    swatch:
      "linear-gradient(125deg, hsl(344 100% 96%), hsl(34 100% 92%) 26%, hsl(54 100% 88%) 50%, hsl(160 74% 90%) 74%, hsl(265 90% 92%) 100%)",
    page:
      "linear-gradient(160deg, hsl(344 100% 97%) 0%, hsl(28 100% 94%) 25%, hsl(56 100% 90%) 48%, hsl(162 72% 91%) 74%, hsl(265 90% 93%) 100%)",
    panel: "linear-gradient(180deg, hsl(0 0% 100%) 0%, hsl(327 100% 99%) 40%, hsl(188 100% 98%) 100%)",
    vars: {
      background: "336 86% 97%",
      foreground: "236 38% 18%",
      card: "0 0% 100%",
      "card-foreground": "236 38% 18%",
      popover: "0 0% 100%",
      "popover-foreground": "236 38% 18%",
      primary: "268 84% 58%",
      "primary-foreground": "0 0% 100%",
      "primary-deep": "251 62% 24%",
      title: "248 62% 30%",
      "primary-soft": "266 100% 92%",
      secondary: "346 86% 66%",
      "secondary-foreground": "0 0% 100%",
      accent: "159 76% 88%",
      "accent-foreground": "162 72% 20%",
      muted: "42 100% 92%",
      "muted-foreground": "242 18% 40%",
      border: "324 52% 84%",
      input: "324 52% 84%",
      ring: "268 84% 58%",
      "chart-1": "268 84% 58%",
      "chart-2": "346 86% 66%",
      "chart-3": "28 100% 62%",
      "chart-4": "49 100% 54%",
      "chart-5": "160 72% 42%",
      "chart-6": "198 88% 56%",
      "table-header-bg": "330 100% 98%",
    },
  },
  {
    id: "pride-spectrum",
    label: "Pride · duha",
    type: "gradient",
    swatch:
      "linear-gradient(180deg, hsl(0 97% 45%) 0%, hsl(0 97% 45%) 16.66%, hsl(33 100% 50%) 16.66%, hsl(33 100% 50%) 33.33%, hsl(56 100% 50%) 33.33%, hsl(56 100% 50%) 50%, hsl(138 100% 25%) 50%, hsl(138 100% 25%) 66.66%, hsl(222 100% 50%) 66.66%, hsl(222 100% 50%) 83.33%, hsl(292 90% 28%) 83.33%, hsl(292 90% 28%) 100%)",
    page:
      "linear-gradient(160deg, hsl(0 100% 97%) 0%, hsl(33 100% 94%) 18%, hsl(56 100% 90%) 36%, hsl(138 70% 91%) 56%, hsl(222 100% 94%) 78%, hsl(292 100% 95%) 100%)",
    panel: "linear-gradient(180deg, hsl(0 0% 100%) 0%, hsl(0 100% 99%) 18%, hsl(222 100% 99%) 100%)",
    vars: {
      background: "56 100% 94%",
      foreground: "234 42% 18%",
      card: "0 0% 100%",
      "card-foreground": "234 42% 18%",
      popover: "0 0% 100%",
      "popover-foreground": "234 42% 18%",
      primary: "0 97% 48%",
      "primary-foreground": "0 0% 100%",
      "primary-deep": "292 78% 28%",
      title: "292 78% 32%",
      "primary-soft": "56 100% 90%",
      secondary: "33 100% 50%",
      "secondary-foreground": "0 0% 100%",
      accent: "138 72% 88%",
      "accent-foreground": "138 72% 18%",
      muted: "222 100% 94%",
      "muted-foreground": "240 18% 36%",
      border: "292 42% 78%",
      input: "222 52% 82%",
      ring: "222 100% 50%",
      success: "138 72% 38%",
      "success-foreground": "0 0% 100%",
      warning: "56 100% 50%",
      "warning-foreground": "44 84% 16%",
      destructive: "0 97% 45%",
      "destructive-foreground": "0 0% 100%",
      "chart-1": "0 97% 45%",
      "chart-2": "33 100% 50%",
      "chart-3": "56 100% 50%",
      "chart-4": "138 100% 25%",
      "chart-5": "222 100% 50%",
      "chart-6": "292 90% 28%",
      "table-header-bg": "56 100% 96%",
      "table-header-fg": "292 78% 28%",
    },
  },
  {
    id: "lavender-pearl",
    label: "Levandule · perla",
    type: "gradient",
    swatch: "linear-gradient(135deg, hsl(275 54% 97%), hsl(265 52% 90%))",
    page: "linear-gradient(178deg, hsl(276 62% 97%) 0%, hsl(264 54% 90%) 100%)",
    panel: "linear-gradient(180deg, hsl(274 76% 99%) 0%, hsl(268 58% 95%) 90%)",
    vars: {
      background: "270 54% 94%",
      foreground: "264 62% 15%",
      card: "0 0% 100%",
      primary: "265 92% 48%",
      "primary-foreground": "0 0% 98%",
      "primary-deep": "265 94% 18%",
      title: "262 78% 32%",
      "primary-soft": "268 94% 90%",
      secondary: "280 74% 70%",
      "secondary-foreground": "270 24% 12%",
      accent: "284 92% 90%",
      "accent-foreground": "262 48% 22%",
      muted: "270 48% 88%",
      "muted-foreground": "264 28% 36%",
      border: "272 34% 78%",
      input: "272 34% 78%",
      ring: "265 92% 48%",
      "table-header-bg": "272 58% 96%",
    },
  },
  {
    id: "mint-sand",
    label: "Máta · písek",
    type: "mixed",
    swatch: "linear-gradient(120deg, hsl(165 58% 91%), hsl(155 48% 88%) 45%, hsl(42 58% 90%))",
    page: "linear-gradient(135deg, hsl(168 56% 91%) 0%, hsl(44 60% 90%) 100%)",
    panel: "linear-gradient(135deg, hsl(170 62% 96%) 0%, hsl(46 66% 94%) 100%)",
    vars: {
      background: "158 48% 90%",
      foreground: "168 68% 12%",
      primary: "168 82% 30%",
      "primary-foreground": "0 0% 98%",
      "primary-deep": "172 86% 16%",
      title: "170 78% 24%",
      "primary-soft": "166 62% 86%",
      secondary: "198 84% 42%",
      "secondary-foreground": "0 0% 98%",
      accent: "44 84% 88%",
      "accent-foreground": "32 52% 18%",
      muted: "160 36% 86%",
      "muted-foreground": "170 24% 30%",
      border: "155 32% 76%",
      input: "155 32% 76%",
      ring: "168 82% 30%",
      "chart-1": "168 82% 36%",
      "chart-3": "44 90% 48%",
      "table-header-bg": "162 52% 94%",
    },
  },
  {
    id: "navy-champagne",
    label: "Námořní modř · šampaň",
    type: "mixed",
    swatch: "linear-gradient(110deg, hsl(43 78% 94%), hsl(222 68% 22%))",
    page: "linear-gradient(165deg, hsl(44 72% 95%) 0%, hsl(220 68% 88%) 100%)",
    panel: "linear-gradient(180deg, hsl(46 88% 98%) 0%, hsl(215 42% 92%) 100%)",
    vars: {
      background: "42 70% 93%",
      foreground: "222 68% 14%",
      primary: "222 72% 28%",
      "primary-foreground": "48 100% 97%",
      "primary-deep": "222 84% 12%",
      title: "222 78% 22%",
      "primary-soft": "215 48% 88%",
      secondary: "40 82% 52%",
      "secondary-foreground": "222 84% 10%",
      accent: "38 98% 86%",
      "accent-foreground": "222 72% 18%",
      muted: "42 44% 88%",
      "muted-foreground": "220 28% 32%",
      border: "220 36% 76%",
      input: "220 36% 76%",
      ring: "222 72% 28%",
      "chart-2": "222 72% 28%",
      "chart-4": "40 82% 55%",
      "table-header-bg": "44 62% 95%",
    },
  },
  {
    id: "blush-cream",
    label: "Ruměnec · smetanová",
    type: "gradient",
    swatch: "linear-gradient(135deg, hsl(348 70% 97%), hsl(48 96% 92%))",
    page: "linear-gradient(180deg, hsl(350 68% 96%) 0%, hsl(48 100% 93%) 100%)",
    panel: "linear-gradient(180deg, hsl(350 90% 99%) 0%, hsl(46 100% 96%) 90%)",
    vars: {
      background: "350 64% 95%",
      foreground: "340 78% 14%",
      primary: "343 82% 46%",
      "primary-foreground": "0 0% 99%",
      "primary-deep": "340 88% 18%",
      title: "340 78% 28%",
      "primary-soft": "350 96% 90%",
      secondary: "28 72% 48%",
      "secondary-foreground": "0 0% 98%",
      accent: "48 100% 88%",
      "accent-foreground": "32 62% 18%",
      muted: "350 48% 90%",
      "muted-foreground": "330 32% 34%",
      border: "345 46% 82%",
      input: "345 46% 82%",
      ring: "343 82% 46%",
      "table-header-bg": "350 72% 96%",
    },
  },
  {
    id: "sage-beige",
    label: "Šalvěj · béžová",
    type: "gradient",
    swatch: "linear-gradient(135deg, hsl(142 38% 91%), hsl(38 44% 90%))",
    page: "linear-gradient(180deg, hsl(144 40% 91%) 0%, hsl(40 48% 89%) 100%)",
    panel: "linear-gradient(180deg, hsl(142 48% 96%) 0%, hsl(42 56% 94%) 90%)",
    vars: {
      background: "142 36% 89%",
      foreground: "142 48% 12%",
      primary: "152 62% 30%",
      "primary-foreground": "0 0% 98%",
      "primary-deep": "152 72% 14%",
      title: "150 68% 22%",
      "primary-soft": "146 48% 84%",
      secondary: "38 58% 46%",
      "secondary-foreground": "0 0% 98%",
      accent: "84 52% 86%",
      "accent-foreground": "92 72% 16%",
      muted: "140 28% 86%",
      "muted-foreground": "138 22% 32%",
      border: "138 28% 76%",
      input: "138 28% 76%",
      ring: "152 62% 30%",
      "chart-6": "152 58% 36%",
      "table-header-bg": "142 40% 94%",
    },
  },
  {
    id: "slate-ice-blue",
    label: "Břidlice · ledová modř",
    type: "gradient",
    swatch: "linear-gradient(135deg, hsl(217 42% 86%), hsl(198 94% 88%))",
    page: "linear-gradient(178deg, hsl(218 40% 86%) 0%, hsl(200 88% 90%) 100%)",
    panel: "linear-gradient(180deg, hsl(217 52% 94%) 0%, hsl(200 72% 94%) 90%)",
    vars: {
      background: "218 38% 85%",
      foreground: "220 76% 8%",
      primary: "198 100% 40%",
      "primary-foreground": "0 0% 100%",
      "primary-deep": "205 100% 16%",
      title: "202 94% 28%",
      "primary-soft": "200 94% 86%",
      secondary: "215 44% 36%",
      "secondary-foreground": "0 0% 98%",
      accent: "191 94% 86%",
      "accent-foreground": "198 100% 12%",
      muted: "218 36% 82%",
      "muted-foreground": "218 22% 32%",
      border: "217 26% 70%",
      input: "217 26% 70%",
      ring: "198 100% 40%",
      "chart-1": "198 100% 40%",
      "table-header-bg": "218 40% 91%",
    },
  },
  {
    id: "dusty-pink-taupe",
    label: "Zamlžená růž · taupe",
    type: "gradient",
    swatch: "linear-gradient(135deg, hsl(342 36% 90%), hsl(28 38% 86%))",
    page: "linear-gradient(180deg, hsl(340 42% 90%) 0%, hsl(28 42% 86%) 100%)",
    panel: "linear-gradient(180deg, hsl(344 54% 95%) 0%, hsl(32 48% 92%) 90%)",
    vars: {
      background: "340 34% 88%",
      foreground: "25 72% 8%",
      primary: "356 74% 40%",
      "primary-foreground": "0 0% 98%",
      "primary-deep": "356 92% 14%",
      title: "2 74% 24%",
      "primary-soft": "350 74% 86%",
      secondary: "25 62% 36%",
      "secondary-foreground": "40 94% 96%",
      accent: "328 82% 86%",
      "accent-foreground": "340 92% 12%",
      muted: "32 42% 84%",
      "muted-foreground": "26 54% 24%",
      border: "25 42% 72%",
      input: "25 42% 72%",
      ring: "356 74% 40%",
      "table-header-bg": "338 38% 91%",
    },
  },
  {
    id: "lilac-silver",
    label: "Šeřík · stříbro",
    type: "gradient",
    swatch: "linear-gradient(135deg, hsl(278 72% 97%), hsl(268 52% 86%))",
    page: "linear-gradient(180deg, hsl(280 74% 96%) 0%, hsl(266 54% 86%) 100%)",
    panel: "linear-gradient(180deg, hsl(278 94% 99%) 0%, hsl(270 72% 93%) 90%)",
    vars: {
      background: "276 62% 92%",
      foreground: "270 84% 8%",
      primary: "276 98% 44%",
      "primary-foreground": "0 0% 98%",
      "primary-deep": "278 92% 15%",
      title: "276 92% 26%",
      "primary-soft": "276 94% 88%",
      secondary: "268 24% 58%",
      "secondary-foreground": "0 0% 98%",
      accent: "270 52% 88%",
      "accent-foreground": "270 84% 10%",
      muted: "270 36% 86%",
      "muted-foreground": "270 18% 34%",
      border: "268 28% 74%",
      input: "268 28% 74%",
      ring: "276 98% 44%",
      "chart-5": "276 98% 44%",
      "table-header-bg": "276 52% 94%",
    },
  },
  {
    id: "graphite-pearl",
    label: "Šedá · perla",
    type: "gradient",
    swatch: "linear-gradient(135deg, hsl(220 14% 95%), hsl(218 10% 72%), hsl(220 18% 88%), hsl(215 14% 55%))",
    page: "linear-gradient(178deg, hsl(220 16% 96%) 0%, hsl(218 12% 88%) 100%)",
    panel: "linear-gradient(180deg, hsl(222 22% 98%) 0%, hsl(216 14% 92%) 90%)",
    vars: {
      background: "220 14% 92%",
      foreground: "220 26% 14%",
      card: "0 0% 99%",
      "card-foreground": "220 24% 14%",
      popover: "220 14% 97%",
      "popover-foreground": "220 24% 14%",
      primary: "220 36% 32%",
      "primary-foreground": "0 0% 98%",
      "primary-deep": "222 42% 14%",
      title: "220 38% 20%",
      "primary-soft": "220 26% 86%",
      secondary: "215 14% 44%",
      "secondary-foreground": "0 0% 98%",
      muted: "220 12% 86%",
      "muted-foreground": "220 14% 36%",
      accent: "215 18% 84%",
      "accent-foreground": "222 38% 16%",
      border: "220 10% 78%",
      input: "220 10% 78%",
      ring: "220 36% 32%",
      "chart-3": "220 36% 38%",
      "table-header-bg": "220 16% 94%",
      "table-body-text": "220 22% 22%",
    },
  },
  {
    id: "onyx-copper",
    label: "Černá · měď",
    canvasTone: "dark",
    type: "gradient",
    swatch: "linear-gradient(135deg, hsl(214 29% 8%), hsl(216 23% 12%), hsl(31 69% 51%), hsl(28 66% 34%))",
    page: "linear-gradient(180deg, hsl(214 29% 8%) 0%, hsl(214 25% 10%) 100%)",
    panel: "linear-gradient(180deg, hsl(216 23% 12%) 0%, hsl(215 25% 15%) 100%)",
    vars: {
      background: "214 29% 8%",
      foreground: "220 14% 95%",
      card: "216 23% 12%",
      "card-foreground": "220 14% 95%",
      popover: "215 25% 15%",
      "popover-foreground": "220 14% 95%",
      primary: "31 69% 51%",
      "primary-foreground": "214 29% 8%",
      "primary-deep": "31 69% 45%",
      title: "220 14% 95%",
      "primary-soft": "213 23% 17%",
      secondary: "213 23% 17%",
      "secondary-foreground": "220 14% 95%",
      muted: "213 23% 17%",
      "muted-foreground": "214 24% 82%",
      accent: "31 69% 51%",
      "accent-foreground": "220 14% 95%",
      border: "31 69% 51%",
      input: "214 32% 10%",
      ring: "31 69% 51%",
      "chart-1": "32 92% 58%",
      "chart-2": "199 76% 52%",
      "chart-6": "152 62% 44%",
      "overlay-backdrop": "214 29% 6%",
      "table-header-bg": "212 24% 16%",
      "table-header-fg": "214 24% 82%",
      "table-body-text": "220 14% 95%",
    },
  },
  {
    id: "neon-candy-night",
    label: "Neonová noc",
    canvasTone: "dark",
    type: "gradient",
    swatch:
      "linear-gradient(125deg, hsl(244 42% 10%), hsl(314 88% 56%) 28%, hsl(193 100% 56%) 56%, hsl(51 100% 58%) 82%, hsl(146 78% 48%) 100%)",
    page: "linear-gradient(180deg, hsl(244 42% 10%) 0%, hsl(260 46% 12%) 100%)",
    panel: "linear-gradient(180deg, hsl(254 38% 14%) 0%, hsl(243 32% 17%) 100%)",
    vars: {
      background: "244 42% 10%",
      foreground: "220 100% 97%",
      card: "252 38% 14%",
      "card-foreground": "220 100% 97%",
      popover: "247 32% 17%",
      "popover-foreground": "220 100% 97%",
      primary: "193 100% 56%",
      "primary-foreground": "240 42% 10%",
      "primary-deep": "193 100% 48%",
      title: "220 100% 97%",
      "primary-soft": "252 28% 20%",
      secondary: "314 88% 58%",
      "secondary-foreground": "0 0% 100%",
      accent: "51 100% 58%",
      "accent-foreground": "244 42% 10%",
      muted: "248 22% 22%",
      "muted-foreground": "240 24% 78%",
      border: "251 24% 28%",
      input: "251 24% 28%",
      ring: "193 100% 56%",
      success: "146 78% 48%",
      "success-foreground": "244 42% 10%",
      warning: "39 100% 58%",
      "warning-foreground": "244 42% 10%",
      "chart-1": "193 100% 56%",
      "chart-2": "314 88% 58%",
      "chart-3": "51 100% 58%",
      "chart-4": "146 78% 48%",
      "chart-5": "23 100% 58%",
      "chart-6": "268 100% 68%",
      "overlay-backdrop": "246 42% 8%",
      "table-header-bg": "248 32% 18%",
      "table-header-fg": "220 100% 96%",
      "table-body-text": "228 30% 90%",
    },
  },
];

/** Kompletní seznam — pro `getAppearancePresetById`, localStorage a apply. */
export const APPEARANCE_PRESETS = PRIMARY_APPEARANCE_PRESETS;

const PRESET_BY_ID = Object.fromEntries(APPEARANCE_PRESETS.map((p) => [p.id, p]));

/**
 * Náhledové pruhy pro picker; většina presetů vrací 4 tokeny, speciální motivy i více.
 * @param {AppearancePreset} preset
 * @returns {string[]}
 */
export function getPresetStripCss(preset) {
  if (preset?.id === "pride-spectrum") {
    return [
      "hsl(0 97% 45%)",
      "hsl(33 100% 50%)",
      "hsl(56 100% 50%)",
      "hsl(138 100% 25%)",
      "hsl(222 100% 50%)",
      "hsl(292 90% 28%)",
    ];
  }
  const m = {
    ...APPEARANCE_ROOT_DEFAULTS,
    ...(preset?.vars || {}),
  };
  const q = (v) => `hsl(${v})`;
  return [q(m.background), q(m.primary), q(m.accent), q(m.secondary)];
}

export function normalizeStoredAppearanceId(raw) {
  if (!raw || typeof raw !== "string") return DEFAULT_APPEARANCE_ID;
  const alias = STORED_ID_ALIASES[raw] || raw;
  return PRESET_BY_ID[alias] ? alias : DEFAULT_APPEARANCE_ID;
}

export function getAppearancePresetById(id) {
  const nid = normalizeStoredAppearanceId(id);
  return PRESET_BY_ID[nid] || PRESET_BY_ID[DEFAULT_APPEARANCE_ID];
}

export function loadStoredAppearanceId() {
  try {
    return normalizeStoredAppearanceId(localStorage.getItem(APPEARANCE_STORAGE_KEY));
  } catch {
    return DEFAULT_APPEARANCE_ID;
  }
}

/** Vrátí true pokud uživatel explicitně zvolil schéma (je uloženo v localStorage). */
export function hasUserChosenAppearance() {
  try {
    return localStorage.getItem(APPEARANCE_STORAGE_KEY) !== null;
  } catch {
    return false;
  }
}

/**
 * @param {AppearancePreset} preset
 */
export function applyAppearancePresetToDocument(preset) {
  if (typeof document === "undefined") return;
  const root = document.documentElement;
  const merged = {
    ...APPEARANCE_ROOT_DEFAULTS,
    ...(preset.vars || {}),
  };
  for (const [key, value] of Object.entries(merged)) {
    if (typeof value !== "string") continue;
    root.style.setProperty(`--${key}`, value);
  }
  root.setAttribute("data-bankoapp-canvas", preset.canvasTone === "dark" ? "dark" : "light");
  root.setAttribute("data-appearance-preset", preset.id || "");
}
