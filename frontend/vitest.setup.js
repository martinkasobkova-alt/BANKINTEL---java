import { vi } from "vitest";
import "@testing-library/jest-dom/vitest";

// Pre-existing test files across the repo use the bare Jest global (jest.fn(), jest.mock()).
// Vitest's equivalent is `vi` - alias it so those tests run unmodified under Vitest.
if (typeof globalThis.jest === "undefined") {
  globalThis.jest = vi;
}
