# ADR-0002: OTA delivery performance within contract v1

* Status: **accepted** — proposed in M5.OTA.4a; accepted in M5.OTA.4b on HTTPS measurements against the
  VK Cloud staging origin (see "M5.OTA.4b: measured over HTTPS" below)
* Date: 2026-10-18 (proposed and accepted)
* Deciders: repository owner
* Trigger: test-plan §7.3 — all three thresholds fired at parallelism 4
  ([client-performance-report.md](client-performance-report.md))
* Constraints: contract v1 unchanged ([ota-contract-v1.md](ota-contract-v1.md)); no archive format
  (ADR-0001 D2); platform HTTP APIs on the device (OD-11)

## Context

M5.OTA.3 measured the per-file design (`OtaPerformanceBenchmark`, the production JVM client stack,
a local origin with injected latency, a shared bandwidth bucket and loss). Release B
(`1.60.1.70450-r1`) is 397 files, 5 077 048 bytes decoded, a 75 792-byte manifest; a cold install is
399 requests (catalog, manifest, 397 files).

| §7.3 trigger (parallelism 4) | threshold | measured |
| --- | --- | --- |
| cold install, poor (300 ms, 1 Mbit/s, 1 % loss), median | > 90 s | **93.2 s** |
| warm install, mobile (100 ms, 5 Mbit/s), median | > 15 s | **16.9 s** |
| request overhead, cold, mobile | > 50 % | **52 %** |

All three fired, each narrowly. The same report, at parallelism 8: poor cold 77.7 s, mobile cold
10.6 s with 22 % overhead.

What the release is made of (computed for this ADR from the committed data of 70450):

| files | count | decoded bytes | gzip -6 | largest |
| --- | --- | --- | --- | --- |
| JSON (talent data, dataset manifest, visuals, tooltips, localization, icon registry) | 38 | 3 075 516 | **232 223** (−92 %) | 140 068 (`icons.json`) |
| PNG icons | 359 | 2 001 532 | 2 009 778 (does not compress) | small (icons) |
| **release** | 397 | 5 077 048 | ≈ 2.24 MB (−56 %) | |

Two facts follow. The time is mostly **per-request latency** (399 round trips divided by the
parallelism), and most of the **bytes that compress** are JSON the origin could compress for free.
"Warm" barely helps between 70374 and 70450 because only 6 files are byte-identical: the icons were
re-encoded on another platform (OD-6), not changed.

## Options

Each option is assessed on time, RAM, CPU, requests, bytes on the wire and robustness on a weak
device on a poor link, and on what it costs the contract.

### O1 — Parallelism 4 → 8 (client constant `OtaConfig.DEFAULT_PARALLELISM`)

* **Time:** measured. Poor cold 93.2 → 77.7 s (−17 %); mobile cold 17.2 → 10.6 s; mobile overhead
  52 → 22 %. Every §7.3 threshold passes at 8 (warm mobile was measured at 4 only; at 8 it tracks
  the cold case, since warm reuses only 6 files).
* **RAM:** each in-flight file is buffered whole and bounded by its manifest `byteLength` (at most
  `maxBytes + 1` bytes are read). With the real data that is 8 × ≤ 140 KB, about 1–2 MB including
  the buffer copy. The contract bound (16 MiB per file) would allow 8 × 16 MiB in theory — only for
  a release the owner signed, so not an attack surface; a byte budget for in-flight downloads
  (e.g. 16 MiB) is a cheap hardening if real files ever grow.
* **CPU:** unchanged in total — the same SHA-256 over the same 5 MB (tens of milliseconds) and the
  same validation; at most 8 instead of 4 TLS handshakes per install.
* **Requests / bytes:** unchanged (399 requests, 5.08 MB decoded).
* **Weak device / poor link:** measured on the poor profile (1 % loss): better, because a stalled
  stream blocks one of eight slots instead of one of four. Eight TCP streams are more aggressive on a
  shared, congested link; for a 2–5 MB download once per build this is acceptable, and the radio is
  on for a shorter time.
* **Not measured yet:** the benchmark origin is plain HTTP on loopback. Over HTTPS every new
  connection costs a TLS handshake (1–2 RTT), and the platform connection pools keep a bounded number
  of idle connections per host (5 by default for the JVM's `HttpURLConnection`; Android's pool is also
  bounded), so 8 parallel requests may open more connections than 4. This must be measured over HTTPS
  before the constant changes (M5.OTA.4b, on the staging stand and on the device).
