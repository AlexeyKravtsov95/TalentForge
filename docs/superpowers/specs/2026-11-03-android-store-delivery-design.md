# RuStore / AppGallery delivery

Owner approved the technical scheme in chat on 2026-11-03. Full production delivery,
submission for review, automatic release after review and personal Telegram reports
are authorized. PR merges remain the owner's action.

## Audit

- main: `b114dd97c8437960af7927772c5dd6a33fb2ce8d`; CI `41482006038` and
  mobile-ci `41482005987` succeeded for this exact SHA.
- Android source: `1.0.0 (4)`, package `io.github.alexeykravtsov95.talentforge`.
- RuStore live console, 2026-11-03: app `2063774012`, `1.0.0 (1)`, published
  2026-10-30, 100%. AppGallery live console: app `119465493`, published
  `1.0.0 (3)`, uploaded 2026-10-26; second history entry has no package.
  Live Play bundle list contains codes 1–4, active Alpha 4.
- Code 4 is consumed by Play: run `40540792139`, source
  `e07d7825d3ddbe5de3a68d5536595dbe8ab9d048`. Its APK hash is
  `25febe10c387b04ede5009e471f4a294510a150175b32c4844d465f11ec1db2d`;
  this is historical evidence, not the hash of the new release.
- The global Android sequence is binding. The next new build must exceed every
  consumed code, including testing/drafts in all three stores. Lower bound: 5;
  chosen code: 5, in separate [PR #111](https://github.com/AlexeyKravtsov95/TalentForge/pull/111).
- Main contains Home purpose/large-font fixes (#87, `912e265`, `ffdacef`),
  cancelled-touch handling (`75876d8`), inspector reading in sequence (#92,
  `e9291ef`), About simplification (`d00d178`) and rank-chip scaling (`bde2dd7`).
  These are ancestry checks, not a fresh device acceptance test or proof of the
  contents of either currently published APK. No remaining UX backlog is added.

## Approach

Extend the existing Store delivery, using official HTTP APIs and the existing
httpx/cryptography stack. New store goals are independent from Play and iOS.
Retain legacy delivery paths. No third-party publishing action/plugin: it adds
credentials exposure and obscures recovery. No browser automation in CI: sessions
and moderation delays require durable API state.

1. Gate source SHA on main and require existing CI for that exact SHA. Build one
   universal APK with the existing app key and production OTA enabled. Reuse
   source and workflow validators, OTA preflight, version/package/certificate
   checks. Build the Play AAB only when Play was selected.
2. Present APK SHA-256, certificate SHA-256, package, versionCode, versionName,
   source SHA, tooling SHA and target stores before one GitHub Environment
   approval (`android-public-release`). Approval binds those exact bytes.
3. Persist the approved manifest and identical APK as assets of a dedicated draft
   GitHub release. It is the durable release journal, not an additional approval.
   Store writes require this record, never a free-form SHA/hash supplied by a user.
4. For each selected store independently: read all versions/packages first, save
   intent before any non-idempotent request, upload the approved file, reconcile
   identity, submit for review with full automatic publication. A failure in one
   store does not undo or hide success in the other.
5. A scheduled/manual reconciliation workflow reads the durable approved record
   and the stores. Short runs replace multi-day waiting jobs. It never rebuilds
   or re-approves the APK. Telegram uses the existing notifications environment;
   notify transitions/failure/action required, not unchanged moderation polls.

## Official contracts (checked 2026-11-03)

Approval provenance: the successful protected job name includes the manifest digest,
and must belong to Store delivery/dispatch/main in this repository, with tooling SHA
matching the run and source/tooling on main. Initialization also requires its matching
immutable approval artifact. The job metadata remains the durable proof after artifact
expiry. Draft release author/asset uploaders must be Actions; a manually made release
cannot authorize store writes. Journal assets are create-only, named by phase/store,
and every envelope includes the manifest digest. Both workflows share one concurrency
group. Lost GitHub writes are reconciled by exact bytes before any store call.
Huawei candidate version/package ID is re-read immediately before app-submit; foreign
console edits cause CONFLICT.

RuStore uses keyId + timestamp signed with RSA/SHA-512 to obtain Public-Token.
Create a draft with `publishType=INSTANTLY`, upload APK, submit the draft for
moderation. List all version states with pagination and inspect version details.
Only ACTIVE at 100% for the bound code means published; READY_FOR_PUBLICATION,
MODERATION and successful upload responses do not.

Huawei recommends Developer-level Service Accounts for new access (Getting Started
updated 2026-04-03), with PS256 JWT in Authorization. Use app-info and package-list,
upload-url/for-obs with sha256/contentLength, PUT to the returned upload URL, then
app-file-info (fileType=5) and app-submit with releaseType=1 and no releaseTime.
The latter publishes immediately after approval. Package parsing is asynchronous;
wait at least two minutes before submission. Use package-list.shaCode to identify
the APK and app-info.onShelfVersionCode/Id to distinguish the published package
from an update under review. Preserve countries, pricing, privacy and listings.

Sources:
- [RuStore authorization](https://www.rustore.ru/help/work-with-rustore-api/api-authorization-token)
- [RuStore draft](https://www.rustore.ru/help/work-with-rustore-api/api-upload-publication-app/create-draft-version)
- [RuStore upload](https://www.rustore.ru/help/work-with-rustore-api/api-upload-publication-app/apk-file-upload)
- [RuStore moderation](https://www.rustore.ru/help/work-with-rustore-api/api-upload-publication-app/send-draft-app-for-moderation)
- [RuStore versions](https://www.rustore.ru/help/work-with-rustore-api/api-upload-publication-app/get-version-status)
- [RuStore details](https://www.rustore.ru/help/work-with-rustore-api/api-upload-publication-app/app-version-detail)
- [Huawei authorization](https://developer.huawei.com/consumer/en/doc/AppGallery-connect-Guides/agcapi-getstarted-0000001111845114)
- [Huawei packages](https://developer.huawei.com/consumer/en/doc/appgallery-connect-references/agcapi-obbfile-package-list-0000001111685210)
- [Huawei app state](https://developer.huawei.com/consumer/en/doc/appgallery-connect-references/agcapi-app-info-query-0000001158365045)
- [Huawei upload](https://developer.huawei.com/consumer/en/doc/appgallery-connect-references/agcapi-upload-url-new-0000001111685200)
- [Huawei file association](https://developer.huawei.com/consumer/en/doc/appgallery-connect-references/agcapi-app-file-info-0000001111685202)
- [Huawei submission](https://developer.huawei.com/consumer/en/doc/appgallery-connect-references/agcapi-app-submit-0000001158245061)

Huawei reference content was read from its public documentPortal/getDocumentById
endpoint because the HTML pages return a JavaScript shell to non-browser clients.
Keep official parameter tables as authoritative where examples contradict them
(notably appId casing and files array).

## Recovery and evidence

Persist intent BEFORE upload/submission, then receipt and observed state.
Retry safe reads with bounds; do not automatically repeat non-idempotent requests
on timeout/5xx. If intent exists, read back first. Same code without a matching
hash/receipt is CONFLICT/UNKNOWN, never a fresh upload. An accepted identical package
is reused. Missing evidence, expired assets, unknown wire types or new statuses
stop the affected store. Submitted/reviewing/published packages are never replaced.

RuStore details do not document an APK SHA-256 field. Use the upload receipt and
package identity bound to prior intent; ambiguous acceptance without a receipt
requires store-side byte verification or owner recovery, never an invented hash.
Huawei shaCode is optional: absence prevents proving package identity.
No failure response or API body may echo credentials or signed upload URLs.

Each report records independent raw/normalized store state, version/package IDs,
source and tooling SHA, version, app certificate fingerprint, approved APK digest,
timestamps and GitHub run URL. Terminal success requires both stores published
at full rollout for this exact APK; record results in the version ledger.
GitHub Actions green/upload complete is not this criterion.

## One-time owner setup

1. `android-stores` Environment: selected branch `main`, no required reviewer
   (the single release approval is separate). Secrets:
   `RUSTORE_PRIVATE_KEY_BASE64` (the console's base64 PKCS8 RSA key),
   `APPGALLERY_SERVICE_ACCOUNT_JSON` (entire downloaded private.json).
   Variables: `RUSTORE_KEY_ID`, `APPGALLERY_APP_ID` (existing Android app ID).
   RuStore app-scoped key needs publication permissions. Huawei Service Account:
   Developer-level, App administrator, enabled, authorized for this app.
2. `android-public-release` Environment: selected branch `main`, required reviewer
   owner; admin bypass disabled. No secrets. This is the only approval for the concrete signed APK.
3. Existing `android-signing`: keep release key/password/alias/fingerprint.
   Existing `notifications`: keep TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID. Neither
   needs another per-release review. No secrets are read into chat or local files.
4. Enable scheduled reconciliation after the workflow PR is owner-merged. Review
   existing country/region availability and absence of another pending store edit.
5. Owner merges the separate version PR and implementation PR; then approves one
   concrete release after its hash is available. Never change signing service/key,
   package name or approved bytes to work around an API refusal.

Implementation is not yet publication. iOS and existing OTA automation remain out
of scope; their workflow definitions and behavior are retained.
