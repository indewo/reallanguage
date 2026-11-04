export type ServiceHealth = Readonly<{ status: "ok"; service: string }>;

export const getServiceHealth = (service: string): ServiceHealth => {
  const normalized = service.trim();
  if (!normalized) throw new Error("service name is required");
  return { status: "ok", service: normalized };
};