* **Contract:** none. **Cost:** one constant.

### O2 — gzip (or brotli) on the origin for JSON blobs

* **Bytes:** computed above: 5.08 → ≈ 2.24 MB per cold install (JSON 3.08 MB → 0.23 MB).
* **Time:** the payload part only. Transfer-only time at 5 Mbit/s falls from 8.2 s to ≈ 3.6 s, at
  1 Mbit/s from 41.2 s to ≈ 18 s. *Estimate* (measured time minus the saved transfer time; not
  measured): poor cold at parallelism 8 ≈ 55 s, mobile cold ≈ 6 s. Request overhead is unchanged in
  seconds and grows as a share.
* **RAM / CPU:** a streaming inflate of 0.23 MB into 3.1 MB on the device: negligible. The client's
  byte limit applies to the **decoded** stream, so a compressed response can never inflate past
  `byteLength + 1` bytes in memory (no zip-bomb exposure).
* **Requests:** unchanged.
* **Client:** nothing to change. Contract §1 already makes every hash and length cover the decoded
  body; Android's `HttpURLConnection` requests and decodes gzip transparently, and `NSURLSession` does
  the same on iOS. A CDN that mangled a body would be caught as `BLOB_HASH_MISMATCH` (a transient
  failure, retried). (The desktop JVM fetcher sends no `Accept-Encoding`; it is not shipped.)
* **Publisher / hosting:** CDNs compress by `Content-Type`, and blobs are content-addressed without
  an extension. The remote store adapter (OD-1) therefore sets `Content-Type: application/json` on
  JSON blobs and `image/png` on icons, and the CDN compresses `application/json`. Pre-compressed
  objects (`Content-Encoding: gzip` stored at rest) are **not** recommended: an object store serves
  them whatever the request's `Accept-Encoding` says.
* **Contract:** none.

### O3 — HTTP/2

* **Time:** multiplexes every request over one TLS connection. It removes the connection limit and
  the handshakes, not the request/response round trips; in effect it lets parallelism rise without
  opening more connections.
* **Platforms:** iOS gets HTTP/2 (and HTTP/3) from `NSURLSession` automatically when the CDN offers
  it — no client change. Android's platform `HttpURLConnection` is used as an HTTP/1.1 client here;
  HTTP/2 on Android means adding an HTTP client library (OkHttp, or Ktor confined to the adapter,
  OD-11), with its size, update and supply-chain cost.
* **RAM / CPU:** one connection instead of several; marginally less.
* **Contract:** none. **Decision:** not now on Android; enable it on the CDN (OD-1) so that iOS
  benefits. Revisit only if O1 + O2, measured on devices, still trip §7.3.

### O4 — Reproducible PNG encoding (OD-6)

* **Effect:** warm and refresh installs, not cold ones. ADR-0001 D2 measured that neighbouring builds
  share their icons when the encoding is reproducible (70338 → 70374 adds 0 new PNGs): a refresh then
  downloads ≈ 38 JSON files (≈ 0.23 MB gzipped) instead of ≈ 391 files (≈ 2.2 MB). After launch, the
  common case is exactly that — the next build.
* **RAM / CPU on the device:** less of both (fewer files fetched and hashed).
* **Cost:** a data-pipeline change — one pinned encoding environment for `visuals normalize`, recorded
  in the icon provenance — and a data PR that re-encodes the committed icons (presentation only; the
  talent data and gate G4 are unaffected). Not a client or contract change; nothing is published yet,
  so no published bytes change.
* **Decision:** a separate data milestone (OD-6), not part of the minimal fix: it does not move the
  cold-install thresholds.

### O5 — Batch / archive delivery

* **Time / requests:** the largest effect on requests (a handful instead of 399) and on the poor-link
  cold time.
* **Costs:** ADR-0001 D2 already weighed it. Batching needs a new file role or a new manifest shape,
  i.e. **a new contract version** (§10: a new optional member is not backward compatible) and a new
  storage layout; per-file resume, per-file verification before storing, and reuse of bundled and
  installed files are lost or have to be rebuilt inside the archive; the client gains an archive
  reader with zip-slip, zip-bomb, duplicate-entry and symlink defenses.
* **RAM / disk:** an archive is verified as a whole before use, so it is staged whole (or streamed
  with partial state).
