# M5.OTA.3 — OTA download performance (test-plan §7)

> **M5.OTA.4b:** the measurements over real HTTPS (VK Cloud staging) and the parallelism decision
> (4 → 8) are in [M5.OTA.4b-remote-staging-report.md](M5.OTA.4b-remote-staging-report.md) §7 and
> [ADR-0002](ADR-0002-delivery-performance.md). The tables below remain the M5.OTA.3 loopback benchmark.

Measured 2026-10-18 on the development Mac (Apple Silicon, macOS 26.3, JDK 17) with
`OtaPerformanceBenchmark` (composeApp desktopTest, opt-in):

```bash
./gradlew :composeApp:desktopTest --tests "*OtaPerformanceBenchmark*" -Dtalentforge.ota.benchmark=<report.md>
```

**Setup.** Client = the production JVM stack (`OtaRuntime` with the `HttpURLConnection` fetcher, the
`java.nio` store, full §6.3/§6.4 verification, the app's repositories as validator, atomic install and
activation) — the code Android ships. Origin = a local HTTP server that injects the profile's
round-trip time before each response, one shared token bucket for the link bandwidth (all
connections share it, like a phone's single link), and loss as retransmission stalls (each 16 KiB
burst loses a packet with probability 1 − (1 − p)^11 and then stalls max(200 ms, 2 × RTT)).
Release B = `1.60.1.70450-r1`: 397 files, 5 077 048 bytes decoded, manifest 75 792 bytes; the
device bundles only the reference build (nothing of B reusable). *Cold* = empty OTA store; *warm* =
release A installed first (6 identical files reusable by SHA-256); *resume* = the first attempt
interrupted (app sent to the background) at 50 % of the files, measured: the second attempt.
Time = the whole check (catalog GET, verification, downloads, verification and validation of the
snapshot, install, activation). Median of 5 runs (p90 reported); the poor profile uses 3 runs
except the threshold case (cold, parallelism 4: 5 runs). "gzip bytes" is what the same requests
would transfer if the origin served `Content-Encoding: gzip` (computed per blob; the local origin
sends identity). "transfer-only" = decoded bytes / bandwidth; "overhead" = 1 − transfer-only / median.

