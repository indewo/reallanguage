import type { SceneFormationProvider } from "../application/scene-formation.js";
export class HttpSceneFormationProvider implements SceneFormationProvider {
  public constructor(
    private readonly config: Readonly<{ endpoint: string; apiKey: string }>,
    private readonly fetchImplementation: typeof fetch = fetch,
  ) {}
  public async execute(
    task: string,
    command: Readonly<Record<string, unknown>>,
  ): Promise<unknown> {
    const response = await this.fetchImplementation(this.config.endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.apiKey}`,
        "Content-Type": "application/json",
        "Idempotency-Key": String(command.commandId),
        "X-RealLanguage-Account-Id": String(command.accountId),
      },
      body: JSON.stringify({ task, input: command }),
    });
    if (!response.ok)
      throw new Error("Scene formation provider request failed");
    return response.json();
  }
}
