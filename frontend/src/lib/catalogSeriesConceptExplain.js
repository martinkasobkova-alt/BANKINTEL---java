import api, { formatApiErrorFromAxios } from "@/lib/api";

function matchConceptBody(title) {
  const low = String(title || "").toLowerCase();
  const segments = String(title || "")
    .split(/[:;,|/]/)
    .map((s) => s.trim())
    .filter(Boolean);
  const tail = segments.length > 1 ? segments.slice(1).join(" ").toLowerCase() : "";

  if (/m[eě]nov|monetary base|money base/.test(low) && /b[aá]z|base/.test(low)) {
    if (/zdroj|source|pasiv|liabilit/.test(low)) {
      return (
        "Sleduje složení pasiv (zdrojů) měnové báze centrální banky — z čeho se skládá množství peněz " +
        "emitovaných CB: bankovky v oběhu, povinné rezervy komerčních bank, vklady vlády a další položky. " +
        "Růst série znamená, že daný zdroj měnové báze roste."
      );
    }
    if (/použit|pouzit|use|asset|aktiv/.test(low)) {
      return (
        "Sleduje složení aktiv centrální banky na straně měnové báze — kam CB alokuje emitované peníze " +
        "(např. devizové rezervy, úvěry bankám, držba cenných papírů). Každá řada je konkrétní položka bilance."
      );
    }
    return (
      "Měnová báze je souhrn pasiv centrální banky — nejužší peněžní agregát emitovaný CB. Ukazuje, " +
      "kolik „peněz centrální banky“ je v ekonomii; růst obvykle signalizuje expanzivnější měnovou politiku " +
      "nebo vyšší poptávku bank po likviditě."
    );
  }

  if (/analytick/.test(low) && /[uú]ct|cnb|centr[aá]ln|central/.test(low)) {
    return (
      "Analytické účty centrální banky jsou detailní bilance CB — aktiva (devizové rezervy, refinační úvěry " +
      "bankám, cenné papíry) a pasiva (měnová báze, vklady bank, emitované cenné papíry). Každá řada sleduje " +
      "konkrétní položku této bilance."
    );
  }

  if (/devizov|forex reserve|fx reserve|international reserve/.test(low)) {
    return (
      "Devizové rezervy centrální banky — cizoměnová aktiva držená CB pro intervence na kurzu, splácení " +
      "zahraničního dluhu a stabilizaci měny."
    );
  }

  if (/hicp|spotřebitelsk|spotrebitelsk|inflac|cpi|cenov/.test(low)) {
    return (
      "Měří změnu cen spotřebního koše domácností v čase — inflační tlak na spotřebitele. Vyšší hodnota " +
      "znamená rychlejší růst cen; sledujte, zda jde o meziroční nebo měsíční změnu."
    );
  }

  if (/efektivní kurz|efektivni kurz|neer|reer|exchange rate index/.test(low)) {
    return (
      "Vyjadřuje sílu měny vůči koši měn obchodních partnerů (vážený kurz). Růst indexu obvykle znamená " +
      "posílení měny, pokles oslabení — nejde o kurz vůči jedné měně."
    );
  }

  if (/hdp|gdp|dom[aá]c[ií] produk/.test(low)) {
    return "Měří objem ekonomické produkce za období. Rozlišujte nominální vs. reálné HDP a meziroční růst vs. úroveň.";
  }

  if (/investic|investment|kapitalov|tvořen[ií] kapit[aá]lu/.test(low)) {
    return (
      "Popisuje, kolik se v ekonomice investuje do kapitálu — důležitý ukazatel dlouhodobého růstového potenciálu."
    );
  }

  if (/finan[cč]n[ií] [uú]ct|financial account|figaro|n[aá]rodn[ií] [uú]ct/.test(low)) {
    return (
      "Patří do národních účtů — mapuje finanční aktiva a pasiva sektorů. Ukazuje strukturu bohatství a zadlužení."
    );
  }

  if (/roe|return on equity|rentabilita vlastn[ií]ho/.test(low)) {
    return (
      "Rentabilita vlastního kapitálu bank — kolik zisku banka vytvoří na jednotku equity. Srovnávejte se stejnou metodikou."
    );
  }

  if (/cet1|tier 1|kapit[aá]lov|capital adequacy|rwa|\bcar\b/.test(low)) {
    return (
      "Ukazatel kapitálové přiměřenosti nebo kvality kapitálu bank — schopnost absorbovat ztráty vůči rizikovým aktivům."
    );
  }

  if (/[uú]rokov|yield|v[yý]nos|sazeb|\brate\b/.test(low) && !/r[uů]st|growth/.test(low)) {
    return (
      "Vyjadřuje cenu peněz nebo výnos z dluhopisů / úvěrů v čase. Sledujte splatnost a zda jde o nominální nebo reálný výnos."
    );
  }

  if (/\bm[123]\b|money supply|pen[eě]žn|penezn/.test(low)) {
    return (
      "Měří objem peněz nebo likvidity v ekonomice podle definice centrální banky (M1/M2/M3)."
    );
  }

  if (/stav na konci|end of period/.test(low)) {
    const headline = segments[0] || title;
    const detail =
      segments.length > 1
        ? ` Poslední část názvu („${segments[segments.length - 1]}“) upřesňuje konkrétní položku nebo agregát.`
        : "";
    return (
      `„${headline}“ je stavová veličina — hodnota k poslednímu dni období (snapshot), ne průměr za celé období.${detail}`
    );
  }

  if (tail && segments.length >= 2) {
    return (
      `„${segments[0]}“ sleduje „${segments[segments.length - 1]}“. Název upřesňuje periodicitu a typ hodnoty (${tail.slice(0, 120)}).`
    );
  }

  return null;
}

