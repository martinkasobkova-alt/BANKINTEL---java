/**
 * UI smoke — klasické hledání se spouští až po Enter/Hledat, ne při psaní.
 * Statické kontraktové testy (bez @testing-library/react).
 */
import fs from "fs";
import path from "path";

const SRC_ROOT = path.resolve(__dirname, "../..");

function readSrc(relPath) {
  return fs.readFileSync(path.join(SRC_ROOT, relPath), "utf8");
}

describe("classic search UI contract", () => {
  it("useCatalogSearchState separates draft query from submittedCrossQuery", () => {
    const src = readSrc("hooks/catalogSearch/useCatalogSearchState.js");
    expect(src).toContain("crossSearchQuery");
    expect(src).toContain("submittedCrossQuery");
    expect(src).toContain("submitCrossSearch");
    expect(src).toContain("crossSearchSubmitNonce");
    expect(src).toMatch(/setCrossSearchSubmitNonce\(\(n\) => n \+ 1\)/);
  });

  it("GlobalCatalogSearchPage runs backend search only on submittedCrossQuery", () => {
    const src = readSrc("pages/GlobalCatalogSearchPage.jsx");
    expect(src).toContain("Klasické hledání jen po Enter / Hledat");
    expect(src).toContain("runCatalogCrossSearch(api, { query: q, catalogDefs: activeDefs })");
    expect(src).toMatch(/\[submittedCrossQuery,\s*crossSearchSubmitNonce/);
    expect(src).not.toMatch(/useEffect\(\(\) => \{[\s\S]*crossSearchQuery[\s\S]*runCatalogCrossSearch/);
  });

  it("UnifiedCatalogSearchHeader exposes Hledat submit via form", () => {
    const src = readSrc("components/catalog/search/UnifiedCatalogSearchHeader.jsx");
    expect(src).toContain("Hledat");
    expect(src).toContain('type="submit"');
    expect(src).toContain("onSubmit={onSubmit}");
    expect(src).toContain('enterKeyHint="search"');
  });
});
