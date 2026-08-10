import { formatTimeAxisTick } from "./chartFormatters";

describe("formatTimeAxisTick", () => {
  it("čtyřmístný rok zobrazí jako rok, ne jako hodnotu '2 tis.'", () => {
    expect(formatTimeAxisTick("2021")).toBe("2021");
    expect(formatTimeAxisTick("2025")).toBe("2025");
    expect(formatTimeAxisTick(2024)).toBe("2024");
  });

  it("rok mimo okno 1900–2100 (historie i projekce) je pořád rok, ne '2 tis.'", () => {
    expect(formatTimeAxisTick("1850")).toBe("1850");
    expect(formatTimeAxisTick("1899")).toBe("1899");
    expect(formatTimeAxisTick("2101")).toBe("2101");
    expect(formatTimeAxisTick("2200")).toBe("2200");
    expect(formatTimeAxisTick("1000")).toBe("1000");
    expect(formatTimeAxisTick(1776)).toBe("1776");
  });

  it("měsíční a kvartální období nechá projít periodovým formátovačem", () => {
    expect(formatTimeAxisTick("2024-08")).not.toMatch(/tis\./);
    expect(formatTimeAxisTick("2025-Q3")).not.toMatch(/tis\./);
  });

  it("malá čísla mimo rozsah roků dál formátuje číselně", () => {
    expect(formatTimeAxisTick("12")).toBe("12");
  });
});
