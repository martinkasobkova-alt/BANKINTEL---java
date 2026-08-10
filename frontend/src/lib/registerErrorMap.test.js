import { mapRegisterHttpError } from "./registerErrorMap";

describe("mapRegisterHttpError", () => {
  it("maps 429 to Czech rate-limit message", () => {
    const msg = mapRegisterHttpError({
      response: { status: 429, data: { detail: "Rate limit exceeded" } },
    });
    expect(msg).toMatch(/Příliš mnoho pokusů/);
  });

  it("allows distinct messages for other statuses", () => {
    expect(
      mapRegisterHttpError({ response: { status: 400, data: { detail: "Email already in use" } } })
    ).toContain("e-mailem");
  });
});
