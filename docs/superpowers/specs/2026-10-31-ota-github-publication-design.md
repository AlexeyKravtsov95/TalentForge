# Автоматическая публикация production OTA через GitHub

Дата: 2026-10-31.
Статус: согласован владельцем 2026-10-31; реализация и включение ещё не выполнены.
Проект: AlexeyKravtsov95/TalentForge.

## Цель и согласованные решения

Алексей хочет доставлять новые игровые данные существующим Android/iOS-клиентам без
ручного скачивания пакетов, переноса PAE/DER и запуска команд публикации. Telegram
подключён. Владелец выбрал GitHub Environment Secrets вместо отдельного сервиса подписи.

Последнее действие владельца для нового релиза — проверка и merge релизного PR с точным
approval record. Далее CI загружает недостающие blobs, подписывает подготовленный каталог,
активирует его, проверяет origin/CDN и сообщает результат.

Merge PR с исходными данными сам по себе публикацию не разрешает. Сначала нужен data PR,
затем отдельный релизный PR: G1 требует данные на main, а approval закрепляет точные bytes
пакета, построенного из уже закоммиченных данных.

Предложения проектировщика для согласования: GitHub App для автоматических PR,
автоматическое продление неизменного каталога, отдельный защищённый job подписи,
единственный CI-publisher после переноса write-доступа с локальной машины,
полная проверка blobs в Telegram watcher при смене active release.

## Текущее состояние

- `ota-prepare.yml` обнаруживает/готовит данные и предлагает data PR. События от
  GITHUB_TOKEN не запускают CI нового PR; сейчас требуется ручной close/reopen.
- `ota-staging-verify.yml` прогоняет настоящий JVM OTA-клиент на локальном HTTPS-стенде.
  Это не тест установленного Android/iOS-приложения на устройстве.
- `ota-release-package.yml` фиксирует source SHA и createdAt, создаёт закрытый пакет
  с package.json, manifest, blobs и staging evidence. Approval закрепляет exact bytes.
- `ota_production_release.py` проверяет пакет, загружает blobs, готовит unsigned payload,
  принимает внешнюю DER-подпись и активирует каталог. Подпись сейчас ручная.
- `inline_sign_envelope` запрещает production-ключи. Это ограничение сохраняется.
- VK S3 игнорирует условные PUT: для каталога необходим единственный publisher.
- Каталог живёт 45 дней; OD-13 предусматривает переподпись каждые 30 дней.

## Рассмотренные подходы

| Подход | Плюсы | Цена и ограничения | Решение |
| --- | --- | --- | --- |
| Основной ключ в GitHub Environment Secrets | Не нужен новый постоянно работающий сервис; совместим с существующими клиентами | Job с доступом к секрету может извлечь ключ; меняется модель доверия | Выбран владельцем |
| Отдельный KMS/сервис подписи | Приватный ключ можно держать вне runner | Инфраструктура, права доступа, совместимость с уже закреплённым ключом | Не выбран |
| Офлайн-подпись владельцем | Сохраняет сегодняшнюю модель хранения ключа | Сохраняет ручную операцию каждого выпуска | Не решает задачу |

## Границы

Сохраняются G1–G8, FAIL/STOP без обхода, immutable blobs, подпись DSSE/PAE ECDSA P-256
SHA-256, прежний production keyring, app data level, сохранённые сборки и публичный origin.
Новый сетевой API приложения, новые APK/IPA и изменение публичных контрактов не нужны.

Не входят: автоматическое принятие G5/G7, выдумывание live-build evidence из индекса,
автоматическое добавление механик/data level, автоматический merge, сборка приложений,
миграция хостинга, удаление blobs, автоматический rollback после неоднозначного исхода.

Опубликованный `1.60.1.70532-r1` повторно не выпускается и не требует нового подтверждения
G7 от владельца: его существующий approval относится именно к этому релизу.

## Поток нового релиза

1. Существующая подготовка предлагает data PR; владелец проверяет и вливает данные.
2. Успешный CI на точном main SHA запускает read-only подготовку выпуска. Кандидат
   выбирается по строгим документам/идентификаторам, не по свободному тексту PR body.
   Подписанный live production-каталог проверяется; его активный build становится base.
3. Для закреплённого source SHA запускается staging verification base → candidate.
   Затем строится один exact production package и проверяется staging binding.
   Задача настоящего клиента должна исполниться: кэшированный JUnit без evidence не годится.
4. Автоматика открывает один draft релизный PR: approval template с manifest/source pins,
   строгий машинный release request, ссылки на package/staging run, diff, gates и объём
   загрузки. OWNER-поля остаются явными; бот не пишет согласие за владельца.
5. Владелец заполняет G7 evidence нового build и принятие G5, если нужно, проверяет отчёт,
   переводит PR из draft и вливает. Approval утверждает exact manifest bytes.
