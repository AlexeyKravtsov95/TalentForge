# M7.CI.HOTFIX.1 — simulator smoke isolation

## Evidence and limits

Source: [Mobile CI run 44098962915](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/44098962915),
SHA `949fd8bfc0c93e8a1ec72b9ac39d9653b0103450`, attempts 1 and 2.
Both attempts used `xcode-27` / Xcode 27.1 build `27A9275`. Android checks, iOS compilation/tests,
framework linking, unsigned Release archives and archive validation passed. OTA E2E passed in
attempt 1; attempt 2 reran only the cancelled macOS job.

| Attempt | Smoke start (UTC, 2026-11-17) | iPhone Pro PASS | Pro Max PASS | Cancellation |
| --- | --- | --- | --- | --- |
| 1 | 14:33:32 | 14:40:22 | 14:47:10 | 14:57:53 |
| 2 | 16:51:21 | 16:58:11 | 17:05:32 | 17:17:59 |

The logs contain no iPad stage boundary or error before cancellation. They establish long
sequential execution and missing observability, **not** the particular iPad command that stalled,
a CoreSimulator root cause, an app hang, or who/what requested cancellation. The script discarded
bootstatus output, allowed unbounded simctl calls (including cleanup/diagnostics), and emitted only
final PASS/FAIL. Both successful phone logs selected iOS 27.2 from the installed runtimes. This
hotfix preserves that device/runtime selection policy and the existing Xcode/SDK/version/signing/OTA
settings; it does not claim that changing runtime versions fixes the problem.

## Execution and evidence

The existing macOS build/test/archive job packages its simulator `.app` as a tar artifact to
preserve modes and framework symlinks. Three mandatory smoke matrix jobs consume that artifact
from the **same workflow run**. Each uses a fresh runner and one device: iPhone 18 Pro, Pro Max,
iPad Pro 13-inch (M5). `fail-fast: false` keeps sibling jobs running after a failure.

Every simulator operation uses the Python standard-library watchdog, including discovery,
process queries, diagnostics, termination and shutdown. `bootstatus -b` also boots a shutdown
device. Defaults in seconds:

| Stage | Deadline |
| --- | ---: |
| Discovery / process query (up to 3 queries) | 30 each |
| Boot readiness | 600 |
| Install | 300 |
| Launch | 120 |
| Settle | 30 (normally sleeps 15) |
| Screenshot | 90 |
| Diagnostic calls | 10–20 each |
| Terminate / shutdown | 20 each |

START/WAIT/END messages identify the stage and elapsed time, with a heartbeat every ten seconds.
A timeout returns 124, terminates the child process group, escalates to KILL after a bounded grace,
and retains partial stdout/stderr. Nonzero commands fail smoke. PID checks preserve the existing
bounded retry for query errors; a missing/dead app is never retried into a pass. Screenshot evidence
must be newly produced and nonempty. There is no automatic device-smoke retry that could mask a crash.

Each device retains `timings.jsonl`, stage stdout/stderr, device/runtime/UDID facts, screenshot,
app logs, installed apps, device/runtime lists, host process/disk snapshots, CoreSimulator logs and
new app crash reports. Diagnostic commands also have deadlines, so an unavailable simulator cannot
block evidence collection indefinitely. Per-device upload uses `always()`. Smoke has a 27-minute
step limit within a 35-minute job, leaving setup/upload headroom; build/archive has a separate
60-minute job limit. Successful build results and sibling smoke artifacts survive a failing iPad job.

## Store delivery gate

Full `CI` must succeed for the exact source SHA on `main` in the requested repository, from
`push` or `workflow_dispatch`. Android-only delivery additionally requires both exact Android job
names from Mobile CI to be completed/successful, bound to that SHA and the trusted run ID/current
attempt. A completed Mobile CI run may have failed/cancelled iOS jobs. Repository identity,
workflow path, branch and event remain checked; missing, skipped, duplicate, wrong-attempt,
foreign-SHA/repository or malformed job evidence fails closed. Job pagination cannot truncate silently.
The Google Play production command applies the same Android scope at its final CI recheck.

iOS-only and combined delivery continue to require the **entire** Mobile CI workflow to succeed.
No store publication is part of hotfix validation. PR CI is review evidence only: the delivery gate
continues to reject PR/feature-branch checks, even when their SHA is exact. After merging, rerun CI
on the resulting `main` SHA before delivery. GitHub can retain successful same-SHA jobs
as current-attempt records after a failed-only rerun; those API-attested records are valid
evidence. A partial rerun that omits either required job or its current-attempt binding fails
closed. Use **re-run all jobs** if the required records are absent; old-attempt records alone
never satisfy the gate.

## Validation

Offline tests cover real subprocess success/error/timeout/cancellation, descendant cleanup,
partial diagnostics, all smoke stages, stale screenshot/output rejection and release-gate trust
failures. The PR's CI and Mobile CI runs are the authoritative validation of the final change,
including the three real simulators, unchanged Android tests and iOS archive/OTA checks.


## First instrumented run

[Run 44140579246](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/44140579246)
at `695a61433ce06ddbe207011a364bae0aac604298` retained the build/archive results and the
successful sibling smoke jobs when Pro Max failed. iPad passed in approximately 5:37:
boot 84.8s, install 114.6s, launch 56.3s. iPhone Pro passed with install 169.2s. Pro Max
completed boot in 91.5s, then hit the initial 180s install deadline. Its bounded diagnostic
calls also timed out (including host `ps`); partial outputs and timings were uploaded in
`ios-smoke-iphone-pro-max`. Host disk output showed 33GiB free. These observations identify
the new failure stage and poor host responsiveness, not the original cancellation cause
or a proven underlying OS/app defect. All three selected iOS 27.2, as before the hotfix.

The final install deadline is 300s to leave useful headroom above the observed successful
169s install, with a 27-minute smoke step inside a 35-minute job. Every required device
still must install, launch, remain running and produce a new screenshot; a deadline breach
remains a failure. There is no retry of app launch/crash checks and no runtime change.


## Session-loss investigation

At `e9f6634433f3b807ee24e6e736b8b1d3cea02bd3`, CI, build/archive, Android, OTA E2E and iPad
passed. Both phones installed and launched successfully, then lost their simulator sessions:
Pro Max process queries reported SimError 405 / bad or unknown session; Pro's screenshot
reported POSIX 60 / timeout waiting for screen surfaces, followed by an unbooted device.
No app crash report was present, but simulator log queries failed, so this is not proof that
app crashes are impossible. The underlying cause remains unproven.

Upstream [15334](https://github.com/actions/runner-images/issues/15334) and
[15337](https://github.com/actions/runner-images/issues/15337) describe runtime unmount/session
loss on image 20261112.0361.1. Our failed phone runners already used 20261104.0337.1, and their
initial unified-log capture contained only a header. The upstream rollback therefore cannot
be claimed as the fix for these failures. A single fresh-runner rerun of the failed phone
jobs was used to investigate transient infrastructure while preserving all checks and artifacts.
Both passed on fresh runners with the same SHA (Pro install 145.2s, Pro Max install 92.5s),
and run 44149049152 completed successfully on attempt 2 with all seven jobs. The original
failed diagnostics and the retry screenshots remain available. This establishes intermittent
session loss; it does not identify its underlying trigger or prove a specific upstream bug.

Smoke now records read-only `simctl runtime list -j` inventory before boot and on failure,
plus the host `CoreSimulator.log` and bounded unified logs for `simdiskimaged`, `cryptexd`
and `SimLaunchHost`. These can distinguish future runtime-registration/session-loss failures
without changing runtime versions, registration, mounts or app behavior.
