/**
 * ECB — lidské názvy datových toků, karty oblastí a rychlé šablony nad existujícím dimension wizardem.
 * Žádná nová API — jen mapování a UX texty.
 */

/** @typedef {{ flow: string | null, humanTitle: string, description: string, ctaLabel: string, examples?: string[] }} EcbFlowPreset */

const OTHER = Object.freeze({
  flow: null,
  humanTitle: "Technický ECB dataset",
  description:
    "Tento datový tok vyžaduje znalost dimenzí ECB. Doporučujeme AI asistenta v tomto katalogu nebo níže „Pokročilé: technický katalog“.",
  ctaLabel: "Pokročilé dimenze",
});

/** @type {Record<string, EcbFlowPreset & { flow: string }>} */
export const ECB_FLOW_PRESETS = Object.freeze({
  EXR: {
    flow: "EXR",
    humanTitle: "Směnné kurzy",
    description: "Kurzy měn vůči euru, např. USD/EUR, GBP/EUR, CZK/EUR.",
    ctaLabel: "Sestavit kurz",
    examples: ["EUR/USD měsíčně", "CZK/EUR denně nebo měsíčně"],
  },
  MIR: {
    flow: "MIR",
    humanTitle: "Úrokové sazby bank",
    description:
      "Úrokové sazby úvěrů a vkladů v eurozóně podle sektoru, splatnosti a země (MIR — monthly interest rate).",
    ctaLabel: "Sestavit sazbu",
  },
  BSI: {
    flow: "BSI",
    humanTitle: "Bilance měnových finančních institucí",
    description: "Úvěry, vklady, aktiva a pasiva bank a dalších MFI (balance sheet).",
    ctaLabel: "Sestavit bankovní ukazatel",
  },
  CBD2: {
    flow: "CBD2",
    humanTitle: "Konsolidovaná bankovní data",
    description:
      "Konsolidované statistiky bankovního sektoru (CBD2) — kapitál, ziskovost, úvěry, kvalita aktiv podle zemí nebo skupin, pokud jsou v datech dostupné.",
    ctaLabel: "Sestavit bankovní statistiku",
  },
  SEC: {
    flow: "SEC",
    humanTitle: "Cenné papíry",
    description: "Emise, držby, dluhopisy, akcie a související finanční statistiky ECB.",
    ctaLabel: "Sestavit ukazatel",
  },
  FM: {
    flow: "FM",
    humanTitle: "Finanční trhy",
    description: "Peněžní trh, měnová politika, tržní sazby a související ukazatele.",
    ctaLabel: "Sestavit tržní ukazatel",
  },
  ICP: {
    flow: "ICP",
    humanTitle: "Inflační a cenové ukazatele",
    description: "Cenové indexy a související inflační data v ECB Statistical Data Warehouse.",
    ctaLabel: "Sestavit cenový ukazatel",
  },
  BOP: {
    flow: "BOP",
    humanTitle: "Platební bilance",
    description: "Běžný a finanční účet, investice a mezinárodní investiční pozice.",
    ctaLabel: "Sestavit BOP ukazatel",
  },
  AMECO: {
    flow: "AMECO",
    humanTitle: "Makroekonomická databáze AMECO",
    description: "Dlouhodobé makroekonomické řady převzané z AMECO — vhodné spíše pro pokročilé.",
    ctaLabel: "Procházet AMECO",
  },
  AGR: {
    flow: "AGR",
    humanTitle: "Zemědělství",
    description: "Oblast agro/dat specifické pro ECB — pro běžné bankovní dashboardy jen zřídka.",
    ctaLabel: "Procházet",
  },
});

/**
 * @param {string | undefined | null} code
 * @returns {EcbFlowPreset}
 */
export function getEcbFlowPreset(code) {
  const k = String(code || "")
    .trim()
    .toUpperCase();
  if (!k) return OTHER;
  return ECB_FLOW_PRESETS[k] ? { ...ECB_FLOW_PRESETS[k], flow: k } : OTHER;
}

/**
 * Karty „Doporučené oblasti“ na stránce /ecb/catalog (pořadí = priorita pro běžné uživatele).
 * `openWizard` řádek: primary flow pro ECB dimension wizard.
 */