* **Decision:** rejected for v1. Reconsidered only as a contract v2 proposal, and only if O1 + O2,
  measured on real devices over HTTPS, still trip §7.3.

## Decision

The minimal improvement compatible with OTA v1 is **O1 + O2**:

1. **Parallelism 8** as the client default (`OtaConfig.DEFAULT_PARALLELISM`), changed in M5.OTA.4b
   **after** the HTTPS measurement: the benchmark profiles over the staging stand's HTTPS origin
   (connections and TLS handshakes per install recorded), the Android emulator, and the TECNO
   Spark 40 on mobile data. If HTTPS makes 8 worse than measured here, 6 is the next value to measure.
2. **gzip for JSON blobs on the origin**, delivered with the hosting decision (OD-1): the remote store
   adapter sets a per-blob `Content-Type`, the CDN compresses `application/json`. No client change.

Explicitly **not** done: no change to the wire contract, no archive format, no new HTTP library.
HTTP/2 is enabled on the CDN when available (free for iOS); reproducible icons go to the OD-6 data
milestone.

Expected outcome (measured where stated, estimated otherwise): every §7.3 threshold passes with O1
alone in the JVM benchmark (measured); with O2 the bytes on the wire fall by about half (computed)
and the poor-link cold install drops to roughly a minute (estimate). The release gate keeps its rule:
the thresholds are re-measured, not inferred, before OTA is enabled in a public build.

## M5.OTA.4b: measured over HTTPS

Origin: VK Cloud Object Storage (OD-1), the real public HTTPS host; client: the production JVM stack
(`OtaRemoteStagingCheck`, platform TLS roots); release B; the test Mac's Wi-Fi, ≈ 140 ms RTT to
`ru-msk`; three runs per cell ([M5.OTA.4b report](M5.OTA.4b-remote-staging-report.md) §7).

| Case | p4 median | p8 median | Connections (= TLS handshakes) p4 / p8 |
| --- | --- | --- | --- |
| cold (399 requests, 5.15 MB identity) | 25.7 s | **17.0 s** (−34 %) | 4 / 8 |
| warm (6 files reusable from A) | 29.6 s | **19.0 s** (−36 %) | 5–7 / 8 |
| resume after 50 % | 15.7 s | **8.2 s** (−48 %) | 4 / 8 |

* HTTPS did not make 8 worse: one TLS handshake per connection, connections kept alive for the whole
  install, no churn at 8 (the concern of O1 above). 6 was therefore not needed.
* Requests are latency-bound: 96 % of a kept-alive blob request is time to first byte.
* **O2 is delivered by the origin itself**, no CDN: VK compresses `application/json` when asked
  (`Accept-Encoding: gzip`) and serves PNG as is. Measured on release B: 5 077 048 → 2 274 035 bytes
  on the wire (−55 %), every decoded SHA-256 matching. Android's `HttpURLConnection` asks for gzip:
  the TECNO Spark 40 received the 75 792-byte manifest in ≈ 31 kB including TLS and the catalog.
  The adapter stores every blob with its `Content-Type` and never a `Content-Encoding`.
* No HTTP/2 on this origin (O3 stays a CDN/hosting item).
* Not measured: a 5 MB download **on the device** (every published build is bundled), the JVM over a
  mobile network, and the §7.3 synthetic profiles at parallelism 8 over HTTPS. The device decision rule
  above still applies when a device download becomes possible.
* New finding, outside this ADR's options: 7–11 % of new TCP connections to the VK endpoint were not
  accepted from the test network; a stalled request fails a client attempt (completed by the next
  check). It is a client error-handling and hosting topic (report §9), not a parallelism one.

**Applied:** `OtaConfig.DEFAULT_PARALLELISM = 8` (M5.OTA.4b).

## Consequences

* M5.OTA.4b carries one client change (a constant) and a measurement table over HTTPS; the benchmark
  gains an HTTPS mode on the staging stand and reports connections and handshakes.
* The remote store adapter (OD-1) gains a `Content-Type` per blob, and its runbook a check that JSON
  blobs come back with `Content-Encoding: gzip` while their decoded SHA-256 still matches.
* `client-performance-report.md` stays the evidence of record for M5.OTA.3; its numbers are not
  restated as passing until re-measured.
* If device measurements disagree with the JVM benchmark, the device wins, and this ADR is revised
  before any public enablement (OD-17).
