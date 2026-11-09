# OTA Delivery Latency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Доставлять обычный готовый, одобренный OTA-пакет с полным публичным proof за целевые 600 секунд, сокращая ожидание событий и последовательный I/O.

**Architecture:** Существующие GitHub Actions, protected writer и durable journal сохраняются. Внутри независимых validation barriers объединяются одинаковые blob reads и ограничивается параллелизм; read-only probe восстанавливает пропущенные события, а metadata-only CI опирается на доказанный полный baseline. Успех конкретного релиза и время доставки учитываются отдельно от aggregate health.

**Tech Stack:** Python 3.12, существующие Pydantic/httpx/boto3, concurrent.futures из standard library, Git/GitHub REST, GitHub Actions, pytest/actionlint/graphify. Новые runtime dependencies не нужны.

**Spec:** [2026-11-08-ota-delivery-latency-design.md](../specs/2026-11-08-ota-delivery-latency-design.md), одобрен владельцем сообщением «го». Исследованная база `204deab84563bf9f2b8a141813661452a70d810d`; дизайн commit `492fee526085ba5785584fdee5950440c4200303`.

## Global Constraints

- Цель: **10 минут после принятия approval**, включая CI/очередь/публикацию/CDN/полную проверку; бюджеты 120/300/180 секунд. 600 секунд не являются таймером отмены catalog PUT.
- До 8 параллельных GET/HEAD, до 4 immutable uploads; один coordinator для catalog, journal и signer. Три существующих reference barriers сохраняются.
- Public cache плюс reserved pinned wire bytes ограничены `MAX_RELEASE_BYTES` (268435456 bytes). Mutable catalog не кешируется вместе с blobs.
- Watchdog `3-58/5 * * * *` запускает только delivery probe; `41 3 * * *` остаётся отдельной daily renewal/global-health проверкой.
- Catalog `public, max-age=60`; blobs `public, max-age=31536000, immutable`. Header меняется только при следующей законной публикации/renewal.
- Catalog PUT: одна SDK попытка; неизвестный исход разрешается только чтением exact target/base/other. Никаких новых payload/sequence/PUT при AMBIGUOUS.
- Сохраняются G1–G8, owner G7/FLAG, exact repo/headrepo/workflow/event/attempt/jobs, approval/provenance/live refresh перед секретами и перед PUT; expired artifact не разрешается кешем ZIP.
- Не менять frozen requests/approvals/packages/tags, source/createdAt/manifest, production keyring, мобильные/игровые контракты. 70862 уже доставлен: не публиковать его повторно и не использовать production writes для benchmark.
- Не читать, не обходить и не индексировать `ceremony/`. Перед первым source grep/read/search — `graphify query`; включать это правило в каждый исследующий код subagent prompt. После code edits — `graphify update .` AST-only, без LLM.
- Никогда не merge без прямой команды владельца. PR132 не сливать самостоятельно; его 15-минутную heavy-сверку согласовать с новым probe.

## Review Focus

1. Bytes одного hash используются в разных release roles/lengths: дедупликация не должна пропускать проверку отдельного reference. Тесты Task 2/4.
2. Ошибка раннего worker при медленном соседнем PUT: возврат отказа не оставляет фоновые записи. Тесты Task 1/3.
3. Fast CI случайно принимает skipped/fork/неполный baseline либо rename с необычным именем: fallback в full CI. Тесты Task 9.
4. Daily и watchdog приходят одновременно, либо pending job заменён GitHub: один writer, самостоятельный daily renewal, доставка не теряется. Тесты Task 8/9.
5. Approval отозван и возвращён, либо каталог опубликован, но CDN отстаёт: t0 не обнуляется обычным retry; новый authorization epoch различим, второй PUT не выполняется. Тесты Task 5/10/11.

---

## Рабочая среда и карта файлов

Работать в изолированном worktree/ветке `codex/`, сохраняя текущую пользовательскую рабочую копию. Перед выполнением перепроверить актуальный main и применимость плана; изменение main не означает разрешения merge. Все пути далее относительно корня worktree.

Для команд Python установить:

```bash
export PATH="/Users/alexeykravtsov/Desktop/workspace/TalentForge/.venv/bin:$PATH"
export PYTHONPATH=src
```

