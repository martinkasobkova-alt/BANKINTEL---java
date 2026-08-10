import React from "react";
import { act } from "react";
import { createRoot } from "react-dom/client";
import { vi } from "vitest";

const apiMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock("react-router-dom", () => {
  const ReactLib = require("react");
  return {
    useNavigate: () => jest.fn(),
    useLocation: () => ({ pathname: "/search/catalog", search: "?ai=1" }),
    useSearchParams: () => [new URLSearchParams("ai=1"), jest.fn()],
    Link: ({ children, to, ...rest }) => ReactLib.createElement("a", { href: to, ...rest }, children),
  };
}, { virtual: true });

vi.mock("sonner", () => ({
  toast: {
    info: jest.fn(),
    success: jest.fn(),
    error: jest.fn(),
  },
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    default: apiMock,
  };
});

vi.mock("@/components/layout/AppShell", () => {
  const ReactLib = require("react");
  return {
    __esModule: true,
    default: ({ children }) => ReactLib.createElement("div", { "data-testid": "app-shell" }, children),
  };
}, { virtual: true });

vi.mock("@/components/sources/SourcePreview", () => {
  const ReactLib = require("react");
  return {
    __esModule: true,
    default: ({ loading, onGeoSelectionChange }) => {
      if (loading) return ReactLib.createElement("div", { "data-testid": "mock-source-preview-loading" });
      return ReactLib.createElement(
        "div",
        { "data-testid": "mock-source-preview" },
        ReactLib.createElement(
          "button",
          {
            type: "button",
            "data-testid": "mock-source-preview-select-geo",
            onClick: () => onGeoSelectionChange?.(["CZ", "DE"]),
          },
          "select-cz-de",
        ),
      );
    },
  };
}, { virtual: true });

vi.mock("@/components/catalog/search/CatalogSetPreviewPanel", () => {
  const ReactLib = require("react");
  return {
    __esModule: true,
    default: ({
      isOpen,
      previewLoading,
      fetchPreview,
      def,
      displayRow,
      row,
    }) => {
      if (!isOpen) return null;
      if (previewLoading) {
        return ReactLib.createElement("div", { "data-testid": "mock-source-preview-loading" });
      }
      return ReactLib.createElement(
        "div",
        { "data-testid": "mock-source-preview" },
        ReactLib.createElement(
          "button",
          {
            type: "button",
            "data-testid": "mock-source-preview-select-geo",
            onClick: () => fetchPreview(
              def,
              displayRow || row,
              undefined,
              [],
              ["CZ", "DE"],
              { geo: ["CZ", "DE"] },
            ),
          },
          "select-cz-de",
        ),
      );
    },
  };
});

vi.mock("@/components/ui/DataLoadIndicator", () => {
  const ReactLib = require("react");
  return {
    __esModule: true,
    DataLoadInline: ({ label }) => ReactLib.createElement("div", null, label || "loading"),
  };
}, { virtual: true });

vi.mock("@/components/ui/loading", () => {
  const ReactLib = require("react");
  return {
    __esModule: true,
    LoadingBlock: () => ReactLib.createElement("div", null, "loading-block"),
    LoadingSpinner: () => ReactLib.createElement("span", null, "spinner"),
  };
}, { virtual: true });

vi.mock("@/contexts/AuthContext", () => ({
  __esModule: true,
  useAuth: () => ({
    user: { id: "u-test" },
    isAdmin: false,
    ready: true,
    isSubscriber: false,
  }),
}), { virtual: true });

vi.mock("@/hooks/useFeatureAccess", () => ({
  __esModule: true,
  useFeatureAccess: () => ({ allowed: true, message: "", loading: false }),
}), { virtual: true });

