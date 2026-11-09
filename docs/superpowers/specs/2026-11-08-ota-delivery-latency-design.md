# Ускорение OTA-доставки в существующем GitHub Actions

Дата: 2026-11-08. Статус: одобрен владельцем сообщением «го»; реализация не начата.
База исследования: main `204deab84563bf9f2b8a141813661452a70d810d`.
Независимая проверка документа для планирования завершена: существенных открытых
вопросов не найдено. Это проверка дизайна, не подтверждение реализации или достижения SLO.

## Согласовано с владельцем

Владелец выбрал оптимизацию существующего GitHub Actions. Цель — обычный готовый
OTA-пакет должен получить подтверждённую публичную доставку за **10 минут после
принятия approval**. Время включает CI, очереди, публикацию, распространение каталога
и полную публичную проверку. Новый release-server или self-hosted runner не выбран.

Количество workers, правила короткого CI, лёгкий watchdog и TTL ниже — предложения
проектировщика для согласования этим документом, а не ранее высказанные требования владельца.

## Фактическая причина работы

Для 70862 после approval прошло ещё 4 ч 13 мин до независимого публичного proof.
Часть задержки вызвали approval-only CI authorization и пропущенное событие после
исправления кода. Последний controlled run занял 45 мин 14 с; внутри него:

| Измеренный этап | Время |
| --- | --- |
| Secret-free preflight | 3 мин 01 с |
| Publish or renew | 37 мин 56 с |
| Notify candidate outcome | 3 мин 08 с |

Очередь первого job заняла 3 секунды, подготовка writer — около 14 секунд.
Следовательно, установка Python и запуск runner не являются главным измеренным расходом.

Добавилось 39 immutable объектов, около 3,15 МБ. Пять retained manifests содержат
1 996 ссылок на 919 уникальных файлов. `ensure_catalog_references()` не объединяет
повторные hashes: вместе с manifests это 2 001 GET за проход. Успешный путь нового
prepared payload вызывает её до подписи, перед PUT и при read-back — 6 003 GET.
Дедупликация внутри каждого из трёх проходов уменьшает этот участок до 2 772 GET.
Остальные gates и публичные проверки добавляют свои чтения.

Архив пакета уже кешируется внутри процесса. Первая причина S3-отказа неизвестна:
код сохранил только `UPLOAD_FAILED`; нельзя считать доказанными сеть или неверные права.
В последнем статусе 70862 был `VERIFIED`, но общий итог оставался `BLOCKED` из-за 70755.

Источник длительностей: [run 42411885377](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/42411885377).
Контрольный signed catalog: sequence 5, payload
`sha256:069b0b3d53f25ff958efe0736c6ed89a075cd59378b52d3921e1a56865085e21`.
Независимый proof всех 403 файлов 70862 завершён 2026-11-07T21:17:13.903Z.

## Рассмотренные подходы

| Подход | Выигрыш | Цена | Решение |
| --- | --- | --- | --- |
| Оптимизация текущих workflows и Python publisher | Убирает потерю событий и лишний последовательный I/O, сохраняет инфраструктуру | Нужны строгие тесты барьеров, конкурентности и CI-классификации | Выбран владельцем |
| Отдельный runner рядом с S3 | Меньше RTT и больше контроля над исполнителем | Сопровождение, изоляция секретов, обновление и защита runner | Не выбран; возможное отдельное решение, если измерения не дадут цель |

Одно увеличение частоты cron не устраняет 38-минутный writer. Открытый PR132 с
15-минутной тяжёлой сверкой должен быть согласован с описанным ниже лёгким входом;
его отдельное слияние этим документом не разрешается.

## Цель, измерение и бюджет

`t0` — серверное `merged_at` GitHub для первого корректно заполненного approval
конкретного frozen request/manifest на main. CI этого commit ещё может выполняться:
его время входит в бюджет. Для отдельного явно разрешённого direct-push пути
используется серверное время создания первого CI run с явной отметкой источника времени.

