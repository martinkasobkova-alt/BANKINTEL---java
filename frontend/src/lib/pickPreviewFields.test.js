import { pickPreviewFields, resolvePreviewAxisFields } from "./pickPreviewFields";

describe("pickPreviewFields", () => {
  test("prefers OBS_VALUE over frequency metadata column", () => {
    const fields = [
      "FREQ",
      "L_MEASURE",
      "frequency",
      "TIME_PERIOD",
      "OBS_VALUE",
      "value",
    ];
    const rows = [
      {
        FREQ: "Q",
        L_MEASURE: "S",
        frequency: "Q",
        TIME_PERIOD: "2022-Q1",
        OBS_VALUE: 1234.5,
        value: 1234.5,
      },
      {
        FREQ: "Q",
        frequency: "Q",
        TIME_PERIOD: "2022-Q2",
        OBS_VALUE: 1250,
        value: 1250,
      },
    ];
    const { timeField, valueField } = pickPreviewFields(fields, rows);
    expect(timeField).toBe("TIME_PERIOD");
    expect(valueField).toBe("OBS_VALUE");
  });

  test("does not use frequency letters as Y values", () => {
    const fields = ["TIME_PERIOD", "frequency", "CBS_BANK_TYPE"];
    const rows = [
      { TIME_PERIOD: "2022-Q1", frequency: "Q", CBS_BANK_TYPE: "40" },
      { TIME_PERIOD: "2022-Q2", frequency: "Q", CBS_BANK_TYPE: "40" },
    ];
    const { valueField } = pickPreviewFields(fields, rows);
    expect(valueField).not.toBe("frequency");
    expect(valueField).not.toBe("CBS_BANK_TYPE");
  });

  test("CSU rows use Roky as time axis and Hodnota as value", () => {
    const fields = ["Ukazatel", "Roky", "Hodnota", "value", "ÚZEMÍ-Kraj"];
    const rows = [
      { Ukazatel: "Byty", "ÚZEMÍ-Kraj": "Praha", Roky: "2019", Hodnota: "80000", value: 80000 },
      { Ukazatel: "Byty", "ÚZEMÍ-Kraj": "Praha", Roky: "2020", Hodnota: "85000", value: 85000 },
      { Ukazatel: "Byty", "ÚZEMÍ-Kraj": "Brno", Roky: "2019", Hodnota: "50000", value: 50000 },
    ];
    const { timeField, valueField } = pickPreviewFields(fields, rows);
    expect(timeField).toBe("Roky");
    expect(valueField).toBe("Hodnota");
  });

  test("does not treat price column as time axis when Roky is present", () => {
    const fields = ["Roky", "Hodnota", "value"];
    const rows = [
      { Roky: "2019", Hodnota: "39306", value: 39306 },
      { Roky: "2020", Hodnota: "44082", value: 44082 },
    ];
    const { timeField } = pickPreviewFields(fields, rows);
    expect(timeField).toBe("Roky");
    expect(timeField).not.toBe("value");
  });

  test("resolvePreviewAxisFields prefers API x_field/y_field hints", () => {
    const fields = ["Roky", "Hodnota", "value"];
    const rows = [
      { Roky: "2019", Hodnota: "39306", value: 39306 },
      { Roky: "2020", Hodnota: "44082", value: 44082 },
    ];
    const axes = resolvePreviewAxisFields(
      { x_field: "Roky", y_field: "Hodnota" },
      fields,
      rows,
    );
    expect(axes.timeField).toBe("Roky");
    expect(axes.valueField).toBe("Hodnota");
  });
});