vi.mock("@/lib/catalogTree", () => ({
  __esModule: true,
  flattenCatalogCategoriesBestEffort: () => ([
    {
      kind: "set",
      set_id: "prc_hicp_aind",
      name: "HICP annual data",
      title: "HICP annual data",
      path: "/prices/hicp/prc_hicp_aind",
      parentPath: "/prices/hicp",
      query_params: {
        coicop: "CP00",
        query_mode: "preview",
      },
    },
  ]),
  buildPathIndex: () => ({}),
  buildFilteredPaths: () => new Set(["/prices/hicp/prc_hicp_aind"]),
  parseSearchKeywords: (q) => String(q || "").trim().split(/\s+/).filter(Boolean),
  MAX_CATALOG_FILTER_ROWS: 500,
  browseAncestorsOpen: () => ["/prices", "/prices/hicp"],
}), { virtual: true });

vi.mock("@/lib/catalogPersonalDashboard", () => ({
  __esModule: true,
  buildExternalCatalogChartConfig: jest.fn(() => ({})),
}), { virtual: true });

vi.mock("@/components/widgets/WidgetRenderer", () => {
  const ReactLib = require("react");
  return { __esModule: true, default: () => ReactLib.createElement("div") };
}, { virtual: true });

vi.mock("@/lib/catalogDefinitions", async (importOriginal) => {
  const actual = await importOriginal();
  return {
  ...actual,
  CATALOGS: [
    {
      id: "eurostat",
      label: "Eurostat",
      sourceType: "eurostat",
      catalogPath: "/catalogs/eurostat/browse",
      tier: "production",
      addPath: "",
    },
    {
      id: "arad",
      label: "ČNB - ARAD",
      sourceType: "arad",
      catalogPath: "/catalogs/arad/browse",
      tier: "production",
      addPath: "",
    },
    {
      id: "csu",
      label: "ČSÚ",
      sourceType: "csu",
      catalogPath: "/catalogs/csu/browse",
      tier: "production",
      addPath: "",
    },
    {
      id: "internal",
      label: "Internal",
      sourceType: "internal",
      catalogPath: "/catalogs/internal/browse",
      tier: "production",
      addPath: "",
    },
  ],
  CATALOGS_PRODUCTION: [
    {
      id: "eurostat",
      label: "Eurostat",
      sourceType: "eurostat",
      catalogPath: "/catalogs/eurostat/browse",
      tier: "production",
      addPath: "",
    },
    {
      id: "arad",
      label: "ČNB - ARAD",
      sourceType: "arad",
      catalogPath: "/catalogs/arad/browse",
      tier: "production",
      addPath: "",
    },
    {
      id: "csu",
      label: "ČSÚ",
      sourceType: "csu",
      catalogPath: "/catalogs/csu/browse",
      tier: "production",
      addPath: "",
    },
    {
      id: "internal",
      label: "Internal",
      sourceType: "internal",
      catalogPath: "/catalogs/internal/browse",
      tier: "production",
      addPath: "",
    },
  ],
  CATALOGS_EXPERIMENTAL: [],
  WB_DEFAULT_COUNTRY: "CZE",
  };
});

vi.mock("@/lib/catalogRowPreviewEligible", () => ({
  __esModule: true,
  isCatalogRowPreviewEligible: () => true,
}), { virtual: true });

vi.mock("recharts", () => {
  const ReactLib = require("react");
  const Div = ({ children }) => ReactLib.createElement("div", null, children);
  return {
    __esModule: true,
    Line: Div,
    LineChart: Div,
    ResponsiveContainer: Div,
    Tooltip: Div,
    XAxis: Div,
    YAxis: Div,
  };
}, { virtual: true });

vi.mock("@/lib/eurostatQueryableSlice", () => ({
  __esModule: true,
  eurostatAiRowNeedsOpenInCatalog: jest.fn(() => false),
}), { virtual: true });

vi.mock("@/lib/catalogAddSourceBody", () => ({
  __esModule: true,
  buildCatalogAddSourceBody: jest.fn(() => ({})),
}), { virtual: true });