| profile | case | parallelism | runs | median s | p90 s | requests | decoded bytes | gzip bytes (if served) | files reused | files downloaded | transfer-only s | overhead |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| good (20 ms, 20 Mbit/s, 0.0 % loss) | COLD | 1 | 5 | 15.2 | 15.7 | 399 | 5154278 | 2263420 | 0 | 397 | 2.1 | 86 % |
| good (20 ms, 20 Mbit/s, 0.0 % loss) | COLD | 4 | 5 | 4.9 | 4.9 | 399 | 5154278 | 2263420 | 0 | 397 | 2.1 | 58 % |
| good (20 ms, 20 Mbit/s, 0.0 % loss) | COLD | 8 | 5 | 3.4 | 3.6 | 399 | 5154278 | 2263420 | 0 | 397 | 2.1 | 39 % |
| good (20 ms, 20 Mbit/s, 0.0 % loss) | WARM | 4 | 5 | 4.8 | 4.9 | 393 | 5119966 | 2228973 | 6 | 391 | 2.0 | 58 % |
| good (20 ms, 20 Mbit/s, 0.0 % loss) | RESUME | 4 | 5 | 2.2 | 2.5 | 202 | 1836861 | 1110902 | 196 | 201 | 0.7 | 66 % |
| mobile (100 ms, 5 Mbit/s, 0.0 % loss) | COLD | 1 | 5 | 57.3 | 57.3 | 399 | 5154278 | 2263420 | 0 | 397 | 8.2 | 86 % |
| mobile (100 ms, 5 Mbit/s, 0.0 % loss) | COLD | 4 | 5 | 17.2 | 17.2 | 399 | 5154278 | 2263420 | 0 | 397 | 8.2 | 52 % |
| mobile (100 ms, 5 Mbit/s, 0.0 % loss) | COLD | 8 | 5 | 10.6 | 10.8 | 399 | 5154278 | 2263420 | 0 | 397 | 8.2 | 22 % |
| mobile (100 ms, 5 Mbit/s, 0.0 % loss) | WARM | 4 | 5 | 16.9 | 17.0 | 393 | 5119966 | 2228973 | 6 | 391 | 8.2 | 52 % |
| mobile (100 ms, 5 Mbit/s, 0.0 % loss) | RESUME | 4 | 5 | 6.5 | 6.5 | 203 | 1160033 | 1120916 | 195 | 202 | 1.9 | 71 % |
| poor (300 ms, 1 Mbit/s, 1.0 % loss) | COLD | 1 | 3 | 205.9 | 205.9 | 399 | 5154278 | 2263420 | 0 | 397 | 41.2 | 80 % |
| poor (300 ms, 1 Mbit/s, 1.0 % loss) | COLD | 4 | 5 | 93.2 | 93.3 | 399 | 5154278 | 2263420 | 0 | 397 | 41.2 | 56 % |
| poor (300 ms, 1 Mbit/s, 1.0 % loss) | COLD | 8 | 3 | 77.7 | 77.7 | 399 | 5154278 | 2263420 | 0 | 397 | 41.2 | 47 % |
| poor (300 ms, 1 Mbit/s, 1.0 % loss) | WARM | 4 | 3 | 89.6 | 89.6 | 393 | 5119966 | 2228973 | 6 | 391 | 41.0 | 54 % |
| poor (300 ms, 1 Mbit/s, 1.0 % loss) | RESUME | 4 | 3 | 30.4 | 30.4 | 202 | 1258514 | 1118477 | 196 | 201 | 10.1 | 67 % |

## §7.3 triggers (parallelism 4)

| trigger | threshold | measured | fired |
| --- | --- | --- | --- |
| cold install, poor profile (median) | > 90 s | 93.2 s | YES |
| warm install, mobile profile (median) | > 15 s | 16.9 s | YES |
| request overhead, cold install, mobile profile | > 50 % | 52 % | YES |

**All three triggers fired, each narrowly.** Per test-plan §7.3 a separate decision (a new ADR,
outside M5.OTA.3) is required; this PR does not change the delivery format. Observations for that
decision:

* The cost is per-request latency: at parallelism 8 the same profiles stay under every threshold
  (poor cold 77.7 s, mobile cold 10.6 s with 22 % overhead). Raising the client's default
  parallelism from 4 to 8 is a one-constant change (`OtaConfig.DEFAULT_PARALLELISM`) that keeps the
  contract; it was not made here because §7.3 asks for a recorded decision.
* "Warm" barely helps between these two builds: A and B share only 6 identical files (the icons
  were re-encoded, OD-6), so a warm install is nearly a cold one. Reproducible icon encoding (OD-6)
  would make most of B's 360 icons reusable from A.
* gzip on the origin would cut the bytes by ~56 % (5.15 MB → 2.26 MB) without any contract change
  (hashes are over decoded bytes, contract §1).
* Batching the ~37 small JSON files into one blob, or HTTP/2 multiplexing, are the options test-plan
  §7.3 names; either must keep every byte pinned by the signed catalog.
* Resume works as designed: the second attempt requests only the missing files
  (202 requests on the poor profile, 30.4 s).

**Not measured here:** Android emulator throttling and a physical device on mobile data (test-plan
§7.2) need an OTA-enabled app build, which cannot exist before hosting and keys (OD-1, OD-2; test
keys are refused by every app configuration). They belong to the Android release gate (§6B).

**How this report was produced.** The numbers are this run's own rows, copied from its output: the
run measured all 15 cases (44 min), then failed while formatting this summary (an unescaped `%`
in the triggers table, fixed in the harness); the summary above was computed from those rows with
the same formulas.