6. Успешный CI на main-коммите с этим merge запускает публикацию этой записи.
   Произвольного старого approval на main недостаточно: request должен быть включён или
   изменён именно событием выпуска и ещё не исполнен.
7. Preflight проверяет происхождение artifact, индексы/хеши, воспроизводимость,
   staging binding, актуальный approval из origin/main, live base и G1–G8.
8. Отдельный защищённый job заново проверяет входы, загружает и читает обратно blobs,
   создаёт payload/PAE, подписывает его и проверяет DER по committed public key.
   Перед активацией он повторно проверяет approval/live base и пишет catalog последним.
9. Проверка через S3 и публичный HTTPS origin/CDN подтверждает подпись, sequence, release,
   manifests и все referenced blobs. Только после этого результат — VERIFIED/PUBLISHED.
   Telegram получает короткий итог и ссылку через общую дедупликацию.

Подготовка запускается автоматически. Workflow_dispatch остаётся способом повтора,
а не обязательным ручным этапом обычного выпуска. Общая логика должна быть тестируемым
Python-кодом, повторно использующим существующие publisher/helper.

## Exact package и release request

Машинная запись, отдельная от human approval, закрепляет schema version, repository,
game/release/build/revision, полный source SHA, createdAt, manifest SHA-256, base release,
staging workflow/run/attempt, package workflow/run/attempt, artifact id и digest архива.
Новый формат закрытый и строгий; окончательное имя/путь выбирает разработчик.

GitHub metadata должна доказывать: свой repository, разрешённый workflow path, trusted main
revision, success нужного attempt и соответствие package index. Artifact из PR/fork или
чужого run не получает production-доверие по одному имени. Request содержит идентификаторы,
а не произвольные URL для скачивания или исполнения.

Пакет не пересобирается с новым timestamp под старый approval. Повтор использует exact
package. Истёкший/удалённый artifact означает BLOCKED; восстановление допускается лишь при
доказанно тех же bytes/digest. Иначе необходимы новый package и новый review/approval.

Изменившийся активный base требует обновить staging/package и повторить проверки.
Автоподмена base, downgrade и активация старого ожидающего PR поверх нового релиза запрещены.
Переподпись того же active release не требует нового staging, но payload строится на
актуальном sequence и проходит все проверки succession.

## Telegram и итоговая проверка

Builder check обнаружил гонку: нынешний watcher проверяет catalog + active manifest и
может занять dedup marker раньше полной проверки публикации.

Решение проектировщика: при смене active release watcher сам проверяет все manifests/blobs
проверяемого каталога через публичный origin тем же reference verifier. Только после
полной проверки он занимает dedup marker успешного выпуска. Прямой итог workflow
использует тот же проверяющий и доставляющий путь.

Порядок watcher/publisher не меняет гарантию сообщения: какой бы run ни отправил его
первым, он доказал публичные bytes полностью. Без смены active не нужно каждый час
повторно скачивать все blobs. Сбой Telegram не меняет результат публикации.

## PR token и CI

Отдельный GitHub App установлен только в TalentForge: contents и pull requests read/write;
прочие права — лишь при конкретной необходимости. Installation token используется в job
предложения PR и не передаётся production signer. App private key хранится в отдельном
environment подготовки без production signing/S3 credentials.

Это убирает обязательный close/reopen PR ради CI. Production trigger всё равно проверяет
repository/workflow/main SHA и release request: одно событие бота не разрешает выпуск.
Создание и настройка App — разовая операция владельца; постоянно работающий сервер не нужен.

## Секреты и изменение политики ключей

Environment `ota-production`: только main, без второго ручного approval после релизного PR.
Основной ключ `tf-prod-2026a` берётся от владельца и сверяется по публичному ключу; key id
не меняется. Резервный `tf-prod-2026b` остаётся офлайн. Ключи не передаются в чат.
Новый production-ключ автоматически не генерируется.

Job получает основной PEM, пароль при необходимости и отдельные production S3 access/secret
keys. Временный AWS profile создаётся под ожидаемым именем helper; endpoint/bucket/keyring
берутся из committed profile. Credentials не дают DeleteObject, изменение ACL/policy или
доступ к staging и другим buckets.

PEM используется только явным CI external signer для проверенного PAE. Существующий
staging-only inline signer не расширяется на production. PEM/profile находятся только
во временном каталоге с ограниченными правами и удаляются при любом исходе.
Секреты не попадают в command arguments, shell trace, cache, logs или artifacts.
Actions с доступом к секретам закрепляются полными SHA. Код из непроверенного artifact,
PR или произвольного ref в этом job не исполняется.