`t1` — завершена полная публичная проверка ожидаемого signed payload, всех retained
manifests и всех их файлов, повторное чтение каталога подтвердило тот же payload,
записан `VERIFIED` для целевого request и сохранён proof. Зелёный workflow не заменяет t1.

| Бюджет | Предел |
| --- | --- |
| Очередь, короткий CI, передача в публикацию | 120 с |
| Preflight, private gates, blobs, подпись, catalog PUT/read-back | 300 с |
| CDN convergence и полный публичный proof | 180 с |
| Итого | 600 с |

Повтор, исправление кода и новый workflow run не обнуляют t0. Явный отзыв approval
останавливает прежнее разрешение; новое одобрение создаёт новую authorization epoch,
но общий возраст пакета остаётся видимым. Время обнаружения, data review, staging,
подготовки пакета и ожидания release approval учитывается отдельно от этого SLO.

Это целевой норматив, не обещание точного старта внешнего GitHub scheduler.
Задержка GitHub или потеря события учитывается как настоящий SLO miss. Превышение
600 секунд не отменяет in-flight запись и не стирает её результат: безопасная
диагностика/проверка завершается, фактическая доставка и SLO сообщаются раздельно.
Более крупный допустимый пакет не отклоняется из-за нового ограничения размера;
размеры и рост retained catalog отражаются в метриках.

## Область и неизменяемые гарантии

Изменяется только доставка после подготовки exact package: CI для metadata-only
изменений, event/recovery routing, I/O publisher/verifier, диагностика и HTTP cache policy.
Обнаружение патчей 10:00/15:00/20:00 Asia/Novosibirsk, построение игровых данных,
KMP-клиенты и публичные JSON/DSSE/data-level контракты не меняются.

Сохраняются:

- exact source/createdAt/manifest/package/request/approval bytes, staging binding и provenance;
- G1–G8, доказательность игровых правил, владелец как источник G7 и принятия FLAG;
- current-main/repository/head-repository/workflow/event/attempt/job checks;
- повторное чтение изменяемого approval/main/CI/artifact metadata/live base перед секретами
  и перед catalog PUT; expired artifact не разрешается кешированным ZIP;
- protected main-only environment, текущий production keyring и один catalog writer;
- durable journal, read-back, succession, запрет слепого повторного PUT;
- все неизвестные поля/типы и неподтверждённые состояния приводят к типизированному отказу.

Нет автоматического merge, production-dispatch для benchmark, новых ключей,
локальных production credentials, DELETE, смены base ради ускорения или повторного
выпуска уже доставленного 70862. Старые frozen requests/approvals/packages/tags не меняются.

## Запуск и лёгкий recovery

Обычный путь остаётся `CI completed -> workflow_run -> probe -> preflight -> writer
-> public proof`. Публикация не включается в сам незавершённый CI: иначе требование
completed-success CI могло бы зависеть от собственной публикации.

Разделяются две идентичности:

1. Trigger CI доказывает успешный CI текущего main и доверенное происхождение события.
2. Request CI доказывает успешный CI latest first-parent изменения JSON request или
   approval MD именно этого релиза. Обе текущие committed bytes должны совпасть с
   проверенными в том commit. Никакой PR/fork CI не становится main/push CI.

При совпадении commit используется точный trigger pin. После code-only исправления
допускается recovery уже одобренного pending request с независимо найденным точным
request CI; trigger CI при этом относится к текущему main. Это явное изменение
старого event-only выбора, а не искусственное изменение request/approval ради запуска.
Текущие main/approval/provenance/barrier проверки writer продолжают действовать.

Событийный запуск и recovery используют один read-only probe без production secrets
и без journal mutation. Он строго читает committed request/approval identities,
receipt states и нужную CI metadata. Если eligible work отсутствует, не скачивает
блобы, не делает полный public scan и не запускает signer/renewal. Пропущенный
pending job не теряет работу: committed requests и журнал остаются очередью.

