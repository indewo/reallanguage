import { describe, expect, it } from "vitest";
import { App } from "./app.js";

describe("Admin composition root", () => {
  it("is available without introducing review behavior", () =>
    expect(App).toBeTypeOf("function"));
});
