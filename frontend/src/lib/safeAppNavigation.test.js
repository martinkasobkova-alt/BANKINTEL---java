import { resolveSafeAppPath } from "./safeAppNavigation";

describe("resolveSafeAppPath", () => {
  it("keeps a valid local chart link", () => {
    expect(resolveSafeAppPath("/my-dashboard?page=p1#widget-w1")).toBe(
      "/my-dashboard?page=p1#widget-w1",
    );
  });

  it("rejects the homepage and external URLs as chart links", () => {
    expect(resolveSafeAppPath("/")).toBe("");
    expect(resolveSafeAppPath("https://example.com/chart")).toBe("");
    expect(resolveSafeAppPath("//example.com/chart")).toBe("");
  });
});