export const ECB_TOPIC_CARDS = Object.freeze([
  {
    id: "exr",
    title: "Směnné kurzy",
    description: ECB_FLOW_PRESETS.EXR.description,
    flow: "EXR",
    cta: ECB_FLOW_PRESETS.EXR.ctaLabel,
    showQuickRates: true,
  },
  {
    id: "mir",
    title: "Úrokové sazby bank",
    description: ECB_FLOW_PRESETS.MIR.description,
    flow: "MIR",
    cta: ECB_FLOW_PRESETS.MIR.ctaLabel,
  },
  {
    id: "bsi",
    title: "Bankovní bilance",
    description: ECB_FLOW_PRESETS.BSI.description,
    flow: "BSI",
    cta: ECB_FLOW_PRESETS.BSI.ctaLabel,
  },
  {
    id: "cbd",
    title: "Konsolidovaná bankovní data",
    description: ECB_FLOW_PRESETS.CBD2.description,
    flow: "CBD2",
    cta: ECB_FLOW_PRESETS.CBD2.ctaLabel,
  },
  {
    id: "fm",
    title: "Finanční trhy",
    description: ECB_FLOW_PRESETS.FM.description,
    flow: "FM",
    cta: ECB_FLOW_PRESETS.FM.ctaLabel,
  },
  {
    id: "sec",
    title: "Cenné papíry",
    description: ECB_FLOW_PRESETS.SEC.description,
    flow: "SEC",
    cta: ECB_FLOW_PRESETS.SEC.ctaLabel,
  },
  {
    id: "bop",
    title: "Platební bilance",
    description: ECB_FLOW_PRESETS.BOP.description,
    flow: "BOP",
    cta: ECB_FLOW_PRESETS.BOP.ctaLabel,
  },
  {
    id: "icp",
    title: "Inflační a cenové ukazatele",
    description: ECB_FLOW_PRESETS.ICP.description,
    flow: "ICP",
    cta: ECB_FLOW_PRESETS.ICP.ctaLabel,
  },
  {
    id: "ameco",
    title: "Makroekonomika · AMECO",
    description: ECB_FLOW_PRESETS.AMECO.description,
    flow: "AMECO",
    cta: ECB_FLOW_PRESETS.AMECO.ctaLabel,
    variant: "secondary",
  },
  {
    id: "advanced_catalog",
    title: "Všechny datové toky",
    description:
      "Rozšířený technický katalog ECB dataflow (A–Z) pro expertní scénáře a ruční práci se SDMX.",
    flow: null,
    cta: "Otevřít technický katalog",
    openAdvancedCatalog: true,
    variant: "advanced",
  },
]);

/**
 * Šablony EXR — dimenze odpovídající běžným řadám (stejná logika jako v EcbDimensionWizardModal).
 */
export const ECB_EXR_QUICK_TEMPLATES = Object.freeze([
  {
    id: "usd_eur_m",
    label: "USD/EUR · měsíčně",
    /** Odpovídá řadě typu EXR/M.USD.EUR.SP00.A */
    suggestedDimensions: {
      FREQ: "M",
      CURRENCY: "USD",
      CURRENCY_DENOM: "EUR",
      EXR_TYPE: "SP00",
      EXR_SUFFIX: "A",
    },
  },
  {
    id: "czk_eur_m",
    label: "CZK/EUR · měsíčně",
    suggestedDimensions: {
      FREQ: "M",
      CURRENCY: "CZK",
      CURRENCY_DENOM: "EUR",
      EXR_TYPE: "SP00",
      EXR_SUFFIX: "A",
    },
  },
  {
    id: "czk_eur_d",
    label: "CZK/EUR · denně",
    suggestedDimensions: {
      FREQ: "D",
      CURRENCY: "CZK",
      CURRENCY_DENOM: "EUR",
      EXR_TYPE: "SP00",
      EXR_SUFFIX: "A",
    },
  },
  {
    id: "gbp_eur_m",
    label: "GBP/EUR · měsíčně",
    suggestedDimensions: {
      FREQ: "M",
      CURRENCY: "GBP",
      CURRENCY_DENOM: "EUR",
      EXR_TYPE: "SP00",
      EXR_SUFFIX: "A",
    },
  },
]);

function normQ(s) {
  return String(s || "")
    .trim()
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "");
}

/**
 * Krátká nápověda v ECB AI panelu podle zadání (před spuštěním hledání).
 * @param {string} q
 * @returns {{ title: string, body: string, primaryFlow?: string, openExrTemplate?: string } | null}
 */
