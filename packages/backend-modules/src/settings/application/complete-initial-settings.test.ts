import { describe, expect, it, vi } from "vitest";

import {
  CompleteInitialSettings,
  GetCurrentSettings,
} from "./complete-initial-settings.js";

const settings = {
  formalityLevel: 3,
  slangEnabled: true,
  basicVocabulary: "CET_6" as const,
  purposeVocabularies: ["IELTS_TOEFL", "BUSINESS_ENGLISH"] as const,
  styleReference: "Clear and direct",
  voiceEnabled: true,
  autoPlayEnabled: false,
  speechSpeedLevel: 4,
};

describe("CompleteInitialSettings", () => {
  it("writes the complete snapshot once and returns its authoritative version", async () => {
    const complete = vi.fn().mockResolvedValue(1);
    const useCase = new CompleteInitialSettings({ complete });

    await expect(
      useCase.execute({ accountId: "account-1", settings }),
    ).resolves.toEqual({
      status: "COMPLETED",
      rowVersion: 1,
    });
    expect(complete).toHaveBeenCalledWith("account-1", settings);
  });
});

describe("GetCurrentSettings", () => {
  it("returns the authoritative server snapshot", async () => {
    const read = vi.fn().mockResolvedValue(settings);
    const useCase = new GetCurrentSettings({ read });

    await expect(useCase.execute({ accountId: "account-1" })).resolves.toEqual(
      settings,
    );
    expect(read).toHaveBeenCalledWith("account-1");
  });
});
