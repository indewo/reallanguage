import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";
import { parse } from "yaml";
import { billingSummarySchema } from "./index.js";

const charge = {
  chargeId: "isolated-attempt",
  amountFen: 100_000_000,
  occurredAt: "2026-09-02T00:00:00.000Z",
  modelSource: "isolated-provider/model",
  callType: "INITIAL_CALL",
  operationLabelZh: "隔离调用",
};

describe("existing billing amount limits agree across public contracts", () => {
  it("documents the same integer lower and upper bounds for balance and each charge", async () => {
    const document = parse(
      await readFile(
        new URL("../openapi/real-language-v1.yaml", import.meta.url),
        "utf8",
      ),
    );
    const summary = document.components.schemas.BillingSummary.properties;
    const amount = { type: "integer", minimum: 0, maximum: 100_000_000 };
    expect(summary.balanceAmountFen).toEqual(amount);
    expect(summary.charges.items.properties.amountFen).toEqual(amount);
  });

  it("retains the current zero and maximum boundary without changing the billing rule", () => {
    for (const amountFen of [0, 100_000_000]) {
      const summary = {
        balanceAmountFen: amountFen,
        charges: [{ ...charge, amountFen }],
      };
      expect(billingSummarySchema.parse(summary)).toEqual(summary);
    }
  });

  it("already refuses fractional, coerced and excessive amounts on the server", () => {
    for (const amountFen of [-1, 0.5, "25", 100_000_001]) {
      expect(
        billingSummarySchema.safeParse({
          balanceAmountFen: amountFen,
          charges: [charge],
        }).success,
      ).toBe(false);
      expect(
        billingSummarySchema.safeParse({
          balanceAmountFen: 0,
          charges: [{ ...charge, amountFen }],
        }).success,
      ).toBe(false);
    }
  });
});