| Файл | Ответственность |
| --- | --- |
| `src/talent_data/ota/blob_io.py` (new) | Bounded execution для независимого immutable I/O, deterministic results и drain |
| `src/talent_data/ota/store.py` | Per-pass reference dedup, immutable uploads, catalog cache header |
| `src/talent_data/ota/s3_store.py` | Read/write SDK policy, immutable outcome read-back, безопасная typed диагностика |
| `src/talent_data/ota/automation/public_verify.py` | Bounded public prefetch/cache при сохранении reference-verifier semantics |
| `src/talent_data/ota/automation/reconcile.py` | Deadline и read-only CDN convergence |
| `src/talent_data/ota/automation/authorization.py` | Раздельные trigger/request CI identities |
| `src/talent_data/ota/automation/probe.py` (new) | Secret-free metadata selection и backoff, без full public scan |
| `src/talent_data/ota/automation/ci_validation.py` (new) | Full-baseline classification и strict request/approval-only validation |
| `src/talent_data/ota/automation/timings.py` (new) | Closed telemetry, approval epoch/t0, phase/counter evidence |
| `src/talent_data/ota/automation/cli.py` | Probe entry point, request scoping, wiring и status output |
| `src/talent_data/ota/automation/publisher.py` | Phase boundaries/typed failure, существующая последовательность записи |
| `src/talent_data/notify/automation.py` | Scoped status и тихие no-work результаты |
| `.github/workflows/ci.yml` | Full/metadata-only paths + stable `checks` aggregator |
| `.github/workflows/ota-production-publish.yml` | Probe → locked preflight/writer; раздельные delivery/daily schedules |
| `scripts/ota_ci_validation.py` (new) | Тонкий entry point в `ci_validation.main` |
| `scripts/benchmark_ota_delivery.py` (new) | TEST/local/read-only public performance measurements |
| `docs/ota/publisher-runbook.md` | Новые schedules, scope, SLO, восстановление, пределы |
| `graphify-out/graph.json`, `graphify-out/GRAPH_REPORT.md`, `graphify-out/graph.html` | Существующие генерируемые AST graph outputs после source changes |

Tests дополняют существующие fixtures `publisher_testkit`, `test_automation_publisher.env`, `test_automation_e2e.rehearsal`, `FakeOrigin`, `InMemoryS3`. Новые concurrency fixtures используют locks/events, не wall-clock sleep assertions. Старые fake stores явно поддерживают parallel blob I/O либо используют serial mode.

В ключевых тестах ниже проверки имеют следующий вид; recording fixtures задают локальные counters/trace, а не новые production interfaces:

```python
# Task 1: deterministic output; helper's negative-case tests also assert drain.
assert run_bounded(range(4), lambda value: value * 2, max_workers=2) == (0, 2, 4, 6)
# Tasks 2/11: exact reference read counts on the five-manifest fixture.
assert reference_gets_per_pass == 924
assert reference_gets_across_three_passes == 2772
# Tasks 3/4: resource limits and cleanup, recorded with synchronization events.
assert peak_uploads <= 4
assert peak_reads <= 8
assert peak_cached_and_reserved_bytes <= 268435456
assert active_workers_after_return == 0
assert catalog_put_attempts_after_response_loss == 1
# Task 5: target success is independent of a known old BLOCKED receipt.
assert target_status.outcome == "VERIFIED" and target_status.exit_code == 0
assert global_unknown_status.outcome == "AMBIGUOUS" and global_unknown_status.exit_code == 3
# Task 6: fixture tracks requests made by real authorization code.
assert verified_request_ci_sha == request_change_commit(repo, request)
assert verified_trigger_ci_sha == current_main_sha
# Tasks 7/8: fake server times and side-effect guards.
assert (
    first_attempt_timings.identity.approval_epoch.approved_at
    == retry_timings.identity.approval_epoch.approved_at
)
assert no_work_probe.action == "NO_WORK"
assert blob_reads == secret_reads == journal_writes == 0
# Task 9: the selected validation path must actually succeed.
assert unknown_baseline_decision.mode == "full"
assert skipped_selected_validation_exit_code != 0
# Task 10: execute actual selection snippet with schedule fixtures.
assert watchdog_selected_renewal is False
assert daily_selected_renewal is True
# Task 11: next legitimate write gets the new header, blobs keep the old one.
assert catalog_cache_control == "public, max-age=60"
assert blob_cache_control == "public, max-age=31536000, immutable"
# Task 12: final rehearsal proves delivery with one pointer update.
assert target_receipt.state is RequestState.VERIFIED
assert full_public_proof.ok and catalog_put_attempts == 1
```

### Task 1: Bounded immutable execution с полным drain

**Files:** Create `src/talent_data/ota/blob_io.py`; test `tests/unit/ota/test_blob_io.py` (new).

**Interfaces:** Produces `run_bounded(items: Iterable[T], worker: Callable[[T], R], *, max_workers: int) -> tuple[R, ...]`. Возвращает результаты в порядке входа; iterator читается с bounded lookahead, а не целиком. `max_workers=1` — эквивалентный serial путь. Невалидный limit — `ValueError` до запуска работ.

