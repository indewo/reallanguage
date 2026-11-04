import { randomUUID } from "node:crypto";

export const createRequestId = (incoming?: string): string => {
  const candidate = incoming?.trim();
  return candidate ? candidate.slice(0, 128) : randomUUID();
};
