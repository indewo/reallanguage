---
name: reallanguage-connection-recovery
description: Diagnose, repair, and continuously improve RealLanguage local API connectivity when the app or phone reports “连接不可用”, cannot reach port 3000, or fails over between the Mac hostname and LAN address.
---

# RealLanguage Connection Recovery

Restore a usable connection, identify the failing layer with evidence, and preserve every newly confirmed cause as reusable project knowledge.

## Project resources

- Project root: `/Users/eric/Documents/英语学习APP`
- Canonical runbook: `/Users/eric/Documents/英语学习APP/docs/LOCAL_API_CONNECTION_RUNBOOK.md`
- Deterministic checker: `/Users/eric/Documents/英语学习APP/tooling/check-local-api-connection.sh`
- Android client: `/Users/eric/Documents/英语学习APP/apps/android`

Read the canonical runbook before changing connection behavior. Run the checker before making a diagnosis, and run it again after repairs.

## Recovery workflow

1. Run `./tooling/check-local-api-connection.sh` from the project root and retain its exact result.
2. Locate the first failing boundary instead of treating every failure as a generic network error:
   - API process and login service;
   - listener and port ownership;
   - loopback health response;
   - current Wi-Fi IPv4 and LAN health response;
   - Bonjour `.local` health response;
   - Android build endpoints, installed build version, local-network permission, proxy/VPN path;
   - phone-to-Mac route and router client isolation;
   - authentication, session, account, or data errors incorrectly presented as connectivity failures.
3. Apply the narrowest repair that preserves account and learning data. Do not begin with reinstalling the app, clearing storage, resetting the account, or deleting progress.
4. Verify the repaired boundary and rerun the complete checker. A listening port alone is insufficient: health must return the RealLanguage API identity.
5. When Mac-side checks pass, use the physical-phone boundary test from the runbook. Do not claim end-to-end success from an emulator or Mac-only check.
6. Report what failed, what changed, what was verified, and any remaining external dependency such as Mac sleep or router isolation.

## Current invariants

- The API health path is `/api/v1/health`.
- The service must listen on all LAN interfaces at port `3000`, not loopback only.
- The Android client uses the Mac `.local` hostname as primary and the current LAN IPv4 as fallback, health-checks candidates, bypasses proxies for LAN hosts, and never sends credentials to an endpoint that does not identify itself as RealLanguage.
- Do not classify authentication, session, storage, parsing, or account failures as “连接不可用”. Preserve the underlying diagnostic category.
- A Mac that is asleep, powered off, logged out, or isolated by the Wi-Fi cannot provide a local API. Code cannot bypass router client isolation.

## Continuous improvement rule

After a failure is reproduced and its cause is confirmed:

1. Add the cause, observable signature, verification command, safe repair, and post-repair check to the canonical runbook.
2. Extend `check-local-api-connection.sh` when the condition can be detected safely and deterministically.
3. Add or update the smallest relevant regression test when application or service code caused the failure.
4. Update this `SKILL.md` only when the new knowledge changes routing, invariants, or the recovery workflow; keep case details in the runbook.
5. Re-run the checker and affected tests. Do not record speculation as a confirmed cause.
6. Synchronize the maintained project copy to `/Users/eric/.codex/skills/reallanguage-connection-recovery` and validate both copies so automatic invocation never uses stale instructions.

This learning loop is part of completing any future RealLanguage connection incident, not optional documentation work.