export function getEcbAiPanelIntentHint(q) {
  const x = normQ(q);
  if (x.length < 3) return null;

  if (/\broe\b|return on equity|zisk.*bank|bank.*zisk|profitabilit|rentabilita.*bank|\broa\b/i.test(x)) {
    return {
      title: "Bankovní ziskovost v ECB",
      body:
        "Pro ziskovost bank (ROE/ROA) použijte konsolidovaná bankovní data toku CBD2 — v průvodci vyberte země/oblast, ukazatel a frekvenci. Začněte kartou „Konsolidovaná bankovní data“ nebo tlačítkem níže (otevře průvodce na CBD2).",
      primaryFlow: "CBD2",
    };
  }

  if (/kurz|smenn|exchange|usd|dolar|\beur\b.*\/|fx\b|cnb|ecb.*men/i.test(x)) {
    return {
      title: "Směnné kurzy EXR",
      body:
        "Pro kurzy vůči euru použijte datový tok EXR. Rychlé šablony (USD/EUR, CZK/EUR, GBP/EUR) najdete nad AI panelem na této stránce — po kliknutí se otevře průvodce dimenzemi.",
      primaryFlow: "EXR",
      openExrTemplate: "usd_eur_m",
    };
  }

  const policyRates =
    /ecb.*policy|\bdeposit facility\b|\bmro\b|\bmarginal refinancing\b|refinancovaci|central bank rate|trojstranna|troj|^sazb.*ecb|eecbs.*saz/i.test(
      x
    );
  const bankIv = /uvěr|uver|vklad|deposit|lending|MFI|mir\b|hypotek/i.test(x);

  if (policyRates && !bankIv) {
    return {
      title: "Úrokové sazby — měnová politika vs. bankovní MIR",
      body:
        "Pro sazby měnové politiky ECB (facility, policy) zkuste často tok FM — finanční trhy / měnová politika. Pro sazby úvěrů a vkladů u bank (MFI) je vhodnější MIR (úrokové sazby bank). Podle formulace dotazu použijte příslušnou kartu nahoře.",
      primaryFlow: "FM",
    };
  }
  if (bankIv || (/\bsazb/i.test(x) && !policyRates)) {
    return {
      title: "Úrokové sazby u bank (MIR)",
      body:
        "Pro úrokové sazby úvěrů a vkladů v eurozóně typicky použijte datový tok MIR a kartu „Úrokové sazby bank“.",
      primaryFlow: "MIR",
    };
  }

  if (/\becb\b.*\bhint|ecb.*serie|ecb.*wizard/i.test(x)) return null;
  return null;
}

/**
 * Nápověda na globální stránce /search/catalog (AI režim).
 * @param {string} q
 * @returns {{ title: string, body: string } | null}
 */
export function getCatalogSearchEcbIntentHint(q) {
  const x = normQ(q);
  if (x.length < 3) return null;
  if (/ecb|kurz|cnb|usd|eur|eur\/|exchange|bilance|\bbop\b|\bsec\b|MIR|\broe\b/i.test(x)) {
    const roe =
      /\broe\b|zisk.*bank|return on equity/i.test(x) &&
      /ecb|cbd|eu|euro|eurozon/i.test(x);
    const fx = /kurz|usd|exchange|eur\/usd|gbp|czech|korun/i.test(x);
    if (roe) {
      return {
        title: "Tip pro ECB dotaz na bankovní ziskovost",
        body:
          "Nepřiklánějte výsledky k obecným databázím typu AMECO/AGR. Pro řady ECB o bankovním sektoru otevřete katalog ECB a zvolte oblast „Konsolidovaná bankovní data“ (CBD2) — následně průvodce dimenzemi.",
      };
    }
    if (fx) {
      return {
        title: "Tip pro kurzy ECB",
        body:
          "Pro kurzy vůči euru použijte v katalogu ECB oblast „Směnné kurzy“ (EXR); k dispozici jsou také rychlé šablony např. USD/EUR měsíčně.",
      };
    }
    if (/sazb|deposit|facility|policy|ecb.*urok/i.test(x)) {
      return {
        title: "Tip pro sazby ECB",
        body:
          "Úrokové sazby měnové politiky často najdete v toku FM; sazby úvěrů a vkladů u bank pak v MIR. V katalogu ECB vyberte odpovídající kartu oblasti.",
      };
    }
  }
  return null;
}