Предложение проектировщика: watchdog `3-58/5 * * * *` только для delivery probe.
Отдельное ежедневное `41 3 * * *` сохраняет проверку renewal/catalog health.
Watchdog не запускает тяжёлый renewal раз в пять минут. PR132 приводится к этой
схеме в рамках реализации; самостоятельный merge не выполняется.

Probe не переисполняет terminal receipts и не реактивирует старый release после
rollback. Заведомо неготовое approval/expired artifact не создаёт тяжёлый бесконечный
цикл: состояние и изменение входов видны, повтор определяется причиной и backoff.
Любой глобальный AMBIGUOUS/WRITE_STARTED остаётся барьером для всех новых записей;
разрешается только read-only recovery. Races между probe и preflight разрешает
повторное строгое планирование под существующим job-level lock.

## Короткий CI для request/approval-only изменений

Стабильный required check `checks` сохраняется. Перед выбором пути полная разница
с доверенным full-CI baseline проверяется по Git, включая modes, добавления,
изменения, удаления и переименования; PR labels и body не являются доказательством.

Короткий путь разрешён только для обычных файлов в:

- `docs/ota/release-requests/<releaseId>.json`;
- `docs/ota/releases/<releaseId>.md` — approval record точного релиза.

Удаления, rename/symlink, неизвестный путь, любые code/workflow/dependency/test/graph
изменения или недоказанный baseline включают полный CI. Полный baseline должен быть
предком текущего main, иметь завершившийся success (не skipped) `full-validation` job того же доверенного
CI workflow и exact API repo/head/SHA/attempt. Все изменения после него должны
попадать в allowlist. Нельзя принять прежний green CI другого кода по одному имени.
После внедрения первый main CI с изменённым workflow обязательно полный. Неясный
legacy baseline означает full CI, а не эвристику по названиям шагов.

Короткая проверка использует существующие строгие parsers и проверяет identities,
manifest/source pins, approval binding/placeholder fields, frozen package origin и
staging/package run/attempt/artifact metadata. Она не принимает G7 за владельца и
не делает production writes. Полные gates остаются в protected publication.

CI run обязан полностью завершиться success до публикации. `checks` агрегирует
правильную ветвь: skip/failure выбранной проверки не может дать success. Изменение
кода в том же commit всегда проходит полный CI, даже если это даёт SLO miss.

## I/O и границы кеширования

Число логических барьеров проверки первоначально сохраняется. Экономится сеть
внутри каждого прохода, а не свежесть mutable inputs между ними.

Для каждого `ensure_catalog_references`:

1. Каждый manifest читается и проверяется по своему catalog pin и identity.
2. Строится набор уникальных blob hashes с ожидаемыми lengths. Несогласованные
   lengths одного hash отвергаются.
3. Один GET на уникальный hash внутри этого прохода проверяет bytes/hash/length.
   Semantic/role проверки reference verifier сохраняются для каждого использования.
4. Успешные marker/cache entries не переходят через следующий отдельный барьер.

Предложение проектировщика: до 8 параллельных GET/HEAD и до 4 загрузок различных
immutable blobs. Catalog reads/writes, journal saves и signer выполняет только
координатор. Один hash имеет только одного worker; порядок итогов детерминирован.
Catalog PUT никогда не попадает в pool.

Public verifier сохраняет свой порядок semantic проверок, checks HTTP headers,
no redirects/auth, все manifests/files и повторный signed catalog read. Prefetch
ограничен, одновременные запросы одного hash объединяются. Существующий byte cache
ограничен `MAX_RELEASE_BYTES` (сейчас 256 MiB); cache плюс зарезервированные pinned
bytes in flight ограничиваются тем же wire-byte бюджетом. При давлении кеша
допускается повторное чтение после eviction, а не отказ допустимому каталогу.
Mutable catalog bytes никогда не обслуживаются из blob cache.

