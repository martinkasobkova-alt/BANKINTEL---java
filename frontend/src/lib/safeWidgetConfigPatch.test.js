import { safeWidgetConfigPatch } from "@/lib/safeWidgetConfigPatch";

describe("safeWidgetConfigPatch", () => {
  it("returns false when patch fn is missing", async () => {
    await expect(safeWidgetConfigPatch(null, "w1", {})).resolves.toBe(false);
    await expect(safeWidgetConfigPatch(undefined, "w1", {})).resolves.toBe(false);
  });

  it("returns false when widget id is missing", async () => {
    const patchFn = jest.fn();
    await expect(safeWidgetConfigPatch(patchFn, "", {})).resolves.toBe(false);
    expect(patchFn).not.toHaveBeenCalled();
  });

  it("returns true when patch succeeds", async () => {
    const patchFn = jest.fn().mockResolvedValue(true);
    await expect(safeWidgetConfigPatch(patchFn, "w1", { title: "A" })).resolves.toBe(true);
    expect(patchFn).toHaveBeenCalledWith("w1", { title: "A" });
  });

  it("returns false when patch throws (403 apod.)", async () => {
    const patchFn = jest.fn().mockRejectedValue({ response: { status: 403 } });
    await expect(safeWidgetConfigPatch(patchFn, "w1", {})).resolves.toBe(false);
  });

  it("returns false when patch resolves false", async () => {
    const patchFn = jest.fn().mockResolvedValue(false);
    await expect(safeWidgetConfigPatch(patchFn, "w1", {})).resolves.toBe(false);
  });
});