vi.mock("@/lib/catalogStubKeys", () => ({
  __esModule: true,
  buildExistingKeys: () => new Set(),
  buildSourceByKey: () => new Map(),
  rowExistingKey: (def, row) => `${String(def?.id || "")}:${String(row?.set_id || "")}`,
}), { virtual: true });

vi.mock("@/lib/catalogPreviewBody", async (importOriginal) => {
  const actual = await importOriginal();
  return {
  ...actual,
  buildCatalogPreviewBody: (def, row) => ({
    source_type: def.sourceType,
    set_id: String(row.set_id || ""),
    name: String(row.name || ""),
    query_params: row.query_params && typeof row.query_params === "object" ? { ...row.query_params } : {},
  }),
  resolveCatalogRowDef: (def) => def,
  };
});

vi.mock("@/lib/catalogBrowseSemantics", () => ({
  __esModule: true,
  getCatalogBrowseSemantics: () => ({ semantic: "set", badge: "", ariaHint: "" }),
  getCatalogBrowseHintCz: () => "",
  getCatalogBrowseLimitedActionHint: () => "",
}), { virtual: true });

vi.mock("@/lib/ecbTopicPresets", () => ({
  __esModule: true,
  getCatalogSearchEcbIntentHint: jest.fn(() => ""),
}), { virtual: true });

vi.mock("@/lib/previewNormalizer", () => ({
  __esModule: true,
  normalizePreviewPayload: (payload) => ({
    source: payload?.source || {},
    rows: Array.isArray(payload?.rows) ? payload.rows : [],
    fields: Array.isArray(payload?.fields) ? payload.fields : [],
    columns: Array.isArray(payload?.columns) ? payload.columns : [],
    metadata: payload?.metadata && typeof payload.metadata === "object" ? payload.metadata : {},
    total_count: Number(payload?.total_count || 0),
    selected_indicator: payload?.selected_indicator || "",
    selected_indicators: Array.isArray(payload?.selected_indicators) ? payload.selected_indicators : [],
    group_field: payload?.group_field || "",
  }),
  previewShapeDebug: (payload) => ({
    keys: payload && typeof payload === "object" ? Object.keys(payload) : [],
    hasRows: Array.isArray(payload?.rows),
    hasData: false,
    hasObservations: false,
    hasFields: Array.isArray(payload?.fields),
    hasColumns: Array.isArray(payload?.columns),
  }),
  buildUnknownPreviewShapeMessage: () => "unknown-preview-shape",
  formatPreviewMessage: (value) => (typeof value === "string" ? value : ""),
  unwrapApiErrorPayload: (x) => x || {},
  buildPreviewPayloadFromStructuredError: () => ({ rows: [], fields: [], columns: [], metadata: {} }),
}), { virtual: true });

vi.mock("@/lib/previewRequestParams", () => ({
  __esModule: true,
  buildSourcePreviewParams: jest.fn(() => ({ limit: 1000 })),
}), { virtual: true });

vi.mock("@/lib/catalogBrowseStatusRegistry", () => ({
  __esModule: true,
  GLOBAL_CATALOG_BROWSE_UI_TIMEOUT_MS: 15000,
  GLOBAL_CATALOG_BROWSE_HTTP_TIMEOUT_MS: 12000,
  buildGlobalBrowseTimeoutHeadlineCz: () => "",
  buildGlobalBrowseTimeoutNextStepCz: () => "",
  getCatalogBrowseDropdownLabel: (id) => {
    if (typeof id === "string") return id;
    if (id && typeof id === "object") return String(id.label || id.id || "");
    return "";
  },
  CATALOG_SOURCE_STATUS_MAP: {},
  GLOBAL_BROWSE_FALLBACK_ROUTE: "/search/catalog",
  shouldSkipUnifiedGlobeBrowseFetch: () => false,
  UNIFIED_GLOBAL_BROWSE_SKIP_CZ: "skip",
}), { virtual: true });