- [ ] **Step 1:** Написать `test_bounded_order_and_drain`: events доказывают `peak_active <= limit`, фактическое перекрытие работ при limit > 1, входной порядок результатов, остановку новых submissions после обнаружения ошибки и отсутствие активных workers после исключения. `test_serial_equivalence` сравнивает значения/тип первой ошибки; при нескольких ошибках выбирать первую по входному порядку среди submitted items.
- [ ] **Step 2:** Запустить `pytest tests/unit/ota/test_blob_io.py -q`; ожидать RED из-за отсутствующего module/API, а не случайного timeout.
- [ ] **Step 3:** Реализовать `run_bounded` через `ThreadPoolExecutor` и ограниченное окно futures. На failure закрыть iterator при наличии `close`, cancel ещё не начатые futures, дождаться всех started jobs; exception не возвращается до shutdown. Не добавлять transport/journal/signing обязанности этому helper.
- [ ] **Step 4:** Запустить тот же файл; ожидать PASS, включая failing-worker + blocked-neighbour сценарий без висящего test thread.
- [ ] **Step 5:** `graphify update .` AST-only; `git diff --check`; commit `feat(ota): bound immutable I/O and drain failed workers` с source/test и изменёнными graph outputs.

### Task 2: Dedup private reference checks внутри каждого барьера

**Files:** Modify `src/talent_data/ota/store.py:306` (`ensure_catalog_references`); test `tests/unit/ota/test_catalog_reference_io.py` (new).

**Interfaces:** Consumes Task 1. Produces backward-compatible `ensure_catalog_references(store: BlobStore, catalog: OtaCatalog, *, read_workers: int = 8) -> None`; `read_workers=1` для serial equivalence. `ensure_blob` и `read_release_manifest` сохраняют существующие интерфейсы/typed failures.

- [ ] **Step 1:** Написать `test_reference_pass_deduplicates_without_cross_pass_cache`: synthetic retained entries с shared hashes дают `manifest_reads + unique_file_hashes` GET; второй вызов заново читает все hashes и замечает corruption. Отдельно `test_shared_hash_conflicting_lengths_rejected`, `test_each_manifest_identity_checked`, `test_one_missing_unique_file_fails` и serial/parallel comparison. Файл с верным hash, но неверным declared length должен отказать.
- [ ] **Step 2:** `pytest tests/unit/ota/test_catalog_reference_io.py -q` → RED: существующий код читает repeated hashes и не проверяет file length здесь.
- [ ] **Step 3:** Прочитать/проверить каждый manifest и catalog identity, собрать ordered unique `(sha_ref, byte_length)`; conflict отвергнуть. Worker читает `ensure_blob`, сравнивает длину и возвращает только non-body success marker. Не накапливать тела всех файлов. Не переносить markers между вызовами, не убирать существующие три вызова publisher; semantic gates `verify_release_files` остаются отдельно.
- [ ] **Step 4:** Новый файл + `pytest tests/unit/ota/test_automation_publisher.py tests/unit/ota/test_automation_reconcile.py -q` → PASS; подсчёты reference passes в failure/recovery также точные.
- [ ] **Step 5:** AST graph update, diff check, commit `perf(ota): deduplicate each private reference verification pass`.

### Task 3: Parallel immutable uploads и однократная запись catalog

**Files:** Modify `src/talent_data/ota/store.py:280`, `src/talent_data/ota/s3_store.py:215,378`, `src/talent_data/ota/automation/publisher.py`; tests `tests/unit/ota/test_blob_upload_io.py` (new), `test_ota_s3_store.py`, `test_ota_s3_production.py`, `test_automation_publisher.py`.

**Interfaces:** Consumes Task 1. Produces `upload_blobs(store: BlobStore, blobs: Mapping[str, bytes], *, upload_workers: int = 4) -> list[str]` с sorted deterministic written keys. `connect(...) -> S3BlobStore` остаётся совместимым; добавляется optional `write_client` в constructor для separately configured SDK client. `_call` выбирает write client только для mutations. Оба clients создаются до threads.

- [ ] **Step 1:** Написать `test_upload_limit_and_no_background_put_after_failure` (peak <= 4, drained on failure), `test_unknown_blob_put_reads_exact_bytes_before_continuing` (не повторяет PUT), `test_corrupt_existing_blob_never_overwritten`, `test_catalog_sdk_total_attempts_is_one`. Botocore transport fixture моделирует response loss после сохранения catalog: число реальных `put_object` attempts равно 1, результат восстановлен чтением либо AMBIGUOUS.
- [ ] **Step 2:** Запустить перечисленные новые tests по node id; подтвердить RED, включая отсутствие replay при transport loss (одного assertion на вызов mock client недостаточно).
- [ ] **Step 3:** Создать read client с `total_max_attempts=3`, connect 5s/read 15s и write client с `total_max_attempts=1`; не добавлять внешнюю S3 retry-петлю. Оставить virtual addressing/checksum/MD5/ETag/IfMatch/IfNoneMatch/production guard. В workers только blob operations; catalog `_observed`/guard state у coordinator. Unknown immutable PUT сверять по exact bytes/hash/length и нужной metadata; unresolved — typed refusal. Не перезаписывать существующий объект. На upload failure дождаться pool до journal save/signing/credential cleanup.
- [ ] **Step 4:** `pytest tests/unit/ota/test_blob_upload_io.py tests/unit/ota/test_ota_s3_store.py tests/unit/ota/test_ota_s3_production.py tests/unit/ota/test_automation_publisher.py -q` → PASS, включая withdrawal/live change/signer failure/partial upload/lost catalog response. PUT не получает новый payload при неизвестном исходе.
- [ ] **Step 5:** AST update, diff check, commit `perf(ota): parallelize immutable uploads without retrying catalog writes`.

