import { describe, expect, it } from "vitest";
import { getServiceHealth } from "./service-health.js";

describe("getServiceHealth", () => {
  it("returns a deterministic readiness state", () => {
    expect(getServiceHealth(" api ")).toEqual({ status: "ok", service: "api" });
  });

  it("rejects an unnamed service", () =>
    expect(() => getServiceHealth(" ")).toThrow("service name is required"));
});
