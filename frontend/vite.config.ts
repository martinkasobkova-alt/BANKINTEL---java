import { defineConfig, loadEnv } from "vite";
import type { ProxyOptions } from "vite";
import type { ServerResponse } from "node:http";
import type { Socket } from "node:net";
import react from "@vitejs/plugin-react";
import path from "node:path";

const REACT_APP_DEFAULTS = [
  "REACT_APP_DEV_PROXY",
  "REACT_APP_BACKEND_URL",
  "REACT_APP_PROD_SAME_ORIGIN_API",
  "REACT_APP_API_TIMEOUT_MS",
  "REACT_APP_AUTH_TIMEOUT_MS",
  "REACT_APP_EXPLORE_TIMEOUT_MS",
  "REACT_APP_EXPLORE_POLL_MAX_MS",
  "REACT_APP_SENTRY_DSN",
  "REACT_APP_TURNSTILE_SITE_KEY",
  "REACT_APP_DEEP_SEARCH_CHUNK_TIMEOUT_MS",
  "REACT_APP_CATALOG_AI_QUICK_ESTIMATE_SEC",
  "REACT_APP_CATALOG_AI_QUICK_TIMEOUT_MS",
  "REACT_APP_DEEP_SEARCH_TIMEOUT_MS",
  "REACT_APP_CATALOG_SOURCE_ROUTE_TIMEOUT_MS",
  "REACT_APP_PROXY_TARGET",
  "REACT_APP_PROXY_TIMEOUT_MS",
] as const;

function vendorChunkName(moduleId: string): string | null {
  const id = moduleId.replaceAll("\\", "/");
  if (!id.includes("/node_modules/")) return null;
  if (id.includes("/react/") || id.includes("/react-dom/") || id.includes("/react-router-dom/")) {
    return "vendor-react";
  }
  if (id.includes("/recharts/") || id.includes("/d3-") || id.includes("/victory-vendor/")) {
    return "vendor-charts";
  }
  if (id.includes("/pdfjs-dist/")) {
    return "vendor-pdf";
  }
  if (id.includes("/pptxgenjs/") || id.includes("/html-to-image/")) {
    return "vendor-export";
  }
  if (id.includes("/@sentry/")) {
    return "vendor-observability";
  }
  if (id.includes("/lucide-react/")) {
    return "vendor-icons";
  }
  return "vendor-core";
}

function isKnownPdfJsEvalWarning(warning: unknown): boolean {
  const raw = warning && typeof warning === "object" ? warning as Record<string, unknown> : {};
  const id = String(raw.id || raw.loc || raw.frame || "");
  const message = String(raw.message || raw.plugin || raw.code || warning || "");
  return id.includes("pdfjs-dist") && message.toLowerCase().includes("eval");
}

function reactAppProcessEnvDefine(mode: string): Record<string, string> {
  const env = loadEnv(mode, process.cwd(), "");
  const define: Record<string, string> = {
    "process.env.NODE_ENV": JSON.stringify(
      mode === "production" ? "production" : "development",
    ),
  };

  for (const [key, val] of Object.entries(env)) {
    if (key.startsWith("REACT_APP_") || key.startsWith("VITE_")) {
      define[`process.env.${key}`] = JSON.stringify(val);
    }
  }

  for (const key of REACT_APP_DEFAULTS) {
    if (!(`process.env.${key}` in define)) {
      define[`process.env.${key}`] = JSON.stringify("");
    }
  }

  return define;
}

function stripBrowserOriginForDevProxy(proxy: {
  on: (event: "proxyReq", handler: (proxyReq: { removeHeader: (name: string) => void }) => void) => void;
}) {
  proxy.on("proxyReq", (proxyReq) => {
    proxyReq.removeHeader("origin");
  });
}

function proxyTarget(mode: string): string {
  return (
    process.env.REACT_APP_PROXY_TARGET
    || loadEnv(mode, process.cwd(), "").REACT_APP_PROXY_TARGET
    || "http://127.0.0.1:8080"
  );
}

/**
 * Bez tohoto byl proxy nastavený na `timeout: 0, proxyTimeout: 0` — když se spojení na
 * backend jednou zaseklo (typicky když Vite naběhl dřív než backend), požadavky nikdy
 * neselhaly ani nevypršely a stránka se nikdy nedonačetla. Teď dostane klient po
 * uplynutí limitu (nebo při chybě spojení) regulérní 504/502 s JSON tělem.
 *
 * Jde o záchrannou brzdu proti zaseknutému socketu, ne o UX limit — ten patří klientovi
 * (viz API_TIMEOUT_MS / AUTH_TIMEOUT_MS v `src/lib/api.js`). Proto je strop velkoryse nad
 * nejpomalejším naměřeným synchronním voláním (/api/explore/sector ~44 s).
 */
const DEV_PROXY_TIMEOUT_DEFAULT_MS = 180_000;

function devProxyTimeoutMs(mode: string): number {
  const raw = Number(
    process.env.REACT_APP_PROXY_TIMEOUT_MS
    || loadEnv(mode, process.cwd(), "").REACT_APP_PROXY_TIMEOUT_MS
    || DEV_PROXY_TIMEOUT_DEFAULT_MS,
  );
  return Number.isFinite(raw) && raw > 0 ? raw : DEV_PROXY_TIMEOUT_DEFAULT_MS;
}

type DevProxyServer = Parameters<NonNullable<ProxyOptions["configure"]>>[0];