### Task 4: Bounded public prefetch с прежней строгой проверкой

**Files:** Modify `src/talent_data/ota/automation/public_verify.py`, `reconcile.py`; tests `test_automation_public_verify.py`, `test_automation_reconcile.py`.

**Interfaces:** Consumes Task 1. `verify_publication(...) -> PublicVerification` сохраняет существующие параметры и добавляет keyword `read_workers: int = 8`; `verify_after_write(...)` передаёт этот limit. Internal `_BlobCache` в `public_verify.py` предоставляет `read(file: ReleaseFile) -> bytes` и `close() -> None`; владеет futures/cache/reservations, создаётся на один proof и всегда закрывается в `finally`.

- [ ] **Step 1:** Написать `test_public_prefetch_bounded_and_singleflight`, `test_cache_reservations_include_inflight_bytes`, `test_eviction_refetches_without_rejecting_large_retained_catalog`, `test_parallel_matches_serial_strict_failures`. Проверять existing corrupt/missing/oversize/redirect/encoded/identity/same-build cases, conflicting lengths и changed final catalog; ожидаемый payload читается до/после, catalog GET всегда 2 для успешного proof.
- [ ] **Step 2:** `pytest tests/unit/ota/test_automation_public_verify.py -q` → RED для нового параметра/parallel assertions; existing security controls остаются зелёными.
- [ ] **Step 3:** Manifest/semantic traversal оставить в существующем порядке. Prefetch только уникальные ближайшие file hashes после проверки manifest; окно <=8 и reservation до submission, cache + reserved pinned response bytes <=268435456. При давлении — eviction/refetch/serial progress, без deadlock на одном максимальном объекте. Worker выполняет anonymous bounded `_fetch`; coordinator выполняет `verify_release_files`, same-build и dataset-role bookkeeping. Не сохранять mutable catalog в cache. Любой error/expiry прекращает submissions, закрывает responses и drains pool.
- [ ] **Step 4:** В `_fetch` ограничить transient HTTP/network reads тремя total attempts и общим monotonic deadline; один retry-layer. 403, encoding/length/hash/schema/identity ошибки не повторять как transient. `verify_after_write` повторяет только временную недоступность/ожидание ожидаемого payload; permanent strict failure возвращается сразу. Fake clock tests доказывают отсутствие нового fetch после deadline и отсутствие повторного PUT.
- [ ] **Step 5:** `pytest tests/unit/ota/test_automation_public_verify.py tests/unit/ota/test_automation_reconcile.py -q` → PASS; AST update/diff check; commit `perf(ota): verify all public references with bounded prefetch`.

### Task 5: Scoped outcome, точный failure stage и безопасная диагностика

**Files:** Modify `automation/cli.py:175,288,534`, `automation/publisher.py`, `s3_store.py`, `src/talent_data/notify/automation.py`; tests `test_automation_e2e.py`, `test_automation_workflows.py`, `test_ota_s3_production.py`, `tests/unit/test_telegram_notify.py`.

**Interfaces:** `StatusDocument` и `read_status(raw: bytes) -> StatusDocument` сохраняют strict v1 wire shape. Добавить `_Run.scoped_entries() -> tuple[JournalEntry, ...]`, где `JournalEntry` импортируется из `state.py`; второй entry model не создаётся. При `--release-id` normal operations только target; unresolved global entries включаются всегда. Errors получают allowlisted typed S3 diagnostic record, не raw exception text.

- [ ] **Step 1:** Написать `test_scoped_verified_ignores_unrelated_old_blocked`, `test_unscoped_reconcile_keeps_aggregate_failure`, `test_global_ambiguous_blocks_scoped_target`, `test_upload_failure_reports_upload_stage_without_secrets`. Проверить unchanged old receipt bytes, target VERIFIED + full proof → exit0, AMBIGUOUS → exit3; malformed common input остаётся отказом.
- [ ] **Step 2:** Запустить новые E2E tests → RED на текущем aggregate `final()`; notification test доказывает, что старый BLOCKED не подменяет событие активации target.
- [ ] **Step 3:** Применить одну scope-функцию в status/final/pending verification, но global journal validation/write barrier не сужать. Publisher failure определять в месте upload/sign/write/read-back отказа; mapping закрыт и согласован с strict status literals. S3 diagnostic поля: kind/operation/object hash/HTTP status/attempt/elapsed; никакого raw stderr/headers/URL/PEM/env. Notify сохраняет самостоятельный full public proof, no-action не запускает deep scan и не рассылает healthy/pending.
- [ ] **Step 4:** `pytest tests/unit/ota/test_automation_e2e.py tests/unit/ota/test_automation_workflows.py tests/unit/ota/test_ota_s3_production.py tests/unit/test_telegram_notify.py -q` → PASS, включая persisted outcome при cleanup failure.
- [ ] **Step 5:** AST update/diff check; commit `fix(ota): report scoped delivery and typed failure stages`.

