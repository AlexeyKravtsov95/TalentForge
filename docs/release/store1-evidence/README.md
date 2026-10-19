# M5.STORE.1 — evidence

This is the curated evidence for [store1-release-audit.md](../store1-release-audit.md), taken from `main` @
`b242cd61f1e9ca400e5c652cc6db61c48c7178f5` on 2026-10-19. Local paths are shown as `~`, `<worktree>`,
`<logs>` or `<checkout>`. No production key was involved: both signed APKs were signed with temporary keys,
and those keys were deleted.

| Path | What it is |
|---|---|
| `mac/` | Curated output of `appgallery/evidence/run-gallery1-checks.sh` (drift checks, forced JVM/desktop tests, debug/release/AAB builds, `lintRelease`, dependency report, aapt2/apksigner/bundletool inspection). Every row of `00-summary.tsv` has the full SHA. `15-freshness.txt` is the runner's failed invocation, run with a Python that lacks `talent_data`; `15-freshness-rerun-venv.txt` is the valid re-run |
| `signing-selftest/` | `scripts/release_signing_selftest.sh`: the 14-scenario summary, the deleted test key's certificate digest and the `publish-ok` report |
| `publishable-tempkey/` | `assemblePublishableRelease` with a second temporary key, built for the emulator network check. Contains the validator report, the certificate digest and the generated OTA settings compiled into that APK |
| `emulator/` | The network-behaviour run on the API 35 `compact` emulator (`-read-only`, rooted). Contains the step log, package flags, iptables/ip6tables per-UID counters, the positive control, the kernel per-UID byte map, a DNS summary of the full capture, the monkey summary and screenshots. The screenshots are test evidence and **not** store material |
| `netcheck.sh`, `publishable-tempkey.sh`, `tap.sh` | The scripts used above. `netcheck.sh` refuses to run if the package is already installed |

Not committed:

- the raw packet capture (it contains unrelated emulator system traffic);
- the APKs (never upload a temporary-key APK);
- the full Gradle logs.
