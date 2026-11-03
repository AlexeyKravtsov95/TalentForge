# RuStore / AppGallery: approved APK delivery

This extends [Store delivery](ci-delivery.md). The owner approved the full cycle
and technical scheme on 2026-11-03. It does not change iOS, Play production, or
the working OTA publication workflows. Implementation is not publication: the
first real delivery and both store readbacks still have to be recorded below.

## One-time setup (owner)

The environments below were created on 2026-11-03. Never paste credentials into
chat, PRs, logs, command arguments, or the repository. Enter secret values directly
in GitHub → Settings → Environments → `android-stores`.

| Environment | Protection | Values |
|---|---|---|
| `android-stores` | selected branch `main`, no additional reviewer | secrets `RUSTORE_PRIVATE_KEY_BASE64`, `APPGALLERY_SERVICE_ACCOUNT_JSON`; variables `RUSTORE_KEY_ID`, `APPGALLERY_APP_ID` |
| `android-public-release` | selected branch `main`; sole required reviewer AlexeyKravtsov95 (User); admin bypass disabled | no secrets; one approval after the concrete APK identity is displayed |
| existing `android-signing` | keep existing main restriction | existing app keystore/password/alias/fingerprint; existing upload certificate remains a public comparison pin; Play upload keystore is needed only when Play is selected |
| existing `notifications` | keep existing main restriction | existing `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` |

`APPGALLERY_APP_ID=119465493` is already set. No secret values were read or copied
locally while preparing this change.

### Where the credentials come from