function failFastOnProxyError(proxy: DevProxyServer) {
  proxy.on("error", (err, _req, res) => {
    const code = String((err as NodeJS.ErrnoException)?.code || "");
    const status = code === "ECONNREFUSED" || code === "ENOTFOUND" ? 502 : 504;
    if (!res) return;
    // U websocket upgrade je `res` holý Socket — nemá writeHead/end, jen ho zavřeme.
    const httpRes = res as ServerResponse;
    if (typeof httpRes.writeHead !== "function" || typeof httpRes.end !== "function") {
      (res as Socket).destroy?.();
      return;
    }
    if (httpRes.writableEnded) return;
    try {
      if (!httpRes.headersSent) {
        httpRes.writeHead(status, { "content-type": "application/json; charset=utf-8" });
      }
      httpRes.end(
        JSON.stringify({
          detail: "Dev proxy nedosáhl na backend. Zkontrolujte, že backend běží na správném portu.",
          error: "dev_proxy_upstream_unreachable",
          code: code || "PROXY_ERROR",
        }),
      );
    } catch {
      // socket už je pryč — nic víc s tím neuděláme
    }
  });
}

export default defineConfig(({ mode }) => ({
  plugins: [
    react({
      include: /\.(jsx|js|tsx|ts)$/,
    }),
  ],
  envPrefix: ["VITE_", "REACT_APP_"],
  define: reactAppProcessEnvDefine(mode),
  resolve: {
    alias: [
      {
        find: "@/components/catalog",
        replacement: path.resolve(__dirname, "./src/features/ai-search/components/catalog"),
      },
      {
        find: "@/hooks/catalogSearch",
        replacement: path.resolve(__dirname, "./src/features/ai-search/hooks/catalogSearch"),
      },
      {
        find: "@/components/explore",
        replacement: path.resolve(__dirname, "./src/features/manager-explorer/components/explore"),
      },
      {
        find: "@/components/archive",
        replacement: path.resolve(__dirname, "./src/features/archive-reader/components/archive"),
      },
      {
        find: "@/components/widgets/AradView.jsx",
        replacement: path.resolve(__dirname, "./src/features/arad-chart/components/widgets/AradView.jsx"),
      },
      {
        find: "@/components/widgets/AradView",
        replacement: path.resolve(__dirname, "./src/features/arad-chart/components/widgets/AradView.jsx"),
      },
      {
        find: "@/components/widgets/arad",
        replacement: path.resolve(__dirname, "./src/features/arad-chart/components/widgets/arad"),
      },
      { find: "@", replacement: path.resolve(__dirname, "./src") },
    ],
  },
  optimizeDeps: {
    include: [
      "pdfjs-dist/build/pdf",
      "pdfjs-dist/build/pdf.worker.entry",
      "recharts",
      "topojson-client",
    ],
  },
  server: {
    port: 5173,
    // Bez tohoto Vite naslouchá jen na "localhost", což se na Windows umí přeložit
    // výhradně na IPv6 loopback (::1) - 127.0.0.1 pak reálný prohlížeč odmítne
    // (ERR_CONNECTION_REFUSED), i když server běží.
    host: true,
    compress: false,
    proxy: {
      // SSE (deep-search / explore stream) musí zůstat bez časového limitu — dlouhá
      // odmlka mezi eventy je normální. Klíč začíná "^", takže ho Vite bere jako regex
      // a vyhodnocuje se dřív než obecné "/api".
      "^/api/.*/stream": {
        target: proxyTarget(mode),
        changeOrigin: true,
        secure: false,
        timeout: 0,
        proxyTimeout: 0,
        configure: (proxy) => {
          stripBrowserOriginForDevProxy(proxy);
          failFastOnProxyError(proxy);
          proxy.on("proxyRes", (proxyRes) => {
            const ct = String(proxyRes.headers["content-type"] || "");
            if (ct.includes("text/event-stream")) {
              proxyRes.headers["cache-control"] = "no-cache, no-transform";
              proxyRes.headers["x-accel-buffering"] = "no";
              delete proxyRes.headers["content-encoding"];
            }
          });
        },
      },
      "/api": {
        target: proxyTarget(mode),
        changeOrigin: true,
        secure: false,
        timeout: devProxyTimeoutMs(mode),
        proxyTimeout: devProxyTimeoutMs(mode),
        configure: (proxy) => {
          stripBrowserOriginForDevProxy(proxy);
          failFastOnProxyError(proxy);
          proxy.on("proxyRes", (proxyRes) => {
            const ct = String(proxyRes.headers["content-type"] || "");
            if (ct.includes("text/event-stream")) {
              proxyRes.headers["cache-control"] = "no-cache, no-transform";
              proxyRes.headers["x-accel-buffering"] = "no";
              delete proxyRes.headers["content-encoding"];
            }
          });
        },
      },
      "/health": {
        target: proxyTarget(mode),
        changeOrigin: true,
        timeout: 10_000,
        proxyTimeout: 10_000,
        configure: (proxy) => {
          stripBrowserOriginForDevProxy(proxy);
          failFastOnProxyError(proxy);
        },
      },
    },
  },
  build: {
    commonjsOptions: {
      include: [/pdfjs-dist/, /node_modules/],
    },
    rolldownOptions: {
      onwarn(warning, defaultHandler) {
        if (isKnownPdfJsEvalWarning(warning)) return;
        defaultHandler(warning);
      },
      output: {
        codeSplitting: {
          minSize: 20_000,
          groups: [
            {
              name(moduleId) {
                return vendorChunkName(moduleId);
              },
            },
          ],
        },
      },
    },
  },
}));