vi.mock("@/lib/catalogBackNav", () => ({
  __esModule: true,
  normalizeCatalogBrowseIdFromUrlParam: (x) => String(x || "").trim().toLowerCase(),
}), { virtual: true });

vi.mock("@/lib/aradCatalogRescueNotice", () => ({
  __esModule: true,
  getAradCatalogRescueNotice: () => "",
}), { virtual: true });

vi.mock("@/components/catalog/CatalogSearchErrorBoundary", () => {
  const ReactLib = require("react");
  return {
    __esModule: true,
    default: ({ children }) => ReactLib.createElement(ReactLib.Fragment, null, children),
  };
}, { virtual: true });

const mockSearchState = {
  selected: new Set(["eurostat"]),
  setSelected: jest.fn(),
  crossSearchQuery: "hicp",
  setCrossSearchQuery: jest.fn(),
  submittedCrossQuery: "hicp",
  setSubmittedCrossQuery: jest.fn(),
  submitCrossSearch: jest.fn(),
  aiQuery: "",
  setAiQuery: jest.fn(),
  debouncedAi: "",
  useAiAssistant: false,
  setUseAiAssistant: jest.fn(),
  setAiSearchScope: jest.fn(),
};

vi.mock("@/hooks/catalogSearch/useCatalogSearchState", async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    useCatalogSearchState: () => mockSearchState,
  };
});

const mockBrowseState = {
  browseCatalogId: "eurostat",
  setBrowseCatalogId: jest.fn(),
  browsePanelFilter: "hicp",
  setBrowsePanelFilter: jest.fn(),
  browseLocalBranchOnly: false,
  setBrowseLocalBranchOnly: jest.fn(),
  debouncedBrowsePanelFilter: "hicp",
  setDebouncedBrowsePanelFilter: jest.fn(),
  openPaths: new Set(),
  setOpenPaths: jest.fn(),
};

vi.mock("@/hooks/catalogSearch/useCatalogBrowseState", () => ({
  __esModule: true,
  useCatalogBrowseState: () => mockBrowseState,
}), { virtual: true });

const mockDeepRunnerState = {
  deepLoading: false,
  deepError: "",
  deepErrorTechnical: "",
  deepData: {
    ok: true,
    verified: [
      {
        catalog_id: "eurostat",
        source_type: "eurostat",
        kind: "set",
        set_id: "prc_hicp_aind",
        name: "HICP annual data",
        parentPath: "Prices > HICP",
        query_params: {
          coicop: "CP00",
          query_mode: "preview",
        },
      },
    ],
    possible: [],
  },
  deepSourceStatuses: [],
  deepFollowupLoading: false,
  deepFollowupError: "",
  deepFollowupResult: null,
  deepConversation: null,
  runDeepSearch: jest.fn(),
  applySuggestedDeepSearch: jest.fn(),
  cancelDeepSearch: jest.fn(),
  runDeepFollowup: jest.fn(),
  setDeepData: jest.fn(),
  setDeepError: jest.fn(),
  setDeepErrorTechnical: jest.fn(),
  setDeepSourceStatuses: jest.fn(),
  setDeepConversation: jest.fn(),
};

vi.mock("@/hooks/catalogSearch/useDeepSearchRunner", () => ({
  __esModule: true,
  useDeepSearchRunner: () => mockDeepRunnerState,
}), { virtual: true });

vi.mock("@/hooks/catalogSearch/useSearchResultsMerge", () => ({
  __esModule: true,
  useSearchResultsMerge: () => ({
    mergeDeepResults: jest.fn((x) => x),
  }),
}), { virtual: true });

import GlobalCatalogSearchPage from "./GlobalCatalogSearchPage";
import api from "@/lib/api";

function flushMicrotasks() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

async function waitForAssert(assertFn, timeoutMs = 4000) {
  const start = Date.now();
  // eslint-disable-next-line no-constant-condition
  while (true) {
    try {
      assertFn();
      return;
    } catch (err) {
      if (Date.now() - start > timeoutMs) throw err;
      await act(async () => {
        await flushMicrotasks();
      });
    }
  }
}