**RuStore:** open [TalentForge → API RuStore](https://console.rustore.ru/apps/2063774012/multi-api-key).
Create an app-scoped API key with application upload/publication access. Put its
key ID in the environment **variable** `RUSTORE_KEY_ID`, and the console's Base64
private key in the **secret** `RUSTORE_PRIVATE_KEY_BASE64`. Preserve the Base64
string as supplied; do not Base64-encode it again. This is an API credential,
not the APK signing key. The API signs `keyId + timestamp` with RSA/SHA-512 and
obtains a short-lived Public-Token. [Official authorization](https://www.rustore.ru/help/work-with-rustore-api/api-authorization-token).

**Huawei:** AppGallery Connect → Users and permissions → API key → Connect API →
**Service Account** → Create. Choose **Developer-level**, role **App administrator**;
name it `TalentForge GitHub delivery`. Huawei downloads `…private.json` on creation.
Paste the **entire JSON** into the environment secret `APPGALLERY_SERVICE_ACCOUNT_JSON`.
Keep the account enabled and authorized for the existing Android application.
Current Huawei guidance recommends Service Accounts for new credentials; the
workflow uses its PS256 JWT directly in Authorization. It does not create a legacy
API client, exchange credentials with another service, or use OAuth consent.
[Official setup](https://developer.huawei.com/consumer/en/doc/AppGallery-connect-Guides/agcapi-getstarted-0000001111845114).

Creating/revoking store credentials is the owner's action. If a store requires a
new legal agreement or interactive security step, complete it in that store.

## Release

1. Owner merges the separate Android version PR, then this workflow PR (or reverse
   order). Wait for `CI` and `mobile-ci` for the exact merged source SHA. The agent
   never merges either PR.
2. Actions → **Store delivery**, branch **main**:
   `revision` = exact full main SHA; `apk_stores` = `both`, `rustore` or `appgallery`;
   `include_play` = false unless explicitly wanted; `mode` = `verify` for a dry
   build, `deliver` to request a concrete release. Selecting APK stores limits this
   run to Android regardless of the legacy `platforms` selector. With
   `apk_stores=none`, the original Android/Play and iOS behavior is retained.
3. The read-only audit checks all statuses/packages of each selected store before
   building. A consumed code is refused. The APK uses the existing applicationId
   `io.github.alexeykravtsov95.talentforge`, app signing key, and production OTA ON.
   Existing source/workflow validators, certificate comparisons, permission/version
   checks, exact-source CI and ≥30-day OTA catalog preflight remain mandatory.
4. In the build summary inspect `sourceSha`, `workflowSha`, `versionCode`,
   `versionName`, `certificateSha256`, `apkSha256`, size, and target stores. In
   `deliver`, approve **android-public-release** once. The approval job name
   includes the manifest SHA-256. Its successful job metadata binds all of these
   fields permanently, including after Actions artifacts expire.
5. A draft GitHub release `android-store-<versionCode>` keeps `manifest.json` and
   `approved.apk`. Initialization requires the matching immutable Actions approval
   artifact from that same trusted main workflow run. Every later use verifies
   the successful approval job name/digest, run provenance and main ancestry.
   The release is a journal, not another publishing/approval step.
6. The same APK is uploaded independently to each selected store. RuStore draft:
   `publishType=INSTANTLY`, `partialValue=100`. Huawei: `releaseType=1`, no
   `releaseTime`, so publication follows approval. No countries, pricing, privacy
   declarations, listings, signing-service settings or app keys are replaced.
7. **Android public store reconciliation** polls at minutes 7/22/37/52 UTC of
   each hour. GitHub may delay scheduled runs. Manual dispatch accepts a GitHub
   journal release ID or checks all journals. It does not rebuild or re-approve.
   Huawei package parsing is asynchronous: at least two minutes elapse before
   submission; a later poll submits it when ready. Stores perform publication
   themselves after review; GitHub confirms the resulting state.
8. Telegram reports each store's status changes, rejection, conflict or unknown
   result, including version, source SHA, APK hash and run URL. Unchanged polling
   does not send repeated messages. A Telegram transport ambiguity is kept as
   UNKNOWN rather than blindly sending duplicates.

`verify` uploads no APK to a store and keeps only reports/checksums in Actions.
An approval binds one release manifest, source, APK and selected target set.
Changing any of these requires a fresh release decision; do not edit journal assets.

## Status and recovery

| Status | Meaning / action |
|---|---|
| `PUBLISHED` | RuStore ACTIVE at 100% with the bound version/package receipt; Huawei onShelfVersionCode **and** onShelfVersionId identify the package whose shaCode matches the approved APK. Publication is proven at `observedAt`; later reports explicitly mark retained proof as `previouslyConfirmed`. This is delivery evidence, not indefinite availability monitoring. |
| `SUBMITTED`, `IN_REVIEW` | Submission/review, not availability. Let reconciliation continue. |
| `PROCESSING` | Huawei attachment/parsing or release transition; not publication. |
| `REJECTED` | Store review rejected the update; inspect the store's review comments and prepare the correction. No automatic resubmission. |
| `CONFLICT`, `MISMATCH` | A version/package/hash/candidate or publication setting differs. Stop for that store; inspect the durable evidence. Do not overwrite a console edit. |
| `UNKNOWN` | A write may already have succeeded. Readback cannot prove enough identity to safely repeat it. Investigate in the console/API; never erase the intent or rerun a fresh upload blindly. |
| `CONFIG`, `CONTRACT`, `GATE`, `STORE` | Missing access/settings, unexpected wire contract/status, untrusted approval or read failure. Correct the stated cause; resume reconciliation of the same journal. |

Before each non-idempotent call the workflow creates an immutable intent asset;
after a confirmed response it creates a receipt. Asset writes are create-only,
bound to the manifest digest, and checked byte-for-byte if a GitHub response is
lost. Initial delivery and reconciliation share `concurrency: store-delivery`,
without cancelling a running upload. GitHub retains one pending run for a group;
new pending polls can replace another pending run, not a running one.

Use **Re-run failed jobs** for a failed initial delivery job, or the reconciliation
workflow with the same journal ID. Do not start a new build of a consumed code.
One store's successful result is retained if the other fails. Huawei package-list
SHA-256 can reconcile a lost attachment response. Its current candidate is reread
immediately before app-submit so another console package is not submitted.

RuStore does **not** document a package SHA-256 in its detail API. A normal upload
is bound by the upload response, exact local bytes, version ID and stored apkUid;
later readbacks require that same UID/version/size. If the upload response or its
receipt is lost before a UID can be saved, a versionCode alone is insufficient:
the store stays UNKNOWN and needs owner recovery with store-side byte evidence.
The automation never invents a store-reported hash or blindly reuploads it.

Every poll report lives in Actions (`android-store-report`). Only state transitions,
write receipts and publication proofs consume immutable journal assets; unchanged
polls add none. Telegram receipts are keyed by transition sequence, so a return to
an earlier error state is reported again. Each store keeps its own terminal proof,
so a confirmed partial success is retained while the other finishes review. A broken
journal is reported independently and does not block later journals or notifications.
The draft release keeps the approved APK and evidence beyond Actions artifact
retention. Deleting the original run/approval job or altering journal assets breaks
provenance and stops that release. Do not delete active journals/runs.

## Completion record

An upload HTTP 200, successful workflow, approved review, or one published store
does not complete this task. Both stores must confirm this exact release as
published at full rollout. The combined report's `complete` must be true.

| Field | First UX update |
|---|---|
| Main at initial audit | `b114dd97c8437960af7927772c5dd6a33fb2ce8d` |
| Live published versions before update | RuStore `1.0.0 (1)`, 100%; AppGallery `1.0.0 (3)`; Play Alpha `1.0.0 (4)` |
| Separate version PR | [#111](https://github.com/AlexeyKravtsov95/TalentForge/pull/111), Android `1.0.0 (5)`; not yet a built/published APK |
| Expected existing app certificate SHA-256 (GitHub variable) | `03bd4ad1a3a52aaef112ccb94323b508c6eb03c162033c2002d49db9a7401c1e` |
| Actual source / tooling SHA | pending merged release build |
| Actual APK / certificate SHA-256 | pending signed APK verification |
| RuStore version ID / published result | pending |
| AppGallery package ID / published result | pending |
| Telegram result | pending |

Fill the actual release identity and publication readbacks in this record and
[android-versioning](android-versioning.md) once confirmed. The pending entries
are deliberate; no publication has been claimed from offline tests.
