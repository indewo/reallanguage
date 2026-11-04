import { describe, expect, it } from "vitest";
import { GetHealth } from "./get-health.js";

describe("GetHealth", () => {
  it("returns the requested service as ready", () => {
    expect(new GetHealth().execute("api")).toEqual({
      status: "ok",
      service: "api",
    });
  });
});
