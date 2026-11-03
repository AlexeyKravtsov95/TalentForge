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

The guide explicitly requires Developer-level credentials for Connect API. Its
sample export has an empty `project_id`, but it does not specify an emptiness
constraint for that field. Preserve the original console export. Delivery checks
that `project_id` is a required string, accepts empty or populated strings, and
does not infer the account type or permissions from it. JWT signing uses the
documented key, subject and pinned audience; the read-only package/app audit
must succeed for the configured App ID before any APK build or store write.
An authorization refusal remains an error and must be investigated in the
account's actual Type/roles rather than bypassed by editing its JSON.

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

The `public-api-m.rustore.ru` API uses the trust chain documented in
[RuStore's server preparation guide](https://www.rustore.ru/help/developers/monetization/payment-callback/Preparing-the-server-for-RuStore%20API).
Delivery's committed [CA bundle](../../scripts/certs/README.md) is scoped to that
exact host; it does not alter system/mobile trust or disable TLS verification.
For Huawei `CONFIG`, diagnostics name an invalid `project_id` wire type or
`token_uri` without printing their values. Check the original Service Account
JSON in the owner's environment; do not edit credentials to bypass a refusal
or send them to chat. A populated string `project_id` alone is not a refusal.

| Status | Meaning / action |
|---|---|
| `PUBLISHED` | RuStore ACTIVE at 100% with the bound version/package receipt; Huawei onShelfVersionCode **and** onShelfVersionId identify the package whose shaCode matches the approved APK. Publication is proven at `observedAt`; later reports explicitly mark retained proof as `previouslyConfirmed`. This is delivery evidence, not indefinite availability monitoring. |
| `SUBMITTED`, `IN_REVIEW` | Submission/review, not availability. Let reconciliation continue. |
| `PROCESSING` | Huawei attachment/parsing after a confirmed attach response, or release transition; not publication. An attach intent without a receipt stays UNKNOWN, not healthy processing. |
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
An explicit `app-file-info` refusal is stored as `appgallery-attach-error` and
remains an error on later polls while the approved package is absent. A transport
or response-contract error is retained too. Only package-list readback proving the
approved hash, size and candidate identity can resolve that failure. No automatic
upload or attachment retry is performed; intents and failure evidence remain intact.

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
The notifier consumes both `releases` and `errors`. A journal-level error (including
a missing approved APK or manifest) sends a separate operational Telegram alert
with release ID, typed error and run URL; it does not claim a verified store state.
Alert transitions/receipts use a separate envelope bound to the repository's
release ID, so a broken approval manifest does not suppress the alert. Repeated
polls stay quiet; a confirmed healthy reconciliation resets the transition so the
same error returning is reported again. A lost Telegram response stays UNKNOWN
without a blind resend. The original journal errors remain in notification output
and keep its exit code nonzero even when the alert was delivered. If GitHub cannot
read/write alert checkpoints, notification fails closed and its output stays red.
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
| Actual source / tooling SHA | source `9e9d9e188c5a0a10c2e573640b0e0d171f9eebf5`; build workflow `9b463cb23e7d15fce54ecdae6bc567cdd97fc24c` |
| Actual APK / certificate SHA-256 | approved APK `ec2d62fe461d688cfc2c7aa674bfc44d4ef6deac4d6c35480984170d45fa0526` (19,883,006 bytes); certificate `03bd4ad1a3a52aaef112ccb94323b508c6eb03c162033c2002d49db9a7401c1e` |
| RuStore version ID / published result | draft `2064929703` created; upload receipt unresolved; not confirmed published |
| AppGallery package ID / published result | stopped at local upload-URL guard before upload; not confirmed published |
| Telegram result | both initial error transitions have durable `SENT` receipts; publication notification pending |

Fill the actual release identity and publication readbacks in this record and
[android-versioning](android-versioning.md) once confirmed. The pending entries
are deliberate; no publication has been claimed from offline tests.

### First live verify attempt, 2026-11-03

[Run 41609862530](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/41609862530)
used source/tooling `9e9d9e188c5a0a10c2e573640b0e0d171f9eebf5`, Android
`1.0.0 (5)`, `apk_stores=both`, `include_play=false`, `mode=verify`.
The gate confirmed successful CI runs `41601978874` and `41601978959` for that
exact SHA. The read-only store audit stopped before the APK build:

- RuStore: `UNKNOWN: POST transport failure (ConnectError)` at authentication.
  An unauthenticated local probe with the same default Python/httpx trust
  reproduced `CERTIFICATE_VERIFY_FAILED: unable to get local issuer certificate`.
  Loading the official CA chain for this host allowed verified TLS (HTTP 403 for
  the unauthenticated probe). The original CI error does not expose its nested
  cause; the local reproduction identifies the missing-trust prerequisite.
- AppGallery: `CONFIG: Huawei requires Developer-level credentials and pinned audience`.
  This local guard runs before the API call. It means `project_id` is not an
  explicitly empty string, or `token_uri` differs from the pinned audience;
  the original combined diagnostic cannot distinguish them. No secret was read
  locally. The owner must check the original Service Account export/settings.

No APK, public approval, journal, upload or submission was created by this run.
Do not run `deliver` until the same source revision passes a fresh `verify`
with corrected tooling/configuration. A tooling fix on `main` can retain this
source SHA; its distinct workflow SHA must be recorded in the eventual manifest.

### Verify after the TLS fix, 2026-11-03

[Run 41634394482](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/41634394482)
kept source `9e9d9e188c5a0a10c2e573640b0e0d171f9eebf5` and used tooling
`4738998f1990c9fdc505cde1e2e12a157328dc6f` (merged PR #113).
RuStore authentication and audit succeeded: `AVAILABLE`, highest consumed code
`1`, published version ID `2064900758`, `ACTIVE`, 100% rollout.
The code still rejected Huawei locally because `project_id` was populated;
no Huawei API authorization request, APK build or store write occurred.
The owner confirmed this was the original console export and supplied a console
screenshot showing the Service Account and App administrator role. Account Type
was not visible in that screenshot. Subsequent review of the official guide
distinguished its Developer-level requirement from the empty JSON example;
the local emptiness guard was an unsupported inference and is corrected as
described above. Store-side authorization still needs a successful fresh audit.

### Verified APK, skipped approval chain, 2026-11-03

[Verify 41679817702, attempt 2](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/41679817702/attempts/2)
passed both store audits, all three production OTA releases, both APK validators,
the source/OTA release check and checksums. Source remained
`9e9d9e188c5a0a10c2e573640b0e0d171f9eebf5`, tooling was
`3054f094fc1f9827cef7f54a6bb367230a51d8fc`. The owner had corrected Huawei
credentials and the CDN origin request's `Accept-Encoding: identity`, then purged
the blob cache. Attempt 1 had one CDN HTTP 504; a read-only origin probe confirmed
the exact expected object bytes before the single retry. Attempt 2 verified 3/3
OTA releases without changing the validation requirements.

[Deliver 41683811384](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/41683811384)
also passed both audits, OTA and the signed build. Its APK SHA-256 is
`478569a791c771c7f4d199239c983aaa8f8927533cce3ad246f9f65bf36e45c6`,
size 19,883,006 bytes. It was **not approved or uploaded**: GitHub skipped
`public-approval`, `public-journal`, `public-deliver` and `public-notify`, despite
the run's green conclusion. There were no pending deployments or release journal.

The unselected Play ancestor was skipped. GitHub's default status condition
propagates skips through the dependency chain, including jobs after the successful
Android build. The public chain now uses explicit `!cancelled()` conditions and
requires its gate and immediate predecessor to succeed. A failed/skipped build,
unapproved APK, missing journal, cancellation or verify mode still blocks writes.
See [GitHub dependency/status rules](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#jobsjob_idneeds).
These offline condition tests do not prove live approval scheduling; confirm the
single protected approval in the next corrected main workflow run. Do not rerun
the unchanged old workflow or treat the green build as a store delivery.

### Approved delivery failure and read-only inspection, 2026-11-03 UTC

[Run 41696390880](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/41696390880)
passed verify/build checks and the owner's protected approval. Its canonical
manifest digest is `47a982f71fb8aee29cdcf4bb2d34fca9542e34f92dceaf922631ba9716b593b9`.
The approved bytes and manifest are retained in journal release ID `433118574`,
tag `android-store-5`. [Delivery job 126147623306](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/41696390880/job/126147623306)
then failed; [report artifact](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/41696390880/artifacts/13264635787)
has `complete=false`:

- RuStore: `rustore-create-done` binds version ID `2064929703`.
  `rustore-upload-intent` exists but `rustore-upload-done` does not.
  The saved error is `CONFLICT: RuStore must have exactly one universal APK`.
  In this workflow's control flow, that post-upload check follows a successful
  upload API response; the package array cardinality/UID was not saved. This is
  not proof of a rejected upload or proof that retrying is safe. Do not upload
  again, replace the APK, rebuild code 5, or manufacture the missing receipt.
- AppGallery: `CONFIG: untrusted Huawei file-server URL`. The failure is in the
  local authority guard after obtaining upload information, before an upload
  intent or PUT. No upload/attach/submit receipt exists. The old report does not
  retain the returned authority, so its exact mismatch needs a safe readback.
- Both error transitions have `telegram-*-000001-done` receipts with `SENT`.
  Those are operational alerts, not publication confirmations.

The live RuStore console shows the same API draft, without a downloadable APK.
[RuStore documents](https://www.rustore.ru/help/work-with-rustore-api/api-upload-publication-app/apk-file-upload/file-upload-apk)
that API-uploaded files appear in the web interface only after submission for
moderation. An empty console file panel therefore does not prove that an upload
was absent. Do not submit merely to make a file visible.

For diagnosis, dispatch the existing **Android public store reconciliation**
workflow on trusted `main` with `release_id=433118574`, `inspect_only=true`.
Inspection requires one explicit journal ID, proves its original approval and
checks its approved APK bytes before using store credentials. It reads the
RuStore version bound by the create receipt, preserving absent/null/wrong-type
package metadata. For Huawei it reads package/app state and, if this code is
absent, requests upload information solely to report scheme, host, port and
guard/header/hash comparison booleans. It never follows that URL, stores signed
paths/queries or authorization headers, uploads, attaches, submits, repairs a
receipt, changes journal assets or sends Telegram. RuStore authentication still
uses its normal token request. The artifact is **android-store-inspection**;
`OBSERVED` means a read succeeded and does not mean publication.
If optional Huawei upload headers are absent or malformed, the sanitized
authority is retained alongside the typed refusal; inspection does not turn that
response into permission to upload.

Inspection uses the existing concurrency lock and has a separate report schema.
Scheduled runs retain normal reconciliation behavior. Resume a writer only after
the observed refusal is understood and a safe recovery is established; use this
same journal/approved APK. No new build or protected approval is needed for
read-only inspection. The upload authority guard and missing-receipt refusal
remain in force; this diagnostic change does not itself repair either refusal.
