import React from "react";
import { act } from "react";
import { createRoot } from "react-dom/client";
import { vi } from "vitest";
import CatalogSeriesFullscreenDetail from "./CatalogSeriesFullscreenDetail";

/**
 * Živě zjištěno: detail jedné konkrétní řady ukazoval cestu jen jako "ECB" + název řady, obojí
 * neklikací. full_path appka u řady ukládá už dneska (např. "ECB · ověřené řady > RO > ICP"),
 * jen se nepoužíval jako klikací cesta - tenhle test pokrývá opravu.
 */
describe("CatalogSeriesFullscreenDetail breadcrumb", () => {
  let container;
  let root;

  beforeEach(() => {
    container = document.createElement("div");
    document.body.appendChild(container);
    root = createRoot(container);
  });

  afterEach(() => {
    act(() => {
      root.unmount();
    });
    container.remove();
    document.body.querySelectorAll("[data-testid='catalog-series-fullscreen-detail']").forEach((el) => el.remove());
  });

  function render(props) {
    act(() => {
      root.render(
        React.createElement(CatalogSeriesFullscreenDetail, {
          open: true,
          onBack: () => {},
          onClose: () => {},
          title: "Annual rate of change · HICP - EDUCATION · RO",
          code: "ICP/M.RO.N.100000.4.ANR",
          catalogLabel: "ECB",
          ...props,
        }),
      );
    });
  }

  test("renders clickable segments built from catalogPath, not the legacy breadcrumbItems", () => {
    const onOpenCatalogPath = vi.fn();
    render({
      catalogPath: "ECB · ověřené řady > RO > ICP",
      onOpenCatalogPath,
      breadcrumbItems: [{ label: "ECB", kind: "source" }, { label: "Some other title", kind: "series" }],
    });

    const nav = document.querySelector('[aria-label="Cesta v katalogu"]');
    expect(nav).toBeTruthy();
    expect(nav.textContent).toContain("ECB · ověřené řady");
    expect(nav.textContent).toContain("RO");
    expect(nav.textContent).toContain("ICP");
    expect(nav.textContent).not.toContain("Some other title");

    const buttons = nav.querySelectorAll("button[data-testid^='catalog-detail-breadcrumb-segment-']");
    expect(buttons.length).toBe(3);

    act(() => {
      buttons[2].dispatchEvent(new MouseEvent("click", { bubbles: true }));
    });
    expect(onOpenCatalogPath).toHaveBeenCalledWith("ECB · ověřené řady > RO > ICP");
  });

  test("clicking the first segment opens just its own prefix", () => {
    const onOpenCatalogPath = vi.fn();
    render({ catalogPath: "ECB · ověřené řady > RO > ICP", onOpenCatalogPath });

    const buttons = document.querySelectorAll("button[data-testid^='catalog-detail-breadcrumb-segment-']");
    act(() => {
      buttons[0].dispatchEvent(new MouseEvent("click", { bubbles: true }));
    });
    expect(onOpenCatalogPath).toHaveBeenCalledWith("ECB · ověřené řady");
  });

  test("falls back to the legacy plain breadcrumbItems when catalogPath is empty", () => {
    render({
      catalogPath: "",
      breadcrumbItems: [{ label: "ECB", kind: "source" }, { label: "Fallback title", kind: "series" }],
    });

    const nav = document.querySelector('[aria-label="Cesta v katalogu"]');
    expect(nav).toBeTruthy();
    expect(nav.textContent).toContain("Fallback title");
    expect(nav.querySelectorAll("button[data-testid^='catalog-detail-breadcrumb-segment-']").length).toBe(0);
  });

  test("segments render as plain text (not buttons) when onOpenCatalogPath is not supplied", () => {
    render({ catalogPath: "ECB · ověřené řady > RO > ICP", onOpenCatalogPath: undefined });

    const nav = document.querySelector('[aria-label="Cesta v katalogu"]');
    expect(nav.textContent).toContain("ICP");
    expect(nav.querySelectorAll("button[data-testid^='catalog-detail-breadcrumb-segment-']").length).toBe(0);
  });
});