Обновляются OD-2, publisher-and-security, runbooks и комментарии с обещанием «production
signing never runs in CI». Модель угроз признаёт: компрометация trusted main workflow/runner
с секретами позволяет получить и ключ, и S3-доступ. Branch restriction не защищает от
вредоносного уже слитого main-кода. Резервный ключ сохраняет существующий путь восстановления;
мгновенной отмены pinned key у установленных клиентов нет.

## Единственный writer, повторы и неоднозначный исход

Release, resign и ручной rollback/recovery используют один репозиторный concurrency group
без cancel-in-progress. Каждый run перед записью заново проверяет base и допустимость
перехода; FIFO GitHub не является гарантией корректного порядка выпусков.

По умолчанию concurrency сохраняет один pending run; `queue: max` расширяет очередь до 100,
но не заменяет долговечную очередь. Слитые release requests остаются в git. Ежедневный
reconciliation и штатные события находят неисполненные requests, продолжают их либо явно
фиксируют STALE/BLOCKED. Отмена pending run или потеря webhook не теряет request молча.
Terminal request не переисполняется после rollback или нового релиза.

При включении владелец выдаёт ограниченные CI credentials и отзывает write-доступ обычного
локального publisher. Иначе GitHub concurrency не защищает от локальной гонки, а S3 CAS
у провайдера не работает. Break-glass: сначала остановить CI publisher, проверить отсутствие
in-flight записи, затем временные credentials для ручного восстановления.

Повтор загрузки пропускает verified immutable blobs. Повтор уже активного exact release
не увеличивает sequence и не откатывает более новый active release. При потерянном ответе
PUT сначала выполняется re-read exact signed payload: опубликован именно он, не записан
либо AMBIGUOUS. В AMBIGUOUS запрещены слепой повтор PUT, новый payload, rollback и сообщение
об успехе; нужна диагностика владельца.

CDN-проверка ждёт ограниченное время с учётом cache max-age до 300 секунд. Если S3 уже
содержит нужный catalog, а CDN ещё не сошёлся, повторяется только verification, без повторной
публикации или sequence bump. После исчерпания ожидания состояние — PUBLISHED_NOT_VERIFIED,
а не «ничего не опубликовано»; Telegram указывает конкретный этап.

Долговечный журнал хранит только несекретные release/request identity, base/target sequence,
payload digest, run/attempt, шаг и исход. Artifact/cache не являются единственным источником
истины: приоритет имеют live signed catalog, committed request/approval и проверки bytes.

## Продление каталога без нового выпуска

Ежедневный trusted-main запуск проверяет срок каталога. При остатке <=15 дней выполняется
существующий resign: тот же active release, те же entries/manifest pins, новый sequence и
timestamps, expiresAt = issuedAt +45 дней. Данные не меняются; новый G7/approval не создаётся.
После успешного resign повтор в тот же день ничего не пишет. Общий lock и проверки
подписи/succession/read-back обязательны.

Повреждённый/истёкший catalog не исправляется обходом verifier: это ручное восстановление.
Telegram не называет переподпись новым релизом; ошибки продления уведомляются по этапу.

## Проверки реализации и включение

Тестами доказываются: approved exact package; отсутствие секретов/записи при refused/STOP;
чужой artifact/run/ref; подмена bytes/source/createdAt; удалённый approval перед активацией;
изменение base/sequence; конкурирующие requests; уже выпущенный/старый request; истёкший
artifact; чужой ключ/отказ подписи; частичная загрузка; потерянный ответ PUT; S3 success/CDN
lag; resign без смены release; Telegram outage без изменения результата; отсутствие утечек.

PR-проверки и rehearsal используют TEST keys и локальный/fake store, никогда настоящие
production credentials. Автоматизация по умолчанию выключена enable flag; без секретов
публикация явно BLOCKED, а не зелёный успех.

Разовая настройка после review реализации: Environment main-only, GitHub App, отдельные
S3 credentials, импорт primary PEM, отключение локального writer и включение flag.
Первый smoke signer проверяет тестовый PAE по pinned public key без записи в catalog.
Текущий production release не служит поводом повторно менять catalog.
Первый следующий настоящий выпуск проверяется end to end без новых app builds.

## Что остаётся разработчику

Точные имена workflows/modules/request schema; композиция jobs; хранение и дедупликация
журнала; retry/time budgets в рамках описанных состояний; минимальные дополнительные App
permissions; разбиение реализации на небольшие PR. Эти решения не добавляют обязательных
ручных команд между data merge и release PR либо между release merge и публикацией.

## Источники для реализации

- Репозиторий: `docs/ota/production-release.md`, `publisher-and-security.md`,
  `publisher-runbook.md`, `open-decisions.md`; `scripts/ota_production_release.py`;
  `src/talent_data/ota/{release_package,signing,s3_store}.py`.
- [GitHub Environment](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments).
- [Concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency).
- [GitHub App authentication в Actions](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/making-authenticated-api-requests-with-a-github-app-in-a-github-actions-workflow).
