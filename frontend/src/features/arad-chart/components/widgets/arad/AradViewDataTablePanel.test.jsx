/** @jest-environment jsdom */
import React, { act } from "react";
import { createRoot } from "react-dom/client";
import AradViewDataTablePanel from "./AradViewDataTablePanel";

const chartTheme = {
  tableHeaderBg: "#f8fafc",
  border: "#cbd5e1",
  accent: "#0f172a",
};

function renderPanel(props) {
  global.IS_REACT_ACT_ENVIRONMENT = true;
  const container = document.createElement("div");
  document.body.appendChild(container);
  const root = createRoot(container);

  act(() => {
    root.render(
      <AradViewDataTablePanel
        besideChart={false}
        fsExpand={false}
        chartCompact={false}
        tableBodyHeight={280}
        chartTableTransposed={false}
        isMultiSeries={false}
        chartTheme={chartTheme}
        unit="%"
        latestDataMode={false}
        seriesList={[]}
        seriesTableLabels={{ fullLabels: [], displayLabels: [] }}
        tableRows={[
          { period: "2021", value: 10.25 },
          { period: "2020", value: null },
        ]}
        {...props}
      />
    );
  });

  return {
    container,
    cleanup() {
      act(() => root.unmount());
      document.body.removeChild(container);
    },
  };
}

describe("AradViewDataTablePanel", () => {
  test("renders single-series table rows from props", () => {
    const { container, cleanup } = renderPanel();

    expect(container.querySelector('[data-testid="arad-view-table-full"]')).not.toBeNull();
    expect(container.textContent).toContain("2021");
    expect(container.textContent).toContain("10,25");
    expect(container.textContent).toContain("10,25 %");
    expect(container.textContent).toContain("2020");
    expect(container.textContent).toContain("—");

    cleanup();
  });
});