### Task 6: Разделение trigger CI и request CI

**Files:** Modify `automation/authorization.py:87`, `automation/cli.py:333,451`; tests `test_automation_workflows.py`, `test_automation_e2e.py`.

**Interfaces:** Сохранить `authorize_request(api, repo, request, *, ci_run_id=None, ci_attempt=None) -> str` как exact-request authorization. Добавить `request_ci_pin(repo: Path, request: ReleaseRequest, *, trigger_sha: str | None, trigger_pin: tuple[int, int] | None) -> tuple[int, int] | None`: возвращает trigger pin только если request-change SHA совпадает; иначе `None`, что включает существующее exact-history discovery. `_Run.invocation()` отдельно доказывает trigger CI/current main.

- [ ] **Step 1:** Написать `test_code_only_trigger_recovers_older_approved_request`, `test_changed_approval_never_uses_old_request_ci`, `test_trigger_current_main_and_request_ci_both_required`. Негативы: foreign repo/headrepo, wrong workflow/event/attempt, PR/fork CI, branch commit, unrelated release, revoked approval, trigger SHA уже не current main.
- [ ] **Step 2:** Новый code-only recovery E2E → RED на старом `request_change_commit != self.ci_sha: continue`.
- [ ] **Step 3:** Убрать event-only skip и разнести pins в `evidence()`/`assess()` так, чтобы mutable refresh каждый раз заново доказывал committed pair и exact request CI. Trigger pin не передавать другому request-change SHA; ошибочный explicit/manual CI pin по-прежнему отвергать, не превращать в silent discovery. Ни raw artifact, ни имя successful job не заменяют API evidence.
- [ ] **Step 4:** `pytest tests/unit/ota/test_automation_workflows.py tests/unit/ota/test_automation_e2e.py -q` → PASS с assertions no secret reads/no writes для каждого отрицательного случая.
- [ ] **Step 5:** AST update/diff check; commit `fix(ota): separate trigger trust from exact request CI`.

### Task 7: Closed delivery telemetry и неизменный approval t0

**Files:** Create `automation/timings.py`, `tests/unit/ota/test_delivery_timings.py`; modify `automation/cli.py`, `publisher.py`, `s3_store.py`, `public_verify.py`, `.github/workflows/ota-production-publish.yml`.

**Interfaces:** `resolve_approval_epoch(api: GitHub, repo: Path, request: ReleaseRequest) -> ApprovalEpoch`; `DeliveryRecorder(identity: DeliveryIdentity, *, clock: Callable[[], float], now: Callable[[], datetime])`, `.phase(name: Phase) -> AbstractContextManager[None]`, `.count(operation: Literal['GET','HEAD','PUT'], *, bytes: int, retry: bool) -> None`, `.finish(*, verified_at: datetime | None, expected_payload_sha256: str | None, proof: PublicVerification | None) -> DeliveryTimings`. Recorder/counters thread-safe. Типы и enums определяются только здесь, immutable snapshots сериализуются strict JSON.

- [ ] **Step 1:** Написать `test_retry_and_code_fix_preserve_first_approval_time`, `test_revocation_creates_new_epoch_without_hiding_package_age`, `test_unknown_server_time_cannot_claim_slo_pass`, `test_parallel_counters_exact_and_secret_free`. Серверный merged_at 12:00 и proof 12:10:00 →600s/PASS; 12:10:00.001 →MISS; неизвестный t0 →UNKNOWN. Model rejects unknown fields, bool-as-number, invalid pins, negative/nonfinite seconds; no token/raw-error export.
- [ ] **Step 2:** Новый test file → RED; telemetry does not participate in signing/journal authorization, recorder failure не стирает durable publication outcome.
- [ ] **Step 3:** Strict models: `ApprovalEpoch` содержит requestDigest, approvalDigest, approvalChangeSha, approvedAt, timeSource `merged_at|ci_created_at`, epoch id. Историю first-parent request+approval читать только по exact paths; определить начало текущей valid authorization epoch и соответствующий API-proven merged PR `merged_at`; revoked interval завершает epoch. Не брать author/committer local clock. API ambiguity/missing time → UNKNOWN, не новый t0. Для явно разрешённого direct push — CI server created_at с пометкой источника. CI queue start/end брать из exact API run/jobs.
- [ ] **Step 4:** `DeliveryTimings`: schemaVersion1/kind `delivery-timings.v1`, identity (repo/release/request/approval epoch/main/trigger CI/request CI/package/staging pins), inputFingerprint, phase records с UTC boundaries + monotonic durations, I/O counters/unique hashes/bytes/retries, expected payload, full public proof result, verifiedAt, elapsedSeconds nullable, slo `PASS|MISS|UNKNOWN|PENDING`, frozen package age. Optional `failure: DeliveryFailure | None`: closed stage/reason, S3 kind/operation/hash/HTTP status/attempt/elapsed nullable; сырые exception strings запрещены. `delivery_input_fingerprint(request_digest: str, approval_digest: str, code_sha: str, artifact_ids: tuple[int, ...]) -> str` хеширует canonical strict JSON; Task 8 использует тот же helper. CLI optional `--timings-file`; artifact names include run/attempt/target. Upload only explicit status/timings paths, never entire temp dir. Старые runs без telemetry остаются читаемыми; backoff history adapter Task 8 использует новые typed records и честно отказывает при недоказанной неполной history.
- [ ] **Step 5:** Добавить hooks/counters в реально измеряемые boundaries и concurrent transports; phase labels отражают failure site. Отдельные notification/cleanup failures видны, full public proof + VERIFIED задают t1. SLO MISS не отменяет законный in-flight PUT и не вызывает его retry. `pytest tests/unit/ota/test_delivery_timings.py tests/unit/ota/test_automation_e2e.py -q` → PASS; actionlint/AST update/diff check; commit `feat(ota): measure approval-to-public-proof latency`.

