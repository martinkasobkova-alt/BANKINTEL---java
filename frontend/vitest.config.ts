import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import path from "node:path";

export default defineConfig({
  plugins: [react({ include: /\.(jsx|js|tsx|ts)$/ })],
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
  test: {
    globals: true,
    environment: "jsdom",
    include: ["src/**/*.test.{js,jsx,ts,tsx}"],
    setupFiles: ["./vitest.setup.js"],
  },
});