/** Krátká poznámka pod vysvětlením — rozlišuje lokální fallback vs. server heuristiku vs. AI. */
export function seriesConceptExplainSourceNote(data) {
  if (!data || data.ai_used !== false) return null;
  if (data.source === "local_fallback") {
    const st = Number(data.http_status || 0);
    if (st === 404) {
      return "Backend endpoint /api/catalog/explain-series není dostupný (404) — nasaďte nebo restartujte API server.";
    }
    if (st === 502 || st === 503) {
      return "Backend dočasně nedostupný — základní vysvětlení v prohlížeči.";
    }
    if (st === 0) {
      return "Nelze kontaktovat API — zkontrolujte síť, proxy a běžící backend.";
    }
    return "Backend neodpověděl — základní vysvětlení v prohlížeči.";
  }
  if (data.source === "heuristic") {
    if (data.fallback_reason === "openai_unavailable") {
      return "OpenAI nebyla dostupná — použita pravidla serveru.";
    }
    return "Základní vysvětlení bez AI (server).";
  }
  return "Základní vysvětlení bez AI.";
}

function localConceptExplanation(meta, httpStatus = 0) {
  const title = String(meta?.title || meta?.name || meta?.set_id || "Datová řada").trim();
  const unit = String(meta?.unit || meta?.unit_label_cs || meta?.value_descriptor || "").trim();
  const freq = String(meta?.frequency_label || meta?.frequency || "").trim();
  const src = String(meta?.source_type || meta?.catalog_id || "").trim();
  const indicator = String(meta?.selected_indicator_name || meta?.indicator_name || "").trim();

  let body = matchConceptBody(title);
  if (!body && indicator && indicator.toLowerCase() !== title.toLowerCase()) {
    body = matchConceptBody(indicator);
  }
  if (!body) {
    body = `Ukazatel „${title}“ pochází z oficiální statistiky — ověřte definici u zdroje (${src.toUpperCase() || "katalog"}).`;
  }

  let readHint = "Sledujte trend a srovnejte stejnou metodiku v čase; neinterpretujte jednu hodnotu izolovaně.";
  if (unit) readHint = `Jednotka: ${unit}. ${readHint}`;
  if (freq) readHint = `Periodicita: ${freq}. ${readHint}`;
  if (src) readHint = `Zdroj: ${src.toUpperCase()}. ${readHint}`;

  return {
    ok: true,
    ai_used: false,
    source: "local_fallback",
    http_status: httpStatus,
    explanation_cz: `„${title}“ — ${body}`,
    topic_label_cz: title,
    read_hint_cz: readHint,
  };
}

/**
 * Požádá backend (nebo lokální fallback) o konceptuální vysvětlení řady.
 * @param {Record<string, unknown>} meta
 * @param {Record<string, unknown>|null} chartContract ChartDataContract právě zobrazeného grafu —
 *   backend z něj spočítá faktické čtení (poslední hodnota, vývoj), takže odpověď mluví o TOMTO
 *   grafu, ne jen obecnou definici. Bez něj zůstává vysvětlení definiční jako dřív.
 */
export async function fetchSeriesConceptExplanation(meta, chartContract = null) {
  try {
    const payload = chartContract && typeof chartContract === "object"
      ? { ...meta, chart_contract: chartContract }
      : meta;
    const { data } = await api.post("/catalog/explain-series", payload, { timeout: 35000 });
    if (data?.ok && data.explanation_cz) return data;
    if (data?.message_cs) {
      return { ok: false, message_cs: String(data.message_cs) };
    }
    return localConceptExplanation(meta);
  } catch (e) {
    const status = Number(e?.response?.status || 0);
    if (status === 404 || status === 502 || status === 503 || status === 0) {
      return localConceptExplanation(meta, status);
    }
    const msg = formatApiErrorFromAxios(e);
    return {
      ok: false,
      message_cs: msg || "Vysvětlení se nepodařilo načíst.",
    };
  }
}