SDK clients создаются до запуска потоков. Boto3 Session не создаётся/не меняется в
workers; metadata/hooks общего client не мутируются. Mutable catalog ETag state
адаптера остаётся у координатора. Fake/test stores получают явную поддержку
конкурентного blob I/O или сериализованный режим для проверки эквивалентности.

При ошибке прекращается запуск новых работ; in-flight работы завершаются/закрываются
и pool полностью drained до journal transition, подписи, PUT и очистки credentials.
Нельзя вернуть BLOCKED, оставив фоновые PUT работающими. Ответы и read buffers всегда закрываются.

## Retry и неизвестный исход

GET/HEAD допускают ограниченные повторы transient network/service ошибок в общем
monotonic deadline; 403, неверный hash, schema failure и неоднозначные данные не
становятся успешными из-за retry. Предложение: до 3 total read attempts, connect
timeout 5 с и read timeout до 15 с, ограниченный остатком бюджета операции.
SDK и приложение не образуют две вложенные бесконтрольные retry-петли.

Catalog PUT — одна SDK попытка; автоматический SDK retry для него отключён.
Потерянный ответ разрешается существующим read-only сравнением exact target/base/other
и journal barrier. AMBIGUOUS не создаёт новый payload, новый sequence или повторный PUT.

Для immutable upload неизвестный ответ сначала приводит к read-only проверке
конкретного hash/length/metadata. Доказанно существующий точный объект не переписывается.
Если исход не разрешён — отказ и диагностика. Продолжение после доказанного отсутствия
catalog PUT использует прежний frozen пакет и пропускает подтверждённые blobs;
оно не повторяет неизвестную запись каталога. Постоянная ошибка не создаёт
неограниченные автоматические workflow reruns. Approval/base/provenance перед
следующей разрешённой записью всё равно проверяются заново.

## Кэш каталога и публичный proof

Предложение: catalog `Cache-Control: public, max-age=60`; immutable blobs сохраняют
`public, max-age=31536000, immutable`. Контракт v1 прямо допускает catalog TTL ниже
300 секунд; schema/keyring/DSSE не меняются.

Новый header применяется при следующей законной публикации/renewal. Ради смены TTL
не переписывается текущий каталог с тем же sequence и не переиздаётся 70862.
Публичный polling ограничен deadline и проверяет ожидаемый signed payload. CDN lag
продолжает только verification, без повторной публикации.

Финальный `verify_publication` читает **все referenced files проверяемого каталога**,
а не выборку и не только новые 39 объектов. Старый approval, green CI и private S3
success не заменяют этот proof. Уведомитель сохраняет существующий самостоятельный
путь проверки при смене active; он получает тот же ускоренный verifier. Передача
непроверенного PASS через общий cache вместо публичного чтения не вводится.

## Результат конкретного релиза и диагностика

Для операции с `--release-id` успех определяется целевым receipt и его полным
public proof. Успешный target не превращается в failed delivery из-за известного
BLOCKED другого старого release. Эти старые receipts не переписываются ради зелёного
статуса; aggregate health остаётся отдельно видимым в ежедневной проверке.
Глобальный unknown/write barrier, malformed inputs и реальные ошибки общих guards
по-прежнему запрещают успех/новую запись.

Существующий strict status format должен иметь однозначную семантику scope:
scoped result содержит target operations; при глобальном барьере также показывает
его critical receipts и top-level отказ. Для unscoped reconcile сохраняется aggregate
результат. Workflow exit/result и notifier согласованно используют эту семантику;
это не изменение клиентских OTA wire schemas.

Отдельный закрытый `delivery-timings.v1` artifact фиксирует release/request digest,
approval epoch, trigger/request CI pins, main/package/staging identity, phase start/end,
elapsed seconds, GET/HEAD/PUT counts, unique hashes, bytes, retry counts, expected
payload, proof и SLO outcome. Фазы включают очереди/CI/preflight/provenance/gates/
upload/sign/catalog PUT/private read-back/public verification/notification.
Реальная ошибка устанавливает stage непосредственно в месте отказа: старое значение
PROVENANCE не описывает успешные gates с последующим upload failure.