### Task 8: Read-only probe и ограниченный recovery backoff

**Files:** Create `automation/probe.py`, `tests/unit/ota/test_automation_probe.py`; modify `automation/cli.py`, `automation/reconcile.py`.

**Interfaces:** Consumes Task 6 и existing strict requests/journal/GitHub. Produces `probe_delivery(api: GitHub, repo: Path, journal: Journal, *, now: datetime, trigger_sha: str | None, trigger_pin: tuple[int, int] | None) -> ProbeResult`. Strict `ProbeResult`: `schemaVersion=1`, kind `ota-delivery-probe`, action `NO_WORK|PUBLISH|VERIFY|RECOVER|WAIT|BLOCKED`, ordered releaseIds/requestDigests, reason, nextEligibleAt nullable, inputFingerprint. `main(["probe", "--status-file", ...])` пишет отдельный probe artifact, без расширения production-status command enum.

- [ ] **Step 1:** Написать `test_no_work_has_no_blob_scan_secret_or_journal_write`, `test_probe_selects_pending_after_lost_event`, `test_terminal_never_reactivated`, `test_unresolved_global_only_selects_recovery`, `test_same_input_failures_back_off_but_changed_input_rechecks`. Missing/malformed journal/request/common guard → BLOCKED, не пустая очередь.
- [ ] **Step 2:** Новый test file → RED (module/entry point отсутствуют).
- [ ] **Step 3:** Probe читает trusted current main, committed strict request/approval bytes, metadata exact CI/artifact и journal; не загружает package ZIP/blobs и не выполняет `public_live()` full scan. Для watchdog независимо найти completed-success exact current-main push CI с API repo/headrepo/path/attempt/jobs; ещё выполняющийся CI → WAIT, недоказанный чужой CI не авторизует новый write. `PUBLISHED_NOT_VERIFIED` выбирает VERIFY, global WRITE_STARTED/AMBIGUOUS — RECOVER, pending valid pair — PUBLISH. Не обещать, что probe READY авторизует writer: preflight всё перепроверяет под lock.
- [ ] **Step 4:** Зафиксировать backoff: постоянные approval/provenance invalidity обрабатываются дешёво и не запускают heavy job до смены входов; transient pre-write failure — не ранее следующего 5-минутного slot, максимум 3 heavy failures за 24h для неизменного fingerprint, затем BLOCKED с действием владельца. Fingerprint включает request+approval digests, current code SHA и artifact identities, но не меняет t0. Счётчик восстанавливать read-only из exact-repo workflow runs и strict hash-verified status/timings artifacts; неполная history → BLOCKED, не сброс лимита. Отдельный controlled owner retry сохраняет существующую явную авторизацию. Тесты lost/corrupt/expired history не допускают бесконечные heavy retries; никаких новых journal receipt полей.
- [ ] **Step 5:** `pytest tests/unit/ota/test_automation_probe.py tests/unit/ota/test_automation_e2e.py -q` → PASS; AST update/diff check; commit `feat(ota): probe pending delivery without heavy scans`.

### Task 9: Metadata-only CI с доказанным full baseline

**Files:** Create `automation/ci_validation.py`, `scripts/ota_ci_validation.py`, `tests/unit/ota/test_automation_ci_validation.py`; modify `.github/workflows/ci.yml`, `test_automation_workflows.py`.

**Interfaces:** Produces `classify_changes(api: GitHub, repo: Path, *, head_sha: str, event: str) -> CiDecision`, `validate_metadata_changes(api: GitHub, repo: Path, decision: CiDecision) -> None`, `main(argv: list[str] | None = None) -> int`. `CiDecision` strict fields: mode `full|metadata`, headSha, baselineSha nullable, baselineRunId/Attempt nullable, changedPaths. Thin script только вызывает main. PR events всегда full; fast path только trusted main push.

