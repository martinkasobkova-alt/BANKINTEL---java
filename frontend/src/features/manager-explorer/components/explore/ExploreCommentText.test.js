import { describe, expect, it } from "vitest";
import { splitExploreBulletLines } from "@/components/explore/ExploreCommentText";

describe("splitExploreBulletLines", () => {
  it("splits dash bullet lines", () => {
    const text = "- Inflace 2 %\n- HDP stagnuje\n- Verdikt: smíšené";
    expect(splitExploreBulletLines(text)).toEqual([
      "Inflace 2 %",
      "HDP stagnuje",
      "Verdikt: smíšené",
    ]);
  });

  it("accepts bullet and asterisk markers", () => {
    const text = "• První\n* Druhá\n- Třetí";
    expect(splitExploreBulletLines(text)).toEqual(["První", "Druhá", "Třetí"]);
  });

  it("returns null for continuous prose", () => {
    expect(splitExploreBulletLines("Jeden odstavec bez odrážek a se skóre 5.8/10.")).toBeNull();
  });

  it("returns null for a single bullet line", () => {
    expect(splitExploreBulletLines("- Jen jedna odrážka")).toBeNull();
  });
});
