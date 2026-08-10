import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

const REACT_APP_DEFAULTS = [
  "REACT_APP_DEV_PROXY",
  "REACT_APP_BACKEND_URL",
  "REACT_APP_PROD_SAME_ORIGIN_API",
  "REACT_APP_API_TIMEOUT_MS",
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
      "/api": {
        target:
          process.env.REACT_APP_PROXY_TARGET
          || loadEnv(mode, process.cwd(), "").REACT_APP_PROXY_TARGET
          || "http://127.0.0.1:8080",
        changeOrigin: true,
        secure: false,
        timeout: 0,
        proxyTimeout: 0,
        configure: (proxy) => {
          stripBrowserOriginForDevProxy(proxy);
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
        target:
          process.env.REACT_APP_PROXY_TARGET
          || loadEnv(mode, process.cwd(), "").REACT_APP_PROXY_TARGET
          || "http://127.0.0.1:8080",
        changeOrigin: true,
        configure: stripBrowserOriginForDevProxy,
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