function findPreviewButton(container) {
  return Array.from(container.querySelectorAll("button")).find((btn) => {
    const text = String(btn.textContent || "");
    const title = String(btn.getAttribute("title") || "");
    return /data|náhled|zobrazit data/i.test(text) || /náhled/i.test(title);
  });
}

describe("GlobalCatalogSearchPage eurostat geo selection request shape", () => {
  let container;
  let root;

  beforeEach(() => {
    globalThis.IS_REACT_ACT_ENVIRONMENT = true;
    if (!window.HTMLElement.prototype.scrollIntoView) {
      window.HTMLElement.prototype.scrollIntoView = jest.fn();
    }
    api.get.mockReset();
    api.post.mockReset();
    api.get.mockResolvedValue({ data: [] });
    api.post.mockImplementation((url, body) => {
      if (url === "/catalog/search") {
        return Promise.resolve({
          data: {
            source: "eurostat",
            query: "hicp",
            results: [
              {
                catalog_id: "eurostat",
                catalog_label: "Eurostat",
                source_type: "eurostat",
                set_id: "prc_hicp_aind",
                name: "HICP annual data",
                previewable: true,
                row: {
                  kind: "set",
                  set_id: "prc_hicp_aind",
                  name: "HICP annual data",
                  path: "/prices/hicp/prc_hicp_aind",
                  parentPath: "/prices/hicp",
                  query_params: {
                    coicop: "CP00",
                    query_mode: "preview",
                  },
                },
              },
            ],
            themes_triggered: [],
          },
        });
      }
      if (url === "/catalog/preview") {
        return Promise.resolve({
          data: {
            source: { source_type: "eurostat", set_id: body?.set_id || "prc_hicp_aind", name: "HICP annual data" },
            rows: [{ TIME_PERIOD: "2024", GEO: "HU", GEO_LABEL: "Hungary", value: 3.4 }],
            fields: ["TIME_PERIOD", "GEO", "GEO_LABEL", "value"],
            total_count: 1,
            metadata: {
              filters_applied: body?.query_params || {},
              dimensions: { geo: ["CZ", "DE", "HU"] },
            },
            available_dimensions: { geo: ["CZ", "DE", "HU"] },
          },
        });
      }
      return Promise.resolve({ data: {} });
    });

    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    if (root) {
      act(() => {
        root.unmount();
      });
    }
    if (container && container.parentNode) {
      container.parentNode.removeChild(container);
    }
  });

  test("refetches /catalog/preview with query_params.geo array after geo selection", async () => {
    await act(async () => {
      root.render(React.createElement(GlobalCatalogSearchPage));
      await flushMicrotasks();
    });

    await waitForAssert(() => {
      const previewBtn = findPreviewButton(container);
      expect(previewBtn).toBeTruthy();
    });

    const previewBtn = findPreviewButton(container);
    await act(async () => {
      previewBtn.dispatchEvent(new MouseEvent("click", { bubbles: true }));
      await flushMicrotasks();
    });

    await waitForAssert(() => {
      const previewCalls = api.post.mock.calls.filter(([url]) => url === "/catalog/preview");
      expect(previewCalls.length).toBeGreaterThanOrEqual(1);
    });

    await waitForAssert(() => {
      expect(container.querySelector('[data-testid="mock-source-preview"]')).toBeTruthy();
      expect(container.querySelector('[data-testid="mock-source-preview-loading"]')).toBeFalsy();
    });

    const geoBtn = container.querySelector('[data-testid="mock-source-preview-select-geo"]');
    await act(async () => {
      geoBtn.dispatchEvent(new MouseEvent("click", { bubbles: true }));
      await flushMicrotasks();
    });

    await waitForAssert(() => {
      const previewCalls = api.post.mock.calls.filter(([url]) => url === "/catalog/preview");
      expect(previewCalls.length).toBeGreaterThanOrEqual(2);
    });

    const previewCalls = api.post.mock.calls.filter(([url]) => url === "/catalog/preview");
    const [, secondBody] = previewCalls[1];

    expect(secondBody.source_type).toBe("eurostat");
    expect(secondBody.set_id).toBe("prc_hicp_aind");
    expect(Array.isArray(secondBody.query_params?.geo)).toBe(true);
    expect(secondBody.query_params.geo).toEqual(["CZ", "DE"]);
    expect(secondBody.query_params.geo).not.toBe("CZ,DE");
    expect(secondBody.query_params.coicop).toBe("CP00");
    expect(secondBody.query_params.query_mode).toBeUndefined();
    expect(secondBody.query_params.lastTimePeriod).toBeUndefined();
    expect(secondBody.indicator_id).toBeUndefined();
    expect(secondBody.indicator_ids).toBeUndefined();

    await waitForAssert(() => {
      expect(container.querySelector('[data-testid="mock-source-preview"]')).toBeTruthy();
      expect(container.querySelector('[data-testid="mock-source-preview-loading"]')).toBeFalsy();
    });
  });

  test("renders deep search recommendations in final_rank order and demoted mismatch is not high", async () => {
    const previousData = mockDeepRunnerState.deepData;
    mockDeepRunnerState.deepData = {
      ok: true,
      verified: [
        {
          catalog_id: "internal",
          source_type: "internal",
          set_id: "INT_PROFIT",
          name: "Zisk bank",
          final_rank: 2,
          final_score: 0.21,
          result_tier: "mismatch",
          status: "candidate",
          match_quality_cz: "možná",
          semantic_match_level: "mismatch",
          topic_match: false,
          metric_match: false,
          geo_match: true,
          demotion_reason: "topic_mismatch_bank_deposits_vs_profitability",
          preview_status: "preview_available_semantic_mismatch",
          openai_rerank_position: 1,
          why_relevant: "Preview works but topic is wrong.",
          what_to_verify: "Check metric intent.",
        },
        {
          catalog_id: "arad",
          source_type: "arad",
          set_id: "ARAD_DEP",
          name: "Vklady klientů bank",
          final_rank: 1,
          final_score: 0.96,
          result_tier: "verified",
          status: "verified",
          match_quality_cz: "vysoká",
          semantic_match_level: "exact",
          topic_match: true,
          metric_match: true,
          geo_match: true,
          preview_status: "verified",
          preview_available: true,
          preview_row_count: 24,
          why_relevant: "Exact deposit stock match.",
          what_to_verify: "Preview confirmed.",
        },
      ],
      possible: [],
    };

    try {
      await act(async () => {
        root.render(React.createElement(GlobalCatalogSearchPage));
        await flushMicrotasks();
      });

      await waitForAssert(() => {
        expect(container.textContent).toContain("Top doporučené řady");
        expect(container.textContent).toContain("Vklady klientů bank");
        expect(container.textContent).toContain("Zisk bank");
      });

      const text = container.textContent || "";
      expect(text.indexOf("Vklady klientů bank")).toBeLessThan(text.indexOf("Zisk bank"));
      expect(text).toContain("Slabá shoda");
      expect(text).toContain("neodpovídá významu dotazu");
      expect(text).not.toContain("#2 Zisk bankInternalSlabá shodaShoda: vysoká");
    } finally {
      mockDeepRunnerState.deepData = previousData;
    }
  });

  test("does not render foreign-geo ČSÚ mismatch in top recommendations", async () => {
    const previousData = mockDeepRunnerState.deepData;
    mockDeepRunnerState.deepData = {
      ok: true,
      verified: [
        {
          catalog_id: "csu",
          source_type: "csu",
          set_id: "CEN0101",
          name: "Index spotřebitelských cen",
          final_rank: 1,
          result_tier: "verified",
          status: "verified",
          match_quality_cz: "vysoká",
          semantic_match_level: "mismatch",
          geo_match: false,
          detected_query_geo: { type: "country", country_code: "ES", country_codes: ["ES"] },
          demotion_reason: "geo_mismatch_foreign_country",
          preview_status: "pending",
          why_relevant: "Czech inflation only.",
          what_to_verify: "Foreign-country mismatch.",
        },
      ],
      possible: [],
    };

    try {
      await act(async () => {
        root.render(React.createElement(GlobalCatalogSearchPage));
        await flushMicrotasks();
      });

      await waitForAssert(() => {
        expect(container.textContent).toContain("Index spotřebitelských cen");
      });

      const text = container.textContent || "";
      expect(text).not.toContain("Top doporučené řady");
      expect(text).not.toContain("Shoda: vysoká");
      expect(text).toContain("jinou geografii než požadoval dotaz");
    } finally {
      mockDeepRunnerState.deepData = previousData;
    }
  });

  test("renders Eurostat progressive candidate and keeps ČSÚ foreign mismatch out of main list", async () => {
    const previousData = mockDeepRunnerState.deepData;
    mockDeepRunnerState.deepData = {
      ok: true,
      partial: true,
      progressive: true,
      verified: [],
      possible: [
        {
          catalog_id: "csu",
          source_type: "csu",
          set_id: "CEN0101",
          name: "ČSÚ HICP Czech only",
          final_score: 0.8,
          result_tier: "mismatch",
          status: "candidate",
          semantic_match_level: "mismatch",
          geo_match: false,
          detected_query_geo: { type: "country", country_code: "NL", country_codes: ["NL"] },
          demotion_reason: "geo_mismatch_foreign_country",
          preview_status: "pending",
        },
        {
          catalog_id: "eurostat",
          source_type: "eurostat",
          set_id: "prc_hicp_midx",
          name: "HICP - monthly data Netherlands",
          final_score: 0.7,
          result_tier: "candidate",
          status: "candidate",
          semantic_match_level: "plausible",
          geo_match: true,
          preview_status: "pending",
        },
      ],
    };

    try {
      await act(async () => {
        root.render(React.createElement(GlobalCatalogSearchPage));
        await flushMicrotasks();
      });

      await waitForAssert(() => {
        expect(container.textContent).toContain("HICP - monthly data Netherlands");
      });
      expect(container.textContent || "").not.toContain("Top doporučené řady");
      const beforeDemoted = (container.textContent || "").split("Vyřazené / nízká relevance")[0] || "";
      expect(beforeDemoted).not.toContain("ČSÚ HICP Czech only");
      expect(container.textContent || "").toContain("Vyřazené / nízká relevance");
      expect(container.textContent || "").toContain("ČSÚ HICP Czech only");
      expect(container.textContent || "").toContain("Vyřazené / nízká relevance");
    } finally {
      mockDeepRunnerState.deepData = previousData;
    }
  });

  test("renders partial no-valid warning instead of main ČSÚ candidates when only foreign mismatches exist", async () => {
    const previousData = mockDeepRunnerState.deepData;
    mockDeepRunnerState.deepData = {
      ok: true,
      partial: true,
      progressive: true,
      verified: [],
      possible: [
        {
          catalog_id: "csu",
          source_type: "csu",
          set_id: "CEN0101",
          name: "ČSÚ HICP Czech only",
          result_tier: "mismatch",
          status: "candidate",
          semantic_match_level: "mismatch",
          geo_match: false,
          detected_query_geo: { type: "country", country_code: "NL", country_codes: ["NL"] },
          demotion_reason: "geo_mismatch_foreign_country",
          preview_status: "pending",
        },
      ],
      catalog_index_warnings: [
        "Stream vyhledávání skončil před finálním payloadem; ponechávám průběžné výsledky bez duplicitního POST požadavku.",
      ],
    };

    try {
      await act(async () => {
        root.render(React.createElement(GlobalCatalogSearchPage));
        await flushMicrotasks();
      });

      await waitForAssert(() => {
        expect(container.textContent).toContain("Průběžně zatím bez validních kandidátů");
        expect(container.textContent).toContain("Zkusit znovu");
      });
      expect(container.querySelector("#catalog-deep-candidates-section")).toBeFalsy();
    } finally {
      mockDeepRunnerState.deepData = previousData;
    }
  });

  test("shows diagnostic when Eurostat status has candidates but no Eurostat rows are visible", async () => {
    const previousData = mockDeepRunnerState.deepData;
    mockDeepRunnerState.deepData = {
      ok: true,
      partial: true,
      progressive: true,
      verified: [],
      possible: [
        {
          catalog_id: "csu",
          source_type: "csu",
          set_id: "CEN0101",
          name: "ČSÚ HICP Czech only",
          result_tier: "mismatch",
          status: "candidate",
          semantic_match_level: "mismatch",
          geo_match: false,
          detected_query_geo: { type: "country", country_code: "NL", country_codes: ["NL"] },
          demotion_reason: "geo_mismatch_foreign_country",
          preview_status: "pending",
        },
      ],
      source_statuses: [
        { source: "eurostat", label: "Eurostat", status: "ok", row_count: 5000 },
        { source: "csu", label: "ČSÚ", status: "ok", row_count: 8 },
      ],
    };

    try {
      await act(async () => {
        root.render(React.createElement(GlobalCatalogSearchPage));
        await flushMicrotasks();
      });

      await waitForAssert(() => {
        expect(container.textContent).toContain("našel v indexu 5000 shod");
      });
      expect(container.textContent).toContain("Vyřazené / nízká relevance");
      expect(container.textContent).toContain("ČSÚ HICP Czech only");
      expect(container.querySelector("#catalog-deep-candidates-section")).toBeFalsy();
    } finally {
      mockDeepRunnerState.deepData = previousData;
    }
  });

  test("renders no_valid_result empty state with Eurostat warming retry", async () => {
    const previousData = mockDeepRunnerState.deepData;
    mockDeepRunnerState.runDeepSearch.mockClear();
    mockDeepRunnerState.deepData = {
      ok: true,
      status: "no_valid_result",
      reason: "no_candidate_matches_requested_geo",
      message_cs: "Nepodařilo se najít vhodnou datovou řadu pro požadovanou zemi ve vybraných zdrojích.",
      verified: [],
      possible: [],
      source_statuses: [
        {
          source: "eurostat",
          label: "Eurostat",
          status: "warming",
          row_count: 0,
          message_cs: "Eurostat se ještě připravuje / index se načítá.",
        },
        {
          source: "csu",
          label: "ČSÚ",
          status: "skipped",
          row_count: 0,
          message_cs: "ČSÚ přeskočeno pro zahraniční geo dotaz.",
        },
      ],
    };

    try {
      await act(async () => {
        root.render(React.createElement(GlobalCatalogSearchPage));
        await flushMicrotasks();
      });

      await waitForAssert(() => {
        expect(container.textContent).toContain("Pro požadovanou zemi není dostupná vhodná řada");
        expect(container.textContent).toContain("Nepodařilo se najít vhodnou datovou řadu");
        expect(container.textContent).toContain("Eurostat");
        expect(container.textContent).toContain("připravuji index");
        expect(container.textContent).toContain("Zkusit Eurostat znovu");
      });
      const retry = Array.from(container.querySelectorAll("button")).find((btn) =>
        /Zkusit Eurostat znovu/i.test(String(btn.textContent || "")),
      );
      expect(retry).toBeTruthy();
      await act(async () => {
        retry.dispatchEvent(new MouseEvent("click", { bubbles: true }));
        await flushMicrotasks();
      });
      expect(mockDeepRunnerState.runDeepSearch).toHaveBeenCalled();
      expect(container.textContent || "").not.toContain("Top doporučené řady");
    } finally {
      mockDeepRunnerState.deepData = previousData;
    }
  });
});
