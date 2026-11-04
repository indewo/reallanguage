import { randomUUID } from "node:crypto";
import type { Type } from "@nestjs/common";
import { NestFactory } from "@nestjs/core";
import {
  FastifyAdapter,
  type NestFastifyApplication,
} from "@nestjs/platform-fastify";
import { ApiExceptionFilter } from "./presentation/api-exception.filter.js";
import {
  createPostgresDatabase,
  createPostgresPool,
} from "@reallanguage/database";
import {
  createProductionApiModule,
  type ProductionDatabase,
} from "./production/production-api.module.js";
import { parseProductionConfig } from "./production/production-config.js";

export const createHttpApp = async (
  rootModule: Type<unknown>,
  options: Readonly<{
    allowedOrigins?: readonly string[];
    accessLogger?: (entry: Readonly<{
      remoteAddress: string;
      method: string;
      path: string;
      statusCode: number;
      requestId: string;
    }>) => void;
  }> = {},
): Promise<NestFastifyApplication> => {
  const app = await NestFactory.create<NestFastifyApplication>(
    rootModule,
    new FastifyAdapter({ bodyLimit: 12_000_000 }),
    { logger: false },
  );
  app.setGlobalPrefix("api/v1");
  app.useGlobalFilters(new ApiExceptionFilter());
  if (options.allowedOrigins?.length) {
    app.enableCors({
      origin: [...options.allowedOrigins],
      methods: ["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
      allowedHeaders: ["authorization", "content-type", "x-request-id"],
      credentials: false,
      maxAge: 600,
    });
  }
  const fastify = app.getHttpAdapter().getInstance();
  fastify.addHook("onRequest", (request, reply, done) => {
    const incoming = request.headers["x-request-id"];
    const requestId =
      typeof incoming === "string" && incoming.trim()
        ? incoming.trim().slice(0, 128)
        : randomUUID();
    request.headers["x-request-id"] = requestId;
    void reply.header("x-request-id", requestId);
    done();
  });
  if (options.accessLogger) {
    fastify.addHook("onResponse", (request, reply, done) => {
      const requestId = request.headers["x-request-id"];
      options.accessLogger?.({
        remoteAddress: request.ip,
        method: request.method,
        path: request.url.split("?", 1)[0] ?? request.url,
        statusCode: reply.statusCode,
        requestId: typeof requestId === "string" ? requestId : "unknown",
      });
      done();
    });
  }
  await app.init();
  await app.getHttpAdapter().getInstance().ready();
  return app;
};

export const createApp = async (): Promise<NestFastifyApplication> => {
  const config = parseProductionConfig(process.env);
  const database = createPostgresDatabase<ProductionDatabase>(
    createPostgresPool(config.database),
  );
  try {
    const app = await createHttpApp(
      createProductionApiModule(database, config),
      { allowedOrigins: config.http.allowedOrigins },
    );
    app.enableShutdownHooks();
    return app;
  } catch (error) {
    await database.destroy();
    throw error;
  }
};
