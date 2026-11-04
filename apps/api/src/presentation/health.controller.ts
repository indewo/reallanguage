import { Controller, Get, Headers } from "@nestjs/common";
import { GetHealth } from "@reallanguage/backend-modules/platform-health";
import {
  healthResponseSchema,
  type HealthResponse,
} from "@reallanguage/contracts";

@Controller("health")
export class HealthController {
  private readonly getHealth = new GetHealth();

  @Get()
  public health(@Headers("x-request-id") requestId: string): HealthResponse {
    return healthResponseSchema.parse({
      ...this.getHealth.execute("api"),
      requestId,
    });
  }
}
