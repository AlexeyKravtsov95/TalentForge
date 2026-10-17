# M5.GALLERY.1 — Multi-store signing strategy (RuStore + Huawei AppGallery)

| | |
|---|---|
| Milestone | M5.GALLERY.1 — AppGallery release preparation (strategy) · M5.RC.2b — implementation |
| Tracking | GitHub issue #36 · Google Play deferred to backlog issue #35 |
| Base | `main` @ `f7494e1` (merge of PR #34, M5.RC.2a) |
| Sources checked | 2026-10-17, official store documentation listed in [§12](#12-sources) |
| Related | [readiness](readiness.md) · [testing and publication](testing-and-publication.md) · [rc1-defects TF-RC1-SIGNING](../rc1-defects.md#tf-rc1-signing--android-release-artifact-is-unsigned-no-release-signing-configuration) |

> **Status (M5.RC.2b): signing implemented, no production key yet.** M5.GALLERY.1 recorded the
> strategy. M5.RC.2b implemented the signing configuration (§6), the fail-closed publishable path and
> the APK validator (§7, [owner guide](../android-release-signing.md)), verified with a
> temporary test key only. No production key has been generated, nothing was signed for release,
> nothing was uploaded. TF-RC1-SIGNING stays **OPEN** until the owner's key signs an APK that passes
> `assemblePublishableRelease`.

## 1. Recommendation

TalentForge ships as **one Android product** in both stores:

| Property | Decision |
|---|---|
| `applicationId` | `io.github.alexeykravtsov95.talentforge`, unchanged, the same in both stores. No second id, no `applicationIdSuffix` for release. |
| Signing key | **One owner-controlled production key** (RSA 4096), created and held only by the owner, never committed, never given to a store or CI in this phase. |
| Artifact | **One signed universal APK per release**, built once and uploaded **byte-identical** to RuStore and AppGallery. |
| Versions | The same `versionCode` / `versionName` for that APK in both stores. `versionCode` only increases. |
| Flavors | **None.** No store-specific product flavors, resources or code: there is no demonstrated need (no store SDK, no store billing, no store-specific behaviour). |
| Managed signing | **Not enabled**: no Huawei App Signing, no RuStore AAB key upload, no Google Play App Signing. Any of these needs an explicit owner decision first (see §2.3). |

**Does this satisfy both stores today?** Yes, on the evidence of the current official documentation.
Nothing in either store's rules forces store-managed signing for an APK:

| Requirement | RuStore | AppGallery (Android APK) | Plan meets it |
|---|---|---|---|
| Package format | APK or AAB accepted | APK, AAB (also RPK/APP for other platforms) | ✅ APK |
| Who signs an APK | "Каждый APK-файл должен быть подписан цифровым сертификатом" (every APK must be signed with a certificate) | App Signing "supports only APK and AAB files. You can choose not to use the service if your app is released in an APK package" | ✅ developer-signed |
| Store-managed signing | Required only for **AAB** (upload the app-signing key via PEPK + an upload certificate) | Required only for **AAB** ("If you upload an AAB package … App Signing is required") | ✅ not triggered by APK |
| Same key across stores | Recommended: "использовать один сертификат на все версии приложения" (one certificate for all versions); different keys block updates of installs from another store | Update packages must carry the same signature as the installed app | ✅ one key |
| Debug certificate | Listed as a malware sign that can get a version rejected ("Подпись отладочным сертификатом") | — | ✅ release key only (§7) |
| Key type | RSA ≥ 2048 bits (stated for the AAB app-signing key) | Not stated for APK | ✅ RSA 4096 |
| 64-bit | — | "When you distribute an APK to mobile phones, ensure that the uploaded package is a 64-bit one" | ✅ `arm64-v8a` packaged (see [readiness §4](readiness.md#4-huawei-compatibility-audit)) |
| Package name | Unique in the store | Must match the package name set in AGC; "not used by a released app" | ✅ same id in both |

**Risks that remain** (owner-visible, not solved by this document):

1. The key is a single point of failure. Losing it ends in-place updates in **both** stores (§10).
   The backup procedure in §5 is the only mitigation.
2. A universal APK carries all four ABIs; per-device downloads are about 0.8 MB larger than AAB splits (§3).
3. Google Play (deferred, #35) does not accept APKs for new apps. Compatibility is preserved only if
   the owner later gives Play **this same key** (§4).
4. Store behaviour for an install that came from the *other* store (does AppGallery offer an update
   to a RuStore install and vice versa?) is **not documented** by either store and is UNVERIFIED (§9).

## 2. Store signing models in detail

### 2.1 RuStore

From [Работа с подписями APK и AAB](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication/apk-signature)
and [Загрузка приложений в формате AAB](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication/new-version-app/upload-aab):

- APK: the developer signs; RuStore distributes that APK.
- AAB: the developer signs the AAB with an **upload key**; RuStore verifies the upload certificate,
  generates APKs and **signs them with the app-signing key that the developer uploads** as a
  PEPK-encrypted ZIP (plus the upload certificate as PEM). Requirements stated there: RSA ≥ 2048,
  Java 11+. When moving an APK app to AAB, the ZIP must contain the signature used for the APK.
- Changing the key: via support (`support@rustore.ru`); after the change "все версии приложений
  уйдут в архив" (all versions are archived) and every APK/AAB must be uploaded again with the new key.
- RuStore recommends a locally stored certificate "для большего контроля над выпусками" and warns
  that a Google-generated Play App Signing key may be unusable outside Google Play.
- The [new-version page](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication/new-version-app)
  warns that if the signing keys do not match, users cannot update an app installed through another store.

### 2.2 Huawei AppGallery (Android)

From [App Signing — Service Introduction](https://developer.huawei.com/consumer/en/doc/AppGallery-connect-Guides/agc-appsigning-introduction-0000001051379577)
and [Releasing an App (App Bundle)](https://developer.huawei.com/consumer/en/doc/app/agc-help-releasebundle-0000001100316672):

- APK: App Signing is optional. Without it, the developer is responsible for key security.
- AAB: App Signing is mandatory. AGC compiles the uploaded AAB into APKs and signs them with the
  key managed by App Signing; it re-signs every upload.
- App Signing has two modes: AGC **generates** the signing key (new apps only), or the developer
  **uploads** their own key (new and released apps). For an app already released in full, the
  uploaded key must be the one used for the last full release.
- "Once a signature key is generated in AppGallery Connect or uploaded by you, it cannot be modified."
- Without App Signing, a lost or stolen key means the developer "will have to use a new package name
  to release the updated app".

### 2.3 What would change if managed signing were enabled

| Option | Effect on the one-key plan | Status |
|---|---|---|
| AGC App Signing with an **AGC-generated** key | AppGallery installs get a different certificate → no cross-store updates with RuStore; irreversible | **Do not use** |
| AGC App Signing with the **owner's key uploaded** | Same certificate; the private key now also lives in Huawei's encryptor; irreversible | Only with explicit owner approval |
| RuStore AAB (owner's key uploaded via PEPK) | Same certificate; the private key now also lives at RuStore | Only with explicit owner approval |
| Google Play App Signing, Google-generated key | Play installs get a different certificate → incompatible with both stores | **Do not use** (#35) |

## 3. APK vs AAB for the first public release

| Criterion | Signed universal APK | AAB |
|---|---|---|
| Control of the signing identity | The private key never leaves the owner. Both stores distribute the owner-signed file. | Both stores require the **private app-signing key** (RuStore: PEPK upload; AppGallery: App Signing, generated or uploaded). Custody is shared with two stores. |
| Cross-store update compatibility | Guaranteed by construction: the same file, the same certificate. | Only if both stores hold the same uploaded key. One wrong choice (AGC-generated key) breaks it permanently. |
| Binary size | Universal APK file: 17 148 789 bytes (unsigned release, `f7494e1`). Most bytes are the bundled talent datasets (`assets/composeResources`, 9.26 MB compressed) and dex (6.82 MB); native libraries are only 37 KB. | bundletool estimates a per-device download of 16.28–16.33 MB for every ABI, density and language: a saving of only about 0.8 MB (≈ 5 %), because ABI/density splits cannot shrink the dataset payload ([readiness §7](readiness.md#7-validation-results)). |
| Huawei signing requirement | App Signing optional | App Signing **mandatory** |
| RuStore signing requirement | Signed by the developer | Key upload **mandatory** |
| Testing what users receive | The exact uploaded file can be installed and smoke-tested on a device before upload. | Users receive store-generated split APKs that the developer never runs. bundletool can approximate them locally, but not byte-for-byte. AppGallery also requires `fusing` for the universal APK and rejects Play Asset Delivery. |
| Future Google Play (#35) | Play needs an AAB for a new app; the same key can be provided to Play (§4). | Same, once the key decision is made. |

**Recommendation: signed universal APK** for the first public release in both stores.

**Risks of the recommendation:** slightly larger downloads (≈ 0.8 MB, see above); four ABIs including `x86`/`x86_64`, which phones
rarely need (ABI filtering is a TF-RC1-ANDROID-CONFIG decision, not made here); a second
packaging path (AAB) will be needed for Google Play later. Moving a store from APK to AAB later is
possible **only** by uploading the same key (RuStore states this for AAB migration; AGC accepts an
uploaded key for released apps).

## 4. Preserving compatibility with Google Play App Signing (deferred, issue #35)

Nothing is implemented for Google Play in this milestone. To keep the option open:

1. **Never** let Play generate the app-signing key. Google's [Play App Signing help](https://support.google.com/googleplay/android-developer/answer/9842756)
   states that new apps are enrolled with Google-generated keys by default, that developers who
   want to manage their own key can change this default ("Provide a copy of your app signing key"),
   and that this must happen **before** a release is rolled out on the open-testing or production track.
2. Provide **the same RSA key** used for RuStore/AppGallery. Play then signs its APKs with the same
   certificate, so installs from all three stores can update each other.
3. A **separate upload key** for Play is fine; it does not change the app-signing certificate.
4. Keep `applicationId` and the `versionCode` sequence shared across all stores.

If #35 ever proceeds with a Google-generated key, Play installs will not be able to update from
RuStore/AppGallery or vice versa, and RuStore's own guidance warns that such a key may not be usable
outside Play.

## 5. Keystore: creation, offline backup, recovery (owner procedure — not executed here)

### 5.1 Create (once, by the owner)

```bash
# PKCS#12 keystore, RSA 4096, ~100-year validity. Run in a directory OUTSIDE every git checkout.
keytool -genkeypair -v \
  -storetype PKCS12 \
  -keystore talentforge-release.p12 \
  -alias talentforge \
  -keyalg RSA -keysize 4096 \
  -validity 36500 \
  -dname "CN=<owner name>, O=TalentForge, C=<country code>"
```

- Choose the DN deliberately: anyone who inspects the APK can read it. Do not put an e-mail
  address or a street address in it.
- Use strong, distinct store and key passwords generated in a password manager.
- Record the certificate fingerprints immediately (they are public, not secret):

```bash
keytool -list -v -keystore talentforge-release.p12 -alias talentforge | grep -E 'SHA256|SHA1|Owner|Valid'
```

### 5.2 Offline backup

| Copy | Medium | Location |
|---|---|---|
| Primary | the keystore on the owner's release machine | encrypted disk, outside any repository or cloud-synced folder |
| Backup A | encrypted USB drive (encrypted APFS or VeraCrypt volume) | home safe or locked drawer |
| Backup B | a second encrypted medium | a different physical location from A |
| Passwords | password manager **and** a sealed paper copy | the paper copy is not kept with backup A |
| Fingerprints | SHA-256 / SHA-1, alias, DN, creation date | owner's release record (not secret) |

Rules:
- Never commit a keystore, a password or a `*.properties` file containing them. Check `git status`
  before every commit; `*.p12`, `*.jks` and `*.keystore` must never be staged.
- Do not keep the keystore in iCloud/Dropbox/Google Drive unless it is inside an encrypted container.
- Do not type passwords on the command line (shell history); use the properties file in §6 or a prompt.

### 5.3 Recovery drill (before the first upload, then yearly)

1. On a second machine, restore the keystore from backup A only.
2. `keytool -list -v`: alias, DN and SHA-256 must equal the recorded values.
3. Sign a throwaway test APK with it, run `apksigner verify --print-certs`, compare the SHA-256
   digest, then delete the throwaway APK. Never upload it.
4. Repeat with backup B. Record the date of the drill in the owner's release record.

## 6. Signing through local configuration or environment variables (implemented in M5.RC.2b)

Implemented in `mobile/composeApp/build.gradle.kts`; the owner procedure is the
[Android release signing guide](../android-release-signing.md). In short:

- The four settings (`TALENTFORGE_RELEASE_STORE_FILE`, `_STORE_PASSWORD`, `_KEY_ALIAS`,
  `_KEY_PASSWORD`) come from the environment or from `~/.gradle/gradle.properties`
  (`talentforge.release.*`); the environment wins. A `talentforge.release.*` key in the repository's
  `gradle.properties` fails the build.
- **None set:** `assembleRelease` builds the unsigned technical APK, as before.
- **All four set and valid:** the release APK is signed with that key.
- **Anything else** (partial settings, missing keystore, keystore inside the checkout, the SDK debug
  keystore or the `androiddebugkey` alias): `checkReleaseSigning` fails every release build. The
  message names the settings and never prints a value.
- The upload artifact comes only from `:composeApp:assemblePublishableRelease`. That task also
  requires the expected certificate fingerprint (`TALENTFORGE_RELEASE_CERT_SHA256`) and stages the
  APK only after `scripts/verify_release_apk.py` passes (§7).
- The proposed "missing credentials → unsigned" behaviour is kept for `assembleRelease` only. The
  publishable path never produces an unsigned or debug-signed artifact.

CI holds no production secret. `mobile-ci` runs `scripts/release_signing_selftest.sh` with a
temporary test key that is deleted on exit (TF-RC1-CI still blocks CI runs).

## 7. Preventing accidental debug-key signing

All guards below are implemented (M5.RC.2b) and run automatically in `assemblePublishableRelease`
through `scripts/verify_release_apk.py`; the owner can also run the validator standalone on the file
to upload.

| Guard | How (implemented) |
|---|---|
| No debug fallback | The `release` build type sets `signingConfig = signingConfigs.findByName("release")`: the owner's key or none, never `signingConfigs.debug`. The SDK debug keystore and the `androiddebugkey` alias are refused as release settings. |
| No unsigned upload | `assemblePublishableRelease` fails before building without the complete signing settings and the expected fingerprint; it stages nothing unless every check passes. |
| Never upload `*-debug.apk` | Upload only `build/outputs/publishable/TalentForge-<versionName>-<versionCode>.apk`. The validator rejects the debug APK (debug DN, debuggable). |
| Certificate check | `apksigner verify --verbose --print-certs`: verifies, exactly one signer, the DN is **not** the Android debug subject, the certificate differs from the local debug keystore's, and its SHA-256 equals the owner's recorded fingerprint (§5.1). Key: RSA ≥ 4096. |
| Package identity | `aapt2 dump badging`: `applicationId` is the frozen `io.github.alexeykravtsov95.talentforge`; `versionCode`/`versionName` equal the build's; no `application-debuggable`; not test-only. RuStore lists `android:debuggable="true"` and a debug certificate among malware signs. |
| Universal APK | Not a split (`split=` absent), the `native-code` list equals the ABIs packaged under `lib/`, and `arm64-v8a` is present. |
| Identity across stores | The JSON report records the APK's SHA-256; the same digest must be recorded for the RuStore and the AppGallery upload. |
| Signature schemes | Recorded in the report. With `minSdk 26` the release APK is signed with v2 only (no v1 needed); the validator requires v2 or v3. |

`run-gallery1-checks.sh` already showed the difference: the debug APK verifies with the Android
debug certificate and the unsigned release APK does not verify ([readiness §7](readiness.md#7-validation-results)).
M5.RC.2b's self-test shows every guard working with a temporary key
([rc2b-evidence](../rc2b-evidence/README.md#signing-self-test)).

## 8. Certificate SHA-256 verification

Three places must show the **same** SHA-256 digest:

```bash
# 1. the key (owner machine)
keytool -list -v -keystore talentforge-release.p12 -alias talentforge | grep 'SHA256:'
# 2. the artifact to upload
apksigner verify --print-certs --verbose TalentForge-<versionName>-<versionCode>.apk | grep 'SHA-256 digest'
# 3. what a store actually delivered (after publication, on a test device)
adb shell pm path io.github.alexeykravtsov95.talentforge          # -> package:/data/app/…/base.apk
adb pull <that path> delivered.apk
apksigner verify --print-certs delivered.apk | grep 'SHA-256 digest'
```

`keytool` prints colon-separated upper-case hex and `apksigner` prints lower-case hex without
colons; normalise before comparing. Record (1) once, (2) for every release, and (3) once per store
after the first publication (and again if either console ever mentions re-signing).

## 9. Cross-store installation and update scenarios

Android installs an update only if the package name matches, the signing certificate matches and
`versionCode` is not lower. Saved builds live in the app's private storage, so **any uninstall
deletes the user's saved builds**: there is no account or cloud copy, and the only user-side escape
hatch is sharing each build as a code before uninstalling.

| # | Scenario | Same key (this plan) | Different keys |
|---|---|---|---|
| 1 | Installed from RuStore → update from RuStore | ✅ updates | n/a |
| 2 | Installed from AppGallery → update from AppGallery | ✅ updates | n/a |
| 3 | Installed from RuStore → user installs the AppGallery APK (same or higher `versionCode`) | ✅ Android accepts it as an update; builds kept | ❌ `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; only uninstall + reinstall, **builds lost** |
| 4 | Installed from AppGallery → RuStore APK | ✅ as 3 | ❌ as 3 |
| 5 | A store publishes a **lower** `versionCode` than the installed one | ❌ downgrade refused | ❌ |
| 6 | Same `versionCode`, different contents in the two stores | ⚠️ installs, but bug reports can no longer be mapped to a build; forbidden by this plan | ❌ |
| 7 | Sideloaded debug build installed, store build offered | ❌ debug certificate ≠ release certificate | — |
| 8 | Future Google Play with the same key provided (§4) | ✅ | ❌ with a Google-generated key |

**UNVERIFIED (store behaviour, not Android behaviour):** whether the AppGallery client offers
updates for an app it did not install, and whether RuStore does. Neither store documents it. On
Android 14+, "update ownership" applies only if the installing store requests it. Verify on a device
during M5.RC.3 ([testing-and-publication §5](testing-and-publication.md#5-device-test-plan)).

Version policy: one `versionCode` per built APK, strictly increasing and never reused; upload the
same file to both stores; publish to both in the same release window. Open-testing builds consume
their own numbers; the production APK is a new build with a higher `versionCode`
([testing-and-publication §6.1](testing-and-publication.md#61-open-testing--formal-production-release)). RuStore may delist an app whose RuStore version lags the version
the developer distributes through other stores ([moderation rules §8.5](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/requirement-apps)).

## 10. Consequences of losing (or leaking) the signing key

**Lost key, no store-managed signing (this plan):**
- No further update can be installed over any existing install in **any** store.
- RuStore: a key change through support archives all versions; every version must be uploaded
  again with the new key; existing users still cannot update in place.
- AppGallery: Huawei's documentation says the update has to be released under a **new package
  name**, which breaks the one-product identity and splits ratings and reviews.
- Users must uninstall and reinstall, which **deletes their saved builds** unless they shared each
  build as a code first.

**Leaked key:** anyone holding it can sign an APK that Android accepts as a TalentForge update
outside the stores (sideloading). Response: stop releasing, contact both stores' support, decide on a
key change (with the consequences above) and inform users. APK Signature Scheme v3 key rotation
exists at the platform level, but neither store documents support for rotated signing lineages;
treat it as UNVERIFIED and do not rely on it.

The backup procedure in §5 is the only practical protection.

## 11. Owner actions

| # | Action | Needs |
|---|---|---|
| S-1 | Approve this strategy (APK, one key, no managed signing), or record a different decision | owner |
| S-2 | Create the release keystore (§5.1) outside the repository; record the fingerprints | owner |
| S-3 | Make backups A and B and run the recovery drill (§5.2, §5.3) | owner |
| S-4 | M5.RC.2b: review and merge the signing configuration (§6, implemented); TF-RC1-SIGNING closes once the owner's key signs an APK that passes `assemblePublishableRelease` | owner + engineering |
| S-4a | Configure the signing settings and the expected fingerprint on the release machine ([owner guide §3](../android-release-signing.md#3-configure-the-build-)) | owner |
| S-5 | Decide the first public `versionCode` / `versionName` (existing checklist gate) | owner |
| S-6 | In both consoles: do not enable AGC App Signing for the APK and do not upload a key to RuStore | owner's store accounts |
| S-7 | For #35 later: provide the same key to Play before the first open/production release | owner (deferred) |

## 12. Sources

Accessed 2026-10-17. Huawei pages show their own "Last updated" date.

- RuStore — [Работа с подписями APK- и AAB-файлов](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication/apk-signature)
- RuStore — [Загрузка приложений в формате AAB](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication/new-version-app/upload-aab)
- RuStore — [Как загрузить новую версию приложения](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/app-publication/new-version-app)
- RuStore — [Правила модерации RuStore](https://www.rustore.ru/help/developers/publishing-and-verifying-apps/requirement-apps)
- Huawei — [App Signing: Service Introduction](https://developer.huawei.com/consumer/en/doc/AppGallery-connect-Guides/agc-appsigning-introduction-0000001051379577) (last updated 2025-07-16)
- Huawei — [Releasing an App (APK)](https://developer.huawei.com/consumer/en/doc/app/agc-help-releaseapkrpk-0000001106463276) (2026-06-01)
- Huawei — [Releasing an App (App Bundle)](https://developer.huawei.com/consumer/en/doc/app/agc-help-releasebundle-0000001100316672) (2026-06-01)
- Google — [Use Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)
