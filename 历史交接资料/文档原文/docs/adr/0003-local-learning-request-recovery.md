# 0003 — Local recovery of submitted learning requests

- Status: Accepted for ordinary LR-03 and LR-06 implementations; other modules remain unaccepted.
- Date: 2026-09-10
- Product baseline: v1.26; no change to learning or review rules.

## Context and decision

Ordinary learning must retain submitted evidence and resume safely after interruption. An in-memory request and a plaintext progress flag do not preserve the original audio request or its idempotency identity after process death. A fresh command must not be manufactured to retry an unknown result.

Use a private, account-scoped encrypted journal for ordinary LR-03. Reuse the existing `FormationPayloadCipher` and atomic compare-and-set file primitive as infrastructure only, without interpreting a learning request as a formation draft or adding formation domain dependencies. Bind authenticated identity to the account, module, fixed chapter, fixed sentence and exact English text. Use a separate Android Keystore namespace and a subdirectory of the account's existing learning-attempt directory. Account cleanup removes those files and that purpose-specific key, not another account or a session key.

Persist and read back the exact body and command before the first HTTP request. Unknown outcomes retain that request; entry alone never sends it. Persist an accepted feedback candidate before releasing its original audio and completing local training. Local-save retries reuse the candidate, not another AI request. A completed journal is not the authority for learning state: re-check the current fixed source and idempotent local training write. No formal mastery or pronunciation evidence is manufactured by this mechanism.

Use revision-checked `Pending`, `Delivered`, `Completed`, `Blocked`, and `Cleared` technical phases. `Completed` means the feedback attempt was consumed, including an incomplete recognition result, not that a learning ability passed. Explicit new recording can replace a cleared record at a new revision. Missing, unreadable, revoked and conflicting records remain distinct. Do not overwrite unreadable evidence or replace its key on read. Requests are bounded by the existing 12,000,000-byte HTTP limit; encrypted envelopes remain bounded below 16 MiB.

## Limits, tests and rollback

### LR-06 implementation increment, 2026-09-10

Ordinary full-sentence speaking uses its own purpose-specific encrypted journal bound additionally to round 0–4 and authenticated account epoch. Training, transcription, confirmed-transcript evaluation and local delivery retain their distinct phases. Persist original audio/body/command before transport, restore read-only, and retry only explicitly. A confirmed transcript is bound to the originating recording, never silently rebound to another attempt; a clarity repeat retains the same learning round. Account cleanup removes only this account's journal/key, and post-read ownership checks reject revoked/replaced sessions. Accepted local delivery clears retained audio according to the existing lifecycle; the journal never manufactures a formal result.

Evidence: `.local-data/recheck-2026-09-07/full-durable-process-e9aa768d-da11-4259-9ded-efd34b3fa6c9/result.json` records five different process phases with actual encrypted journal/SQLite/KeyStore, original body/command/audio identities, read-back and scoped cleanup. The five ProductionGraph regressions in `.local-data/recheck-2026-09-10-timebox/safety-diary-red-full-graph-device.txt` passed; that mixed run also contains two unrelated visual REDs subsequently fixed. These use isolated transport and synthetic `NOT_AUDIO`, not real microphone/AI or a live in-flight kill. LR-06 does not imply Pattern/Scene cross-process unknown-request recovery or alter review restart rules.

This does not change review's chapter-restart rule, add cloud audio retention, authorize background retry, or change AI models and scoring thresholds. Other learning modules require their own integration evidence. Synthetic `NOT_AUDIO` and isolated transport tests prove ordering and identity only, not microphone, AI or physical-device quality.

Validate encryption and invalid records with real cipher/file primitives; validate actual Android Keystore, SQLite ownership, module changes, recreated runtimes, UI retry and process termination independently. A database schema number is not sufficient rollback evidence: old APKs ignore the journal and can lose this recovery capability. Preserve the previous APK and disclose this incompatibility when freezing a new trial release.