- [ ] **Step 1:** Написать `test_metadata_requires_real_full_validation_ancestor` и parametrized cases delete/rename/copy/symlink/mode-change/newline-path/code/workflow/graph/dependency change/wrong repo/wrong attempt/skipped full-validation/missing baseline. Только regular added/modified `docs/ota/release-requests/<releaseId>.json` и `docs/ota/releases/<releaseId>.md` допускаются; unknown → mode full. Stale baseline с code change между ним и head → full даже если последний commit только approval.
- [ ] **Step 2:** Новый file → RED; workflow test фиксирует имя `checks`, completed workflow trust и nonselected branch skips.
- [ ] **Step 3:** NUL-safe Git raw diff с modes и rename detection от trusted successful full-validation baseline до exact head. Baseline — ancestor current main и successful non-skipped full-validation job exact trusted CI run/attempt/API repo/headrepo/push/main/workflow. Классификатор failure/неполная API history → full; ошибочные metadata bytes в выбранном short validator → CI failure, не green fallback. Validator использует existing strict parsers/binding/provenance metadata и не принимает G7 за владельца.
- [ ] **Step 4:** Workflow jobs `classify`, `full-validation`, `metadata-validation`, `checks`; aggregator `if: always()` требует classify success и success выбранной validation branch, а не просто отсутствие failure. Существующие ruff/format/mypy/git-history/pytest команды перенести в full-validation без урезания. Первый main run с новой workflow/code веткой full по diff; PR всегда full. Pin новых/затронутых actions к проверенным immutable SHAs, checkout full history/persist-credentials false; никаких PR secrets. GitHub token только read permissions.
- [ ] **Step 5:** `pytest tests/unit/ota/test_automation_ci_validation.py tests/unit/ota/test_automation_workflows.py -q` и `actionlint .github/workflows/ci.yml` → PASS; AST update/diff check; commit `perf(ci): validate metadata-only OTA approvals on a trusted baseline`.

### Task 10: Workflow routing для event, watchdog и daily renewal

**Files:** Modify `.github/workflows/ota-production-publish.yml`, `test_automation_workflows.py`, `test_automation_probe.py`, `docs/ota/publisher-runbook.md`.

**Interfaces:** Consumes probe JSON action/releaseIds и unchanged strict preflight/writer status. Probe outputs являются routing hints, не доказательством готовности/авторизацией. Добавить job `probe` с contents/actions read, без environment/secrets; delivery jobs повторно планируют под `ota-production-writer`, `cancel-in-progress: false`.

- [ ] **Step 1:** Написать `test_event_and_watchdog_route_without_daily_renewal`, `test_daily_renewal_survives_candidate_failure`, `test_no_work_skips_deep_jobs`, `test_cancelled_replaced_pending_jobs_recover_from_committed_state`. Выполнять реальные shell selection snippets с `github.event.schedule`/event payload fixture, не только substring checks; оба cron выражения перечислить на UTC сутки.
- [ ] **Step 2:** Workflow tests → RED: сейчас schedule всегда heavy reconcile и нет probe job.
- [ ] **Step 3:** Добавить schedules `3-58/5 * * * *` и `41 3 * * *`. Event/current-main CI + watchdog → probe → eligible scoped delivery; daily → existing independent renewal/global health, независимо от candidate success. Не писать workflow-level lock: он может заблокировать nested jobs. Catalog writer/renewal/preflights по-прежнему один job-level group; probe hints revalidate. При нескольких candidates обрабатывать последовательно под lock, не matrix parallel writers. Probe JSON передавать как data: trusted Python strict parser проверяет release ID patterns, формирует argv, без eval/command interpolation; shell-инъекция из artifact невозможна. Unresolved recovery выполняется раньше fresh write, одним diagnostic turn. Результаты каждого target сохранять отдельно, global errors не теряются.
- [ ] **Step 4:** Notify не запускается для чистого NO_WORK/WAIT; BLOCKED dedup и independent proof при active change сохраняются. Manual dispatch default execute=false и его закрытые modes остаются; новый обычный путь не вызывает manual dispatch. В runbook описать schedule delay, backoff, target/global scope. В согласованном PR132 обновить ту же инициативу либо явно supersede её новым PR при подготовке PR, без самостоятельного merge.
- [ ] **Step 5:** `pytest tests/unit/ota/test_automation_workflows.py tests/unit/ota/test_automation_probe.py -q`; `actionlint .github/workflows/ota-production-publish.yml` → PASS; AST update только если source менялся; diff check/commit `perf(ota): route events and watchdog through a lightweight delivery probe`.

### Task 11: TTL и benchmark без production writes

**Files:** Modify `src/talent_data/ota/store.py:50`, runbook; create `scripts/benchmark_ota_delivery.py`, `tests/unit/ota/test_delivery_benchmark.py`; modify existing cache header assertions в `test_ota_s3_store.py`/`test_ota_s3_production.py`.

