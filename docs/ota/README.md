# Remote dataset delivery (OTA)

Goal: after one app update, new Forever talent trees, icons, descriptions and translations reach
users without a new APK/AAB or iOS binary.

**Status (2026-10-28): production OTA is on in the delivered app builds.** The signed production
catalog serves `1.60.1.70450-r1`. Android `1.0.0` / `versionCode 4` (Google Play, track `alpha`)
and iOS `1.0.0 (4)` (TestFlight, internal group) were built with production OTA **ON**,
delivered by run 40540792139 and checked by the owner on 2026-10-28 (installed from the stores
and run: [ci-delivery.md](../release/ci-delivery.md)). Neither is a public store release yet.
New builds are prepared and proposed for review automatically ([auto-prepare.md](auto-prepare.md));
publication stays the owner's manual, offline-signed procedure.

*Historical, M5.OTA.PROD.1:* staging live on VK Cloud, production prepared but disabled in every
app build; the production bucket, hostname and keys did not exist yet
([production-deployment.md](production-deployment.md)).

| Milestone | What | State |
| --- | --- | --- |
| M5.OTA.1 | architecture, contract v1, schemas, reference verifier, G6, 64-case conformance suite | merged (PR #42) |
| M5.OTA.2 | publisher: `talent-data ota …`, gates G1–G8, local store, offline rehearsal | merged (PR #43) |
| M5.OTA.3 | KMP client: verifiers, install/activation/retention, Android/iOS/JVM adapters, UI; disabled | merged (PR #44) |
| M5.OTA.4a | publisher ↔ client integration: local staging stand over HTTPS, staging E2E, Android staging debug configuration, ADR-0002 | merged (PR #45, [report](M5.OTA.4a-integration-report.md)) |
| M5.OTA.4b | VK Cloud remote staging (OD-1): S3 store adapter, real publication, JVM and TECNO E2E over public HTTPS, ADR-0002 accepted (parallelism 8) | merged ([report](M5.OTA.4b-remote-staging-report.md)) |
| M5.OTA.PROD.1 | production S3 publisher (channel isolation, catalog guard), read-only production preflight, key ceremony helper, VK Cloud templates and owner runbook; no cloud resource created, OTA still disabled | this milestone ([runbook](production-deployment.md)) |
| M5.OTA.AUTO.1 | automatic preparation of a new publishable build in GitHub Actions: detection against the signed production catalog, the existing pipeline and gates in a disposable clone, artifacts and a machine-readable report; never publishes | [runbook](auto-prepare.md) |
| M5.OTA.AUTO.3 | a `PREPARED` candidate is pushed to `ota-candidate/<game>-<build>` and proposed as a PR automatically (checked commit, repeatable, retried after failures); never merges or publishes | [runbook](auto-prepare.md#the-review-pull-request-m5otaauto3) |
| M5.OTA.AUTO.2 | historical patch replay 70374 → 70450 end to end: detection over recorded build indexes, the real preparation, a staging publication with an ephemeral key, the real client as the app before the patch over HTTPS; JVM automated, Android emulator by procedure | [report](M5.OTA.AUTO.2-replay-report.md) |

| Document | What it answers |
| --- | --- |
| [ADR-0001-remote-dataset-delivery.md](ADR-0001-remote-dataset-delivery.md) | Why this design: context, decisions D1–D6, alternatives, consequences |
| [ota-contract-v1.md](ota-contract-v1.md) | The wire contract: storage layout, envelope, catalog, release manifest, verification order, error codes, data levels |
| [client-architecture.md](client-architecture.md) | KMP components, reuse of existing interfaces, Android/iOS storage, startup, activation, session pinning, saved builds, retention |
| [client-implementation-notes.md](client-implementation-notes.md) | M5.OTA.3: what the client implements, its decisions and deviations from the design sketch, verification evidence |
| [client-performance-report.md](client-performance-report.md) | M5.OTA.3: test-plan §7 measurements of the per-file design |
| [publisher-and-security.md](publisher-and-security.md) | Publisher pipeline and gates, channels, atomic pointer, rollback, keys, threat model, why `is_bgdl=false` is not "live" |
| [publisher-runbook.md](publisher-runbook.md) | Operator procedures (M5.OTA.2): first publication, refresh, rollback, re-sign, key loss, hosting change |
| [auto-prepare.md](auto-prepare.md) | M5.OTA.AUTO.1/3: when a new build is prepared automatically, statuses (PREPARED / BLOCKED / UNKNOWN), the artifact, idempotency, the automatic review PR and its GitHub settings, and the path from a prepared candidate to a release |
| [test-plan.md](test-plan.md) | Test layers, scenarios S1–S14, performance measurements, release gates: common, Android, iOS |
| [open-decisions.md](open-decisions.md) | OD-1…OD-17, each with a recommended default |
| [M5.OTA.4a-integration-report.md](M5.OTA.4a-integration-report.md) | M5.OTA.4a: what was verified together (PASSED / FAILED / BLOCKED / NOT RUN), CI status, what the owner must provide |
| [M5.OTA.4b-remote-staging-report.md](M5.OTA.4b-remote-staging-report.md) | M5.OTA.4b: VK Cloud origin behaviour, publication, JVM/device scenarios, HTTPS performance (PASSED / FAILED / BLOCKED / NOT RUN) |
| [production-deployment.md](production-deployment.md) | M5.OTA.PROD.1 (Russian, for the owner): VK Cloud bucket/CDN/domain/HTTPS setup, production keys, first publication, refresh, rollback, re-sign, recovery, checks before enabling OTA in an Android release |
| [staging-setup.md](staging-setup.md) | The local staging stand and the VK Cloud remote staging origin: key classes, publishing with the real publisher, HTTPS, clients |
| [android-device-smoke.md](android-device-smoke.md) | The Android device test plan: emulator API 26 / current API, TECNO Spark 40, lifecycle steps |
| [ADR-0002-delivery-performance.md](ADR-0002-delivery-performance.md) | Why parallelism 8 + origin gzip is the minimal v1-compatible fix for the §7.3 triggers; HTTP/2, reproducible PNG, archives assessed |
| [M5.OTA.2-publisher-brief.md](M5.OTA.2-publisher-brief.md) | Implementation brief: publisher (GLM) |
| [M5.OTA.3-client-brief.md](M5.OTA.3-client-brief.md) | Implementation brief: KMP client (Opus) |
| [M5.OTA.4c-retry-remeasurement.md](M5.OTA.4c-retry-remeasurement.md) | M5.OTA.4c: the per-request retry measured against VK staging (read-only), retry vs. the M5.OTA.4b client, real and injected connection loss |
| [M5.OTA.AUTO.2-replay-report.md](M5.OTA.AUTO.2-replay-report.md) | M5.OTA.AUTO.2 (Russian): the historical replay 70374 → 70450 — entrypoint, what came over the network, signature and integrity, every scenario (PASS / FAIL / BLOCKED / NOT RUN), the emulator procedure |

Integration (M5.OTA.4a): `scripts/ota_integration_check.sh` runs the Python baseline, the staging
stand (`scripts/ota_staging_stand.py`: the real publisher, an ephemeral `tf-staging-local-*` key, a
local HTTPS origin) and the Kotlin client against the publisher's exact bytes (`OtaStagingE2ETest`,
`OtaPublisherInteropTest`) in one command.

Executable parts (M5.OTA.1):

* Schemas: `docs/schema/ota-envelope.v1.schema.json`, `ota-catalog.v1.schema.json`,
  `ota-release.v1.schema.json` (generated by `python -m talent_data schema`, drift-tested).
* Models and reference verifier: `src/talent_data/ota/models.py`, `src/talent_data/ota/verify.py`.
* Gate G6 (unsupported mechanics, STOP without override): `src/talent_data/ota/mechanics.py` with
  the frozen level-1 baseline `docs/ota/data-levels/level-1.json`, tested by
  `tests/unit/ota/test_ota_mechanics_gate.py`.
* Conformance suite: `docs/ota/fixtures/v1/` (64 cases over the real 70374 and 70450 data),
  generated by `scripts/generate_ota_fixtures.py` (`--check` reports drift) and replayed by
  `tests/unit/ota/test_ota_contract.py`.

## The design in one picture

```
 publisher (M5.OTA.2, owner-driven)                      static HTTPS origin + CDN
 ──────────────────────────────────                      ─────────────────────────
 main@commit data/ ─▶ build-release ─▶ gates G1–G8 ─▶    v1/blobs/sha256/<hex>                (immutable)
                      approve ─▶ stage (staging key)  ─▶ v1/channels/staging/catalog.json
                      promote (offline prod key)      ─▶ v1/channels/production/catalog.json  (the only mutable object)

 app (M5.OTA.3)
 ──────────────
 start ─▶ local state (active release | bundled product) ─▶ UI               (never waits for the network)
   └─▶ background: catalog ─▶ verify signature / sequence / expiry ─▶ decide (activeReleaseId only)
         ─▶ release manifest (hash-pinned) ─▶ blobs (hash-pinned; bundled/installed copies reused)
         ─▶ strict contracts + existing repositories ─▶ rename into releases/<id> ─▶ atomic state
         ─▶ new DatasetSnapshot for new sessions          (open editors keep theirs)
 saved build ─▶ its own clientBuild: installed release ─▶ bundled ─▶ on-demand download ─▶ typed "unavailable"
```

## Component plan and integration points

| New component | Module | Integrates with |
| --- | --- | --- |
| `OtaContract`, `OtaFailure`, `CatalogVerifier`, `ActivationPolicy`, `ReleaseVerifier` | `shared` | the strict `Json` policy; `sha256Hex` |
| `ReleaseValidator` | `shared` | `BundledTalentDatasetRepository` and the visuals/tooltips/localization repositories (unchanged) |
| `RetentionPolicy`, `OtaState`, `DatasetSnapshot` | `shared` | `LocalBuildRepository` (read-only: referenced client builds) |
| Ports `SignatureVerifier`, `BlobFetcher`, `OtaFileStore`, `OtaClock` | `shared` (interfaces) | implemented per platform in `composeApp` |
| `ReleaseAssetSource`, `RoutingDatasetAssetSource` | `composeApp` | implement the existing `DatasetAssetSource`; wrap `ComposeResourceDatasetAssetSource` |
| `RemoteDatasetCatalog`, `DatasetDownloadManager`, `DatasetCache`, `DatasetActivationManager`, `OtaUpdateCoordinator` | `composeApp` | `App.kt` wiring; `loadEditorSession` / `createClassDraft` take a `DatasetSnapshot` |
| `ProductDatasetResolver` | `composeApp` | `PRODUCT_BUNDLE` becomes the bundled product and the rollback floor |
| `talent-data ota …` commands | root Python | `talent_data.ota` models, verifier and G6 (`mechanics.py` + `docs/ota/data-levels/`), committed `data/` |

Release gating is per platform: Android ships OTA when the common and Android gates pass; the iOS
gate (including a physical iOS device) is required only before iOS enables OTA
([test-plan.md §6](test-plan.md#6-release-readiness-criteria-ota-public-release-gates)).

Unchanged: the allocation engine, every existing schema, normalized data, the saved-build format,
the bundled datasets and their sync scripts, the freshness watcher.
