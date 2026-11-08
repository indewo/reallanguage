import { createHash } from "node:crypto";
import {
  ConflictException,
  HttpException,
  NotFoundException,
} from "@nestjs/common";
import type { BilingualPassagesRequest } from "@reallanguage/contracts";
import type { generateBilingualPassages } from "./generate-bilingual-passages.js";

type Command = BilingualPassagesRequest & { accountId: string };
type Result = Awaited<ReturnType<typeof generateBilingualPassages>>;
type Job = {
  fingerprint: string;
  controller: AbortController;
  touched: number;
  done: boolean;
  failed: boolean;
  promise: Promise<Result>;
};
export type BilingualGenerator = (
  cmd: Command,
  checkActive?: () => void,
) => Promise<Result>;

/** Clients may lose a polling connection; supplier billing/deliveries persist and use stable per-stage keys.
 * The transient job table stores only the status and final render, under the authenticated account. */
export class BilingualJobs {
  private readonly jobs = new Map<string, Job>();
  private readonly timer: NodeJS.Timeout;
  private readonly ttl = 20 * 60_000;
  constructor(private readonly generate: BilingualGenerator) {
    this.timer = setInterval(() => this.sweep(), 60_000);
    this.timer.unref();
  }
  close() {
    clearInterval(this.timer);
    for (const job of this.jobs.values()) job.controller.abort();
    this.jobs.clear();
  }
  private sweep() {
    const now = Date.now();
    for (const [key, job] of this.jobs) {
      if (now - job.touched >= this.ttl) {
        job.controller.abort();
        this.jobs.delete(key);
      }
    }
  }
  start(cmd: Command): Job {
    this.sweep();
    const key = JSON.stringify([cmd.accountId, cmd.commandId]);
    const fingerprint = createHash("sha256")
      .update(JSON.stringify([cmd.text, cmd.contentShape, cmd.sourceType]))
      .digest("hex");
    const prior = this.jobs.get(key);
    if (prior && prior.fingerprint !== fingerprint)
      throw new ConflictException("请求编号已用于不同的原文。");
    if (prior && !prior.failed && !prior.controller.signal.aborted) {
      prior.touched = Date.now();
      return prior;
    }
    if (!prior && this.jobs.size >= 96) {
      const expired = [...this.jobs.entries()]
        .filter(([, j]) => j.done)
        .sort((a, b) => a[1].touched - b[1].touched)[0];
      if (expired) this.jobs.delete(expired[0]);
      else throw new HttpException("正在处理多份材料，请稍后重试。", 429);
    }
    const controller = new AbortController();
    const check = () => controller.signal.throwIfAborted();
    const job: Job = {
      fingerprint,
      controller,
      touched: Date.now(),
      done: false,
      failed: false,
      promise: Promise.resolve().then(async () => {
        if (prior && !prior.done) await prior.promise.catch(() => {});
        check();
        try {
          return await this.generate(cmd, check);
        } finally {
          check();
        }
      }),
    };
    this.jobs.set(key, job);
    // Attach rejection handling immediately even if the HTTP client goes away before the first poll.
    job.promise.then(
      () => {
        job.done = true;
        job.touched = Date.now();
      },
      () => {
        job.done = true;
        job.failed = true;
        job.touched = Date.now();
      },
    );
    return job;
  }
  poll(accountId: string, commandId: string): Job {
    this.sweep();
    const job = this.jobs.get(JSON.stringify([accountId, commandId]));
    if (!job || job.controller.signal.aborted) throw new NotFoundException();
    job.touched = Date.now();
    return job;
  }
  cancel(accountId: string, commandId: string) {
    const job = this.jobs.get(JSON.stringify([accountId, commandId]));
    if (job && !job.done) job.controller.abort();
  }
}