function localFollowupAnswer(meta, question, priorExplanation) {
  const title = String(meta?.title || meta?.name || "tento ukazatel").trim();
  const q = String(question || "").trim().toLowerCase();
  const prior = String(priorExplanation || "").trim();

  let answer = "";
  if (/\bm[23]\b|pen[eě]ž|penez|money supply/.test(q) && /m[eě]nov|b[aá]z/.test(title.toLowerCase())) {
    answer =
      `„${title}“ je nejužší peněžní agregát emitovaný centrální bankou. M2/M3 jsou širší a zahrnují i vklady u bank — ` +
      "měnová báze je jejich základ, ale nejsou totéž.";
  } else if (/inflac|hicp|cpi|ceny/.test(q) && /m[eě]nov|[uú]rok|sazb/.test(title.toLowerCase())) {
    answer =
      `„${title}“ souvisí s inflací přes měnovou politiku a poptávku, ale vztah není okamžitý — hrají roli i kurz, fiskální politika a strukturální faktory.`;
  } else if (/deviz|kurz|fx|neer/.test(q)) {
    answer =
      `„${title}“ a kurzové/devizové ukazatele se propojují přes bilanci centrální banky a intervenční politiku — vztah je bilancní, ne přímý 1:1.`;
  } else {
    answer =
      `K otázce „${question}“: bez AI nelze spolehlivě popsat vztah k jiné řadě. Porovnávejte stejnou periodicitu a jednotky.` +
      (prior ? ` Základní popis řady: ${prior.slice(0, 280)}` : "");
  }

  return {
    ok: true,
    ai_used: false,
    source: "local_fallback",
    answer_cz: answer,
  };
}

/**
 * Doplňující otázka k vysvětlené řadě (vztah k jiným ukazatelům).
 * @param {Record<string, unknown>} meta
 * @param {string} question
 * @param {string} priorExplanation
 * @param {Array<{role: string, content: string}>} conversationHistory
 */
export function inferFindInDataQuery(meta, question, priorExplanation = "") {
  const q = String(question || "").trim();
  if (!q) return null;
  const ql = q.toLowerCase();
  const country = String(meta?.country_label || meta?.geo_label || "").trim();
  const title = String(meta?.title || meta?.name || "").toLowerCase();
  const blob = `${ql} ${title} ${String(priorExplanation || "").toLowerCase()}`;
  const wantsFind =
    /\b(najdi|najít|najit|najde|vyhledej|hledat|chci jin|chci obecn|chci celkov|a[tť] (to )?najde|find|search|najít v datech)\b/i.test(q)
    || /\b(obecn|celkov|general|celá popul|all population|bez věk|without youth|jiný ukazatel|jiny ukazatel)\b/i.test(q);
  if (!wantsFind) return null;
  if (/nezaměst|nezamest|unemployment|zaměstnan|zamestnan|labour|labor/i.test(blob)) {
    if (/\b(obecn|celkov|general|celou populaci|all population)\b/i.test(q)) {
      const parts = ["unemployment rate"];
      if (country) parts.push(country);
      else if (/slovens|slovak/i.test(q)) parts.push("Slovakia");
      return parts.join(" ");
    }
  }
  if (country && !ql.includes(country.toLowerCase())) return `${q} ${country}`.trim();
  return q;
}

export async function fetchSeriesConceptFollowup(meta, question, priorExplanation, conversationHistory = [], chartContract = null) {
  try {
    const { data } = await api.post(
      "/catalog/explain-series/ask",
      {
        ...meta,
        question,
        prior_explanation: priorExplanation,
        conversation_history: conversationHistory,
        ...(chartContract && typeof chartContract === "object" ? { chart_contract: chartContract } : {}),
      },
      { timeout: 45000 },
    );
    if (data?.ok && data.answer_cz) {
      if (!data.catalog_search_query) {
        const inferred = inferFindInDataQuery(meta, question, priorExplanation);
        if (inferred) {
          return { ...data, catalog_search_query: inferred, find_in_data: true };
        }
      }
      return data;
    }
    const local = localFollowupAnswer(meta, question, priorExplanation);
    const inferred = inferFindInDataQuery(meta, question, priorExplanation);
    if (inferred) {
      local.catalog_search_query = inferred;
      local.find_in_data = true;
    }
    return local;
  } catch (e) {
    const status = Number(e?.response?.status || 0);
    if (status === 404 || status === 502 || status === 503 || status === 0) {
      const out = localFollowupAnswer(meta, question, priorExplanation);
      const inferred = inferFindInDataQuery(meta, question, priorExplanation);
      if (inferred) {
        out.catalog_search_query = inferred;
        out.find_in_data = true;
      }
      out.http_status = status;
      return out;
    }
    const msg = formatApiErrorFromAxios(e);
    return {
      ok: false,
      message_cs: msg || "Odpověď se nepodařila načíst.",
    };
  }
}
