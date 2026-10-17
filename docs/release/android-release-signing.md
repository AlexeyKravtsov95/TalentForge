# Android release signing — owner guide (M5.RC.2b)

| | |
|---|---|
| Applies to | The one signed universal APK uploaded **byte-identical** to RuStore and Huawei AppGallery |
| Strategy | [multi-store-signing.md](appgallery/multi-store-signing.md): one owner-held RSA 4096 key, no Huawei App Signing, no RuStore AAB signing, no Google Play App Signing |
| Implemented by | `mobile/composeApp/build.gradle.kts` (signing settings, `checkReleaseSigning`, `assemblePublishableRelease`), `scripts/verify_release_apk.py`, `scripts/release_signing_selftest.sh` |
| Status | Engineering is done. **No production key exists yet**. Every step below marked 🔑 is an owner action; none was run with a real key. |

Steps marked 🔑 are done only by the owner, on the owner's machine. Nothing in this guide
uploads anything.

## 1. Create the key (once) 🔑

Run in a directory **outside every git checkout** (the build refuses a keystore inside the
repository). keytool prompts for the password; do not type it on the command line.

```bash
keytool -genkeypair -v -storetype PKCS12 \
  -keystore talentforge-release.p12 -alias talentforge \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=<owner name>, O=TalentForge, C=<country code>"
```

- The DN is public: anyone can read it from the APK. No e-mail or street address. Do not use
  `;` in it (X.500 treats it as a separator).
- PKCS12 uses one password for the store and the key: use the same value for both settings below.
- Generate the password in a password manager.

Record the certificate fingerprint (public, not secret) in your release record:

```bash
keytool -list -v -keystore talentforge-release.p12 -alias talentforge | grep 'SHA256:'
```

## 2. Back it up (before the first upload) 🔑

Follow [multi-store-signing §5.2–5.3](appgallery/multi-store-signing.md#52-offline-backup):
two encrypted offline copies in different places, the password in the password manager plus a
sealed paper copy, then a recovery drill from each copy. **Losing the key ends in-place updates in
both stores** ([§10](appgallery/multi-store-signing.md#10-consequences-of-losing-or-leaking-the-signing-key)).

## 3. Configure the build 🔑

The build reads exactly these settings, from the **environment** or from Gradle properties in
`~/.gradle/gradle.properties` (outside the repository). The environment wins.

| Environment variable | Gradle property | Value |
|---|---|---|
| `TALENTFORGE_RELEASE_STORE_FILE` | `talentforge.release.store_file` | absolute path of the `.p12`, outside the checkout |
| `TALENTFORGE_RELEASE_STORE_PASSWORD` | `talentforge.release.store_password` | keystore password |
| `TALENTFORGE_RELEASE_KEY_ALIAS` | `talentforge.release.key_alias` | `talentforge` |
| `TALENTFORGE_RELEASE_KEY_PASSWORD` | `talentforge.release.key_password` | key password (= store password for PKCS12) |
| `TALENTFORGE_RELEASE_CERT_SHA256` | `talentforge.release.cert_sha256` | the fingerprint from §1 (colons and case do not matter); public |

```properties
# ~/.gradle/gradle.properties  — chmod 600, never inside a checkout, never committed
talentforge.release.store_file=/Volumes/<encrypted volume>/talentforge-release.p12
talentforge.release.store_password=<from the password manager>
talentforge.release.key_alias=talentforge
talentforge.release.key_password=<from the password manager>
talentforge.release.cert_sha256=<SHA-256 fingerprint from §1>
```

Do not pass passwords with `-P` on the command line (shell history, process list). A
`talentforge.release.*` key in the repository's `mobile/gradle.properties` fails the build
whatever its value.

What the build does with the settings:

| Settings | `assembleRelease` | `assemblePublishableRelease` |
|---|---|---|
| none | **unsigned** technical APK (`composeApp-release-unsigned.apk`), as before; never uploadable | **fails** before building |
| all four, valid | APK signed with your key | builds, verifies, stages the upload file |
| some missing; keystore missing; keystore inside the checkout; the SDK debug keystore or the `androiddebugkey` alias | **fails**, naming the missing or wrong settings, never printing a value | **fails** |
| all four valid, fingerprint missing or malformed | signed APK | **fails** before building |

The release build type never uses the debug key. There is no fallback.

## 4. Build the upload file 🔑

From a clean checkout of the tagged release commit:

```bash
cd mobile
./gradlew :composeApp:assemblePublishableRelease
```

On success, `composeApp/build/outputs/publishable/` holds exactly:

- `TalentForge-<versionName>-<versionCode>.apk`: **the only file to upload, to both stores**;
- `TalentForge-<versionName>-<versionCode>.apk.report.json`: its verification report.

The task runs `scripts/verify_release_apk.py` on the staged file and keeps it only when every
check passes:

- `apksigner verify` succeeds with exactly one signer;
- the certificate is not the Android debug certificate (checked by DN and against your local
  debug keystore);
- the certificate SHA-256 equals your fingerprint;
- the key is RSA ≥ 4096;
- `applicationId` is `io.github.alexeykravtsov95.talentforge`;
- `versionCode`/`versionName` equal the build's;
- the APK is neither debuggable nor test-only;
- it is a universal (non-split) APK that includes `arm64-v8a`.

On any failure the directory is emptied and the build fails. The report contains public facts
only: file name, size, **APK SHA-256**, package metadata, signer DN and certificate digest. It
never contains a password, a keystore path or an environment value.

## 5. Before and after uploading 🔑

1. Re-check the exact file right before each upload (the same checks, standalone):

   ```bash
   python3 scripts/verify_release_apk.py --apk TalentForge-<v>-<code>.apk \
     --expected-cert-sha256 <fingerprint> --report /tmp/report.json
   shasum -a 256 TalentForge-<v>-<code>.apk   # equals "apk.sha256" in the report
   ```

2. Upload **the same file** to RuStore and AppGallery. Record the APK SHA-256 in the release
   record. It proves both stores received the identical file.
3. Do **not** enable AGC App Signing for the APK, and do not upload a key to RuStore.
4. After publication, check that each store delivered your certificate
   ([multi-store-signing §8](appgallery/multi-store-signing.md#8-certificate-sha-256-verification), step 3).

## 6. Self-test without the production key

`scripts/release_signing_selftest.sh` checks the whole mechanism with a **temporary** RSA 4096
test key. It covers:

- the unsigned build;
- missing and partial settings;
- the debug keystore and a keystore inside the checkout;
- a signed build;
- the publishable task with no, wrong and correct fingerprints;
- the debug APK;
- a secret-leak scan of the logs, reports and `build/`.

The test key lives in a fresh temporary directory with a random password. On exit it is deleted
together with every test-signed output. The self-test runs in `mobile-ci`, and it refuses to run
on a machine where `talentforge.release.*` is set in the Gradle user properties. **A test-key APK
is never a release artifact.**

## 7. Never

- Commit a keystore, a password or a properties file containing one (`*.p12`, `*.jks`,
  `*.keystore` and `*.pk8` are git-ignored as a backstop).
- Put signing secrets into CI. CI is infrastructure-blocked (TF-RC1-CI), and production signing
  in CI is not planned in this phase.
- Upload `composeApp-release-unsigned.apk`, `composeApp-debug.apk`, or anything not produced by
  `assemblePublishableRelease` from the release commit.
- Use a store-generated key (AGC-generated or Google-generated): it breaks cross-store updates
  ([multi-store-signing §2.3](appgallery/multi-store-signing.md#23-what-would-change-if-managed-signing-were-enabled)).