**Interfaces:** `benchmark_ota_delivery.py --mode fixture|public --read-workers 1|8 --output <path>`. Fixture mode: TEST-signed local HTTPS/local S3 fixture с optional network latency; public mode: только anonymous public origin/keyring, никакого writable S3/production credential loader/signing/publishing. Output closed JSON measurement со mode/workers/counts/bytes/elapsed/full proof result; benchmark helper лежит в script, не в production routing.

- [ ] **Step 1:** Написать `test_catalog_header_is_sixty_blobs_unchanged`, `test_benchmark_public_mode_cannot_create_writer`, `test_fixture_reference_counts_2001_to_924` (5 manifests/1996 refs/919 unique files;3 passes→6003 vs2772), `test_latency_measurement_does_not_replace_full_correctness_proof`.
- [ ] **Step 2:** Новые tests → RED для header/CLI; count benchmark сравнивает исходный serial reference loop с optimized implementation на эквивалентном valid reference graph, не ослабляя parser/schema/identity ради чисел.
- [ ] **Step 3:** Установить `CATALOG_CACHE_CONTROL = 'public, max-age=60'`; S3 adapter ожидает ту же константу. Не менять current catalog вручную. Benchmark показывает sequential/parallel elapsed и reads, не содержит flaky “<10s” assertions в unit tests. На pressure cases RAM/reservations/worker counts видны отдельно.
- [ ] **Step 4:** Unit tests → PASS; затем отдельно выполнить fixture baseline/optimized и anonymous read-only public proof с output artifacts. Serial public baseline может быть долгим: bounded command session, не запускать дубликат; не скрывать failed/slow results. Указать, что TTL старого catalog может оставаться300 до следующей легитимной записи и что benchmark не доказывает GitHub end-to-end600s.
- [ ] **Step 5:** Записать фактические результаты/ограничения в runbook; AST update/diff check; commit `perf(ota): reduce catalog TTL and add read-only delivery benchmark`.

### Task 12: Общая проверка и reviewable delivery change

**Files:** Existing tests в `tests/unit/ota/`, workflows/runbook, approved spec/plan и generated graph outputs; без новых product subsystems.

**Interfaces:** Все Task 1–11; required checks/status consumers работают одной согласованной веткой. Реализация не rollout без owner merge.

- [ ] **Step 1:** Написать финальный E2E `test_approval_to_verified_delivery_with_code_only_recovery` на существующем TEST rehearsal: completed main CI, metadata probe, lost-event recovery, one catalog PUT, retained old BLOCKED, target VERIFIED + all-file stable public proof, t0 сохраняется. Негативная вариация withdrawal между preflight и PUT: secrets/write запреты по существующим barriers; global AMBIGUOUS диагностируется read-only.
- [ ] **Step 2:** Запустить этот test; если он уже зелёный после Tasks1–11, зафиксировать это как integration verification, не заявлять наблюдавшийся RED. Проверить coverage каждого Global Constraint/Review Focus по task test names.
- [ ] **Step 3:** Один раз выполнить `ruff check .`, `ruff format --check .`, `mypy`, полный `pytest` (без дубликата уже запущенной сессии), `actionlint .github/workflows/ci.yml .github/workflows/ota-production-publish.yml`, `git diff --check`. После последнего source edit — graphify AST update. Сохранять exact exit codes/counts/durations; известные graph parser warnings обозначать честно.
- [ ] **Step 4:** Сверить diff с областью: frozen70862/70790/70755 bytes/requests/approval/package/tag не изменены, новых keys/production credentials/data/mobile changes нет. Проверить clean commit и review ветки согласно выбранному execution method. Подготовить PR с целью/поведением/tests/benchmark и явным отсутствием фактического доказательства SLO до следующего нового релиза. Если заменяет PR132 — связать PR и не оставить два противоречивых cron изменения готовыми к merge.
- [ ] **Step 5:** Получить exact PR CI repo/headrepo/SHA/event/attempt/jobs green, обновить results. Не merge без команды. После owner merge перепроверить exact tree/frozen pins и normal scheduled/event flow read-only. Измерить600s на следующем независимо одобренном новом frozen релизе, проверить signed payload и все public files; report PASS/MISS с реальными timings. Для нового мониторинга требуется соответствующая задача владельца; завершённый `ota-70862` не воссоздавать.

## Самопроверка плана

Scope: один связанный pipeline доставки; независимый runner/server не входит. Низкоуровневые Tasks1–4 тестируются отдельно; activation wiring завершает Tasks6–10. Telemetry Task 7 предшествует backoff/probe Task 8 и workflow routing Task 10. Промежуточные commits — review units одной ветки, не отдельные rollout PR.

Design coverage: запуск/recovery6/8/10; CI9; I/O1–4; неизвестный PUT3; scoped result5; cache header11; t0/SLO/диагностика5/7; acceptance/benchmark11–12. Review Focus пять случаев покрыты указанными тестами. Функции/типы новых модулей определены в Interfaces соответствующих tasks; existing protocol/public verifier signatures сохранены. План не исполняет production actions и не утверждает достижение600s до end-to-end измерения.
