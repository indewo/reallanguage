import { describe, expect, it } from "vitest";
import { createRequestId } from "./index.js";

describe("createRequestId", () => {
  it("keeps a caller supplied request id", () =>
    expect(createRequestId(" req-1 ")).toBe("req-1"));
  it("creates an id when none is supplied", () =>
    expect(createRequestId()).toMatch(/^[0-9a-f-]{36}$/));
});
