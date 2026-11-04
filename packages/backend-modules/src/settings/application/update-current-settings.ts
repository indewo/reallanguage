import type { InitialSettingsSnapshot } from "./complete-initial-settings.js";

export type VersionedSettings = Readonly<{
  settings: InitialSettingsSnapshot;
  rowVersion: number;
}>;
export type UpdateSettingsResult =
  | Readonly<{ status: "SAVED"; rowVersion: number }>
  | Readonly<{ status: "CONFLICT"; rowVersion: number }>
  | Readonly<{ status: "NOT_FOUND" }>;
export interface CurrentSettingsStore {
  readVersioned(accountId: string): Promise<VersionedSettings | null>;
  update(command: {
    accountId: string;
    expectedRowVersion: number;
    settings: InitialSettingsSnapshot;
  }): Promise<UpdateSettingsResult>;
}
export class ReadVersionedSettings {
  public constructor(private readonly store: CurrentSettingsStore) {}
  public execute(query: { accountId: string }) {
    return this.store.readVersioned(query.accountId);
  }
}
export class UpdateCurrentSettings {
  public constructor(private readonly store: CurrentSettingsStore) {}
  public execute(command: Parameters<CurrentSettingsStore["update"]>[0]) {
    return this.store.update(command);
  }
}