Для S3 сохраняются только allowlisted typed kind, operation, object hash, HTTP status
и attempt/elapsed. Credentials, PEM, environment dumps, signed URLs, headers auth
и произвольный exception text не попадают в logs/artifacts. Unknown fields/types
диагностического формата отвергаются. Notify outage не меняет результат публикации;
повторяющиеся healthy/pending состояния не создают сообщения владельцу.

## Критерии проверки

- На эквивалентном reference graph 70862 private reference checks дают 924 GET за
  проход вместо 2 001; три независимых прохода дают 2 772 вместо 6 003.
- TEST/local HTTPS/S3 fixtures доказывают предел workers/in-flight bytes, single-flight
  hash, drain после ошибки, закрытие responses и отсутствие фоновых PUT после отказа.
- Serial и parallel пути дают одинаковые strict результаты для corrupted/missing/
  oversized/redirect/encoded objects, bad manifest identity, same-build revisions
  и смены signed catalog во время чтения.
- Сохраняются тесты withdrawal до секретов и перед PUT, live/main/CI/attempt changes,
  expired artifact, foreign repo/fork, signer failure, terminal receipts/rollback,
  partial upload, lost catalog response и S3 success/CDN lag без второго PUT.
- Fast CI доказывается против неподдерживаемых путей/modes/rename/deletion/unknown
  baseline и неверного полного baseline; никакой fake skipped job не авторизует writer.
- Event recovery доказывает code-only исправление с pending approved request,
  потерянный workflow event, disabled flag, no-work без deep scan и независимый daily renewal.
- Target70862-подобный VERIFIED вместе со старым BLOCKED не даёт ложный failed delivery;
  глобальный AMBIGUOUS в том же примере всё равно блокирует новую запись.
- Timings и ошибки проверяются на отсутствие секретов и правильный неизменный t0.

Wall-clock performance измеряется отдельным benchmark, не хрупкими unit assertions
со sleep. Сначала TEST/local fixtures с latency и известными hashes, затем read-only
origin/CDN benchmark без production credentials. Реальные записи для benchmark
не выполняются. Следующий нормально одобренный новый релиз даёт end-to-end проверку
600 секунд; SLO объявляется достигнутым только по её измерению. Если цель не достигнута,
отчёт показывает оставшийся расход; переход к другому runner требует отдельного решения.

## Что оставлено реализации

Размещение внутренних helpers, имена TEST fixtures и конкретный интерфейс bounded
executor — решение разработчика. Worker limits могут быть уменьшены по измерениям
throttling; повышение за предложенные пределы требует повторной проверки ресурсов.
Порядок небольших reviewable изменений оформляется отдельным implementation plan
после согласования этого дизайна. Все изменения source проходят ruff/format/mypy/pytest,
workflow changes — actionlint, code changes — graphify update AST-only.
Самостоятельного merge и включения непроверенного production-кода не предусматривается.

## Основания

- [Исходный согласованный дизайн publication](2026-10-31-ota-github-publication-design.md).
- [OTA v1 cache policy](../../ota/ota-contract-v1.md): catalog max-age 300 or lower.
- [Publisher на исследованном main](https://github.com/AlexeyKravtsov95/TalentForge/blob/204deab84563bf9f2b8a141813661452a70d810d/src/talent_data/ota/automation/publisher.py).
- [Reference checks на исследованном main](https://github.com/AlexeyKravtsov95/TalentForge/blob/204deab84563bf9f2b8a141813661452a70d810d/src/talent_data/ota/store.py#L306).
- [GitHub schedule delays](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule): watchdog не является гарантией точного старта.
- [Boto3 client concurrency](https://docs.aws.amazon.com/boto3/latest/guide/clients.html#multithreading-or-multiprocessing-with-clients): client и Session имеют разные ограничения.
