# Android store delivery implementation plan

> **For agentic workers:** use superpowers:executing-plans. Native execution was
> selected to preserve the existing workflow and keep API/journal changes together.

**Goal:** deliver one approved signed APK to RuStore/AppGallery and verify publication.
**Architecture:** existing source/CI/build gates; durable approved release journal;
independent API adapters and periodic reconciliation; existing Telegram channel.
**Tech stack:** Python/httpx/cryptography, Gradle, GitHub Actions and store APIs.
**Spec:** ../specs/2026-11-03-android-store-delivery-design.md

## Global constraints

Keep package/app signing key, production OTA ON and exact-source CI. One APK for
both stores. Never merge PRs. Version above all consumed Android codes in separate
PR. Store credentials only in GitHub. Do not change iOS/OTA automation.

## Review focus

- Timed-out writes may already have succeeded: read back, never blind retry.
- Partial store success survives a failed run and later runs.
- Uploaded code is not proof of publication or byte identity.
- Approval binds immutable bytes even after source/main/workflow advance.
- Store responses and upload URLs must not disclose tokens or allow arbitrary hosts.

## Tasks

- [x] Audit live histories, approved UX ancestry and source CI; choose version only
  after both histories are known and open a separate version PR if needed.
- [x] Implement strict API adapters and a durable release journal in
  scripts/android_store_delivery.py; test PS256 auth, pagination, upload intent,
  conflicting/unknown/missing hashes, automatic publication and partial success in
  tests/unit/test_android_store_delivery.py using httpx.MockTransport.
- [x] Extend .github/workflows/store-delivery.yml and its gate/build selection for
  independent APK stores while preserving legacy paths; add one approval after
  verified APK and durable evidence before store writes.
- [x] Add .github/workflows/android-store-reconcile.yml for bounded scheduled/manual
  polling and status-change Telegram reports. Test recovery from earlier intents,
  source artifact replacement and already-published state without another upload.
- [ ] Document exact setup/runbook and evidence. Run ruff, format, mypy, pytest,
  actionlint, dataset check and relevant mobile baselines. Update graphify after
  code changes. Open implementation PR; do not merge.
- [ ] After owner configuration/merge/approval, run delivery and verify both stores
  published the bound version; record hashes, IDs and observed publication results.
