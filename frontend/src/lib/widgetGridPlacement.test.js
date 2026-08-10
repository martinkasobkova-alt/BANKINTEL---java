import {
  computeLayoutAfterDashboardDrop,
  gridRectToConfig,
  resolveDashboardDropZone,
} from "./widgetGridPlacement";

function w(id, width, rect) {
  return {
    id,
    width,
    type: "arad_view",
    config: rect ? { ...gridRectToConfig(rect) } : {},
  };
}

describe("dashboard layout persistence (horizontal vs vertical stacks)", () => {
  it("resolveDashboardDropZone: center requests swap, edges stay directional", () => {
    expect(resolveDashboardDropZone({}, {}, 0.5, 0.5)).toBe("swap");
    expect(resolveDashboardDropZone({}, {}, 0.2, 0.5)).toBe("before");
    expect(resolveDashboardDropZone({}, {}, 0.9, 0.5)).toBe("after");
    expect(resolveDashboardDropZone({}, {}, 0.5, 0.1)).toBe("above");
    expect(resolveDashboardDropZone({}, {}, 0.5, 0.9)).toBe("below");
  });

  it("swap drop exchanges grid rects between two widgets", () => {
    const left = w("left", "half", {
      colStart: 1,
      colEnd: 7,
      rowStart: 1,
      rowEnd: 3,
    });
    const right = w("right", "half", {
      colStart: 7,
      colEnd: 13,
      rowStart: 1,
      rowEnd: 3,
    });
    const widgets = [left, right];
    const getWidget = (id) => widgets.find((x) => x.id === id);

    const { widgetLayout, nextIds } = computeLayoutAfterDashboardDrop(
      widgets,
      "left",
      "right",
      "swap",
      getWidget
    );

    expect(widgetLayout.left.grid_column_start).toBe(7);
    expect(widgetLayout.left.grid_column_end).toBe(13);
    expect(widgetLayout.right.grid_column_start).toBe(1);
    expect(widgetLayout.right.grid_column_end).toBe(7);
    expect(nextIds.length).toBe(2);
  });

  it("horizontal drop of full-width widgets auto-splits into two halves", () => {
    const left = w("left", "full", {
      colStart: 1,
      colEnd: 13,
      rowStart: 1,
      rowEnd: 3,
    });
    const drag = w("drag", "full", {
      colStart: 1,
      colEnd: 13,
      rowStart: 3,
      rowEnd: 5,
    });
    const widgets = [left, drag];
    const getWidget = (id) => widgets.find((x) => x.id === id);

    const { widgetLayout } = computeLayoutAfterDashboardDrop(
      widgets,
      "drag",
      "left",
      "after",
      getWidget
    );

    expect(widgetLayout.drag.grid_column_start).toBe(7);
    expect(widgetLayout.drag.grid_column_end).toBe(13);
    expect(widgetLayout.left.grid_column_start).toBe(1);
    expect(widgetLayout.left.grid_column_end).toBe(7);
  });

  it("horizontal move does not change grid lines of unrelated vertical stack", () => {
    const stackTop = w("stackTop", "eighth", {
      colStart: 10,
      colEnd: 13,
      rowStart: 1,
      rowEnd: 2,
    });
    const stackBot = w("stackBot", "eighth", {
      colStart: 10,
      colEnd: 13,
      rowStart: 2,
      rowEnd: 3,
    });
    const leftQuarter = w("leftQ", "quarter", {
      colStart: 1,
      colEnd: 4,
      rowStart: 1,
      rowEnd: 3,
    });
    const midEighth = w("mid", "eighth", {
      colStart: 4,
      colEnd: 7,
      rowStart: 1,
      rowEnd: 2,
    });

    const widgets = [leftQuarter, midEighth, stackTop, stackBot];
    const getWidget = (id) => widgets.find((x) => x.id === id);

    const beforeTop = { ...stackTop.config };
    const beforeBot = { ...stackBot.config };

    const { widgetLayout } = computeLayoutAfterDashboardDrop(
      widgets,
      "leftQ",
      "mid",
      "after",
      getWidget
    );

    expect(widgetLayout.stackTop).toEqual(beforeTop);
    expect(widgetLayout.stackBot).toEqual(beforeBot);
    expect(widgetLayout.stackTop.grid_row_start).toBe(1);
    expect(widgetLayout.stackTop.grid_row_end).toBe(2);
    expect(widgetLayout.stackBot.grid_row_start).toBe(2);
    expect(widgetLayout.stackBot.grid_row_end).toBe(3);
  });

  it("vertical stack shifts overlapping widgets below insertion point", () => {
    const targetTop = w("targetTop", "eighth", {
      colStart: 10,
      colEnd: 13,
      rowStart: 1,
      rowEnd: 2,
    });
    const drag = w("drag", "eighth", {
      colStart: 1,
      colEnd: 4,
      rowStart: 1,
      rowEnd: 2,
    });
    const wide = w("wide", "half", {
      colStart: 7,
      colEnd: 13,
      rowStart: 2,
      rowEnd: 4,
    });

    const widgets = [drag, targetTop, wide];
    const getWidget = (id) => widgets.find((x) => x.id === id);

    const { widgetLayout } = computeLayoutAfterDashboardDrop(
      widgets,
      "drag",
      "targetTop",
      "below",
      getWidget
    );

    expect(widgetLayout.drag.grid_column_start).toBe(10);
    expect(widgetLayout.drag.grid_column_end).toBe(13);
    expect(widgetLayout.drag.grid_row_start).toBe(2);
    expect(widgetLayout.drag.grid_row_end).toBe(3);
    // Half-width widget overlaps same columns and starts at insertion row -> must be pushed down.
    expect(widgetLayout.wide.grid_row_start).toBe(3);
    expect(widgetLayout.wide.grid_row_end).toBe(5);
  });
});
