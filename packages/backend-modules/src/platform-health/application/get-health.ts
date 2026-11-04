import {
  getServiceHealth,
  type ServiceHealth,
} from "../domain/service-health.js";

export class GetHealth {
  public execute(service: string): ServiceHealth {
    return getServiceHealth(service);
  }
}
