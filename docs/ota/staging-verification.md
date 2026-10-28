# Staging-проверка OTA-кандидата перед production (M5.OTA.AUTO.4)

Один запуск workflow проверяет одну пару сборок — исходную (**base**) и проверяемую
(**candidate**) — из одного зафиксированного коммита:

**фиксация SHA → сборка обоих релизов существующим publisher → публикация на изолированный
staging-стенд → настоящий OTA-клиент как приложение до обновления → проверяемый отчёт.**

Workflow: [`.github/workflows/ota-staging-verify.yml`](../../.github/workflows/ota-staging-verify.yml).
Entrypoint: [`scripts/ota_staging_verify.py`](../../scripts/ota_staging_verify.py).
Чистая логика (выбор ревизии, сверки, сценарии, статусы, JUnit):
[`src/talent_data/prepare/staging_verify.py`](../../src/talent_data/prepare/staging_verify.py).

**Staging PASS не разрешает production-публикацию.** Проверка ничего не публикует за пределы
runner, не читает и не пишет production, не подписывает production-ключом (его в CI нет), не
пушит, не открывает PR и не мёржит. Правила допуска не меняются: approval-запись
`docs/ota/releases/<releaseId>.md` (G7 / G5), продвижение и офлайн-подпись остаются за владельцем
([publisher-runbook.md](publisher-runbook.md)). В отчёте это поле `"productionApproval": false`.

## 1. Запуск

**GitHub:** Actions → **OTA staging verification** → Run workflow.

| вход | обязателен | по умолчанию | смысл |
| --- | --- | --- | --- |
| `revision` | нет | пусто = HEAD `main` | коммит или ref на `main`, из которого собираются оба релиза |
| `candidate` | да | `1.60.1.70532` | версия проверяемых данных |
| `base` | да | `1.60.1.70450` | версия, на которой приложение работает до обновления |

Значения по умолчанию — первый сценарий, 70450 → 70532; обе версии уже в `main`. Pull request,
меняющий файлы проверки, запускает этот же сценарий от HEAD `main`. Workflow появляется в списке
Actions только после попадания в `main`.

**Локально** (каталог вне git-checkout и пустой; нужен JDK 17 для Gradle):

```bash
pip install -e ".[dev]"
python scripts/ota_staging_verify.py run ~/tf-staging \
    --base 1.60.1.70450 --candidate 1.60.1.70532 [--revision <sha|ref>] [--skip-client]
```

## 2. Выбор ревизии: один SHA на все фазы

* Пустой `revision` — HEAD `origin/main` в checkout runner (`fetch-depth: 0`). Иначе — коммит,
  тег или ветка; допускаются только имена `[0-9A-Za-z._/-]`, начинающиеся не с `-`.
* Ревизия разрешается **один раз** в полный 40-символьный SHA (проверка `R1`). Дальше этот SHA
  передаётся во все фазы: `build-release --commit <sha>` для обоих релизов, экспорт данных base для
  клиента, свойство `-Dtalentforge.ota.candidate.revision=<sha>`; клиент сверяет его со стендом.
* Коммит обязан лежать на `origin/main`: publisher (G1) не собирает релиз из другого коммита, и
  production-релиз собирается только из `main`.
* Обе версии должны быть каноническими `a.b.c.build`, candidate новее base, и у обеих должен быть
  закоммиченный датасет на этом SHA. Иначе `R1 = BLOCKED` с перечнем всех причин, остальные
  проверки — `BLOCKED`, отчёт пишется всё равно.
* Отчёт содержит полный SHA (`revision`), источник выбора (`facts.subject.source`), обе версии
  (`baseBuild`, `candidateBuild`) и SHA checkout, из которого запущены инструменты
  (`facts.tooling.head`): скрипты и Kotlin-клиент берутся из checkout workflow, данные релизов и
  bundle — из зафиксированного SHA.

## 3. Что проверяется

| id | фаза | проверка |
| --- | --- | --- |
| R1 | resolve | ревизия → один полный SHA на `origin/main`; обе версии закоммичены на нём |
| P1 | publish | оба релиза собраны publisher из этого SHA; G1–G8 на staging — все PASS |
| P2 | publish | файлы собранных релизов = закоммиченные данные SHA (путь, SHA-256, размер), без лишних и недостающих |
| P3 | publish | staging-store содержит манифесты и все файлы обоих релизов с их байтами; каталог проверяется staging-ключом, production keyring его отвергает |
| P4 | publish | staging сначала содержит только base, затем перечисляет оба релиза и активирует candidate |
| P5 | publish | изоляция: эфемерный ключ `tf-staging-local-*`, loopback HTTPS, `verify-live` после каждой публикации, publisher отвергает релиз с неподдерживаемой механикой (G6) |
| K0 | client | Kotlin-набор `OtaCandidateStagingTest` выполнен целиком, ничего не пропущено |
| K1 | client | evidence клиента называет запрошенные версии и SHA; установленная копия = собранный релиз (путь, SHA-256, размер) |
| C1 | client | запуск на base; candidate нет ни в bundle, ни в установленном store |
| C2 | client | candidate найден в обновлённом каталоге и загружен по HTTPS с проверкой подписи и SHA-256 |
| C3 | client | candidate активирован; новый редактор работает на его данных |
| C4 | client | редактор, открытый до активации, сохраняет snapshot base |
| C5 | client | сохранённые непустые билды открываются на base без изменений |
| C6 | client | холодный старт сохраняет candidate; повторная проверка ничего не скачивает |
| N1 | client | повреждённая подпись каталога ничего не меняет |
| N2 | client | повреждённый манифест релиза или blob ничего не меняет |
| N3 | client | обрыв загрузки: base остаётся; после рестарта докачиваются только непроверенные файлы |
| N4 | client | нет сети в момент появления candidate: base остаётся, store побайтно тот же |
| G1 | guard | checkout, `PRODUCT_BUNDLE`, `BUNDLED_BUILDS`, production keyring и статус отслеживаемых файлов не изменились |
| G2 | guard | в каталоге отчёта не осталось приватных ключей |
| M1 | device | Android: эмулятор и физическое устройство — **не автоматизировано**, всегда NOT RUN, в итог не входит |
| M2 | device | iOS: симулятор и физическое устройство — **не автоматизировано**, всегда NOT RUN, в итог не входит |

### 3.1 Тестовый клиент — приложение до обновления

`OtaCandidateStagingTest` → [`StagedUpdate`](../../mobile/composeApp/src/desktopTest/kotlin/io/github/alexeykravtsov95/talentforge/ui/ota/OtaStagedUpdate.kt):

* bundle — только base, он же продукт; файлы bundle читаются из данных base **зафиксированного
  SHA** (`<out>/subject`, экспорт тем же чтением git, что у publisher); candidate в экспорт не
  попадает;
* OTA включён только для staging-канала через `OtaConfig.resolve`, как в сборке приложения;
* настоящие адаптеры: fetcher на `HttpURLConnection`, доверяющий только CA стенда (проверка
  сертификата и имени хоста включена), подписи через `java.security`, store на `java.nio`,
  репозиторий билдов `LocalBuildRepository`, загрузка редактора `loadEditorSession`, создание
  черновика `createClassDraft`;
* сохранённые билды — валидные и непустые: Mage 21, Priest 11, Warrior 5 очков, потраченных
  настоящим движком аллокации; содержимое библиотеки сверяется до и после (кроме
  `lastOpenedAt`, которое меняет само открытие), каждый билд открывается на base с теми же
  аллокациями и читает только файлы base;
* хранилище библиотеки — реализация контракта `BuildLibraryStorage` в памяти
  (`FakeBuildLibraryStorage`): `DesktopBuildLibraryStorage` пишет в пользовательские
  `java.util.prefs`, а пользовательские данные проверка не трогает. Разбор, валидацию и запись
  выполняет настоящий `LocalBuildRepository`.

Тест сначала сверяет стенд с запрошенными `base`, `candidate` и SHA. Стенд другой пары, например
исторический 70374 → 70450, проверкой этого кандидата не считается: тест падает, а оркестратор
отдельно проверяет evidence (`K1`).

Отрицательные сценарии запускаются на устройстве, где base уже установлен со staging. В каждом
проверяется, что активный релиз, продукт и библиотека остались прежними, `releases/<candidate>`
не появился, сохранённые билды открываются без изменений, а следующая чистая проверка
устанавливает candidate.

## 4. Статусы и итог

| статус | смысл |
| --- | --- |
| PASS | проверка выполнена и прошла |
| FAIL | проверка выполнена и не прошла |
| BLOCKED | не установлено: вход нельзя проверить, зависимость недоступна, Gradle не запустился или не отчитался, фаза до этого не дала результата |
| NOT RUN | сознательно не выполнялось (`--skip-client`, пропущенный тест) или требует устройства |

Итог — по всем автоматическим проверкам: любой FAIL → FAIL; иначе BLOCKED; иначе NOT RUN; PASS
только если **все** PASS. Проверки на устройствах (M1, M2) не автоматические и в итог не входят:
засчитать их прогоном нельзя.

Коды выхода: `0` PASS, `1` FAIL, `3` BLOCKED или NOT RUN, `2` ошибка использования. Шаг
`Final status` зелёный только при `0`. Пропущенный клиентский прогон, отсутствие JUnit-результатов,
пропущенный или отсутствующий тест сценария дают NOT RUN или BLOCKED, но не PASS. Тесты
успешны, а Gradle завершился с ошибкой — FAIL.

## 5. Артефакт

`ota-staging-verify-<run id>-<attempt>` (30 дней):

* `staging-report.json` — `talentforge-ota-staging-verification` v1: `verdict`,
  `productionApproval: false`, `revision` (полный SHA), `baseBuild`, `candidateBuild`, все
  проверки с деталями, факты: SHA и его источник, гейты publisher, манифесты, снимки staging,
  результаты тестов, evidence клиента;
* `summary.md` — то же для человека, также Actions job summary;
* `junit/staging-checks.xml` (проверки: FAIL → failure, BLOCKED → error, NOT RUN → skipped) и
  `junit/TEST-…OtaCandidateStagingTest.xml` (Gradle);
* `logs/publisher.log`, `logs/gradle-client.log`, `logs/<phase>-crash.txt` при сбое фазы;
* `releases/<releaseId>/release.json` — собранные манифесты обоих релизов;
* `client/<test>.json` — evidence каждого клиентского теста (с запрошенными версиями и SHA,
  установленными файлами);
* `stand/report.json`, публичный staging keyring, CA стенда.

Исключены: приватный staging-ключ (`stand/keys/*.pem`) и TLS-ключ (`stand/tls/server.key`). Скрипт
удаляет их в конце прогона, `G2` проверяет, что в каталоге не осталось PEM с приватным ключом, а
upload-шаг исключает их повторно. Также исключены воспроизводимые stores стенда, его scratch-клон
и экспорт данных.

## 6. Автоматический прогон и устройства

**Автоматически (JVM):** настоящий код `jvmCommonMain` клиента, тот же, что работает на Android,
в `:composeApp:desktopTest`. Без Android TLS-стека, `noBackupFilesDir`, жизненного цикла Activity,
iOS и физических устройств.

**Каждый стенд прогоняет клиента заново.** Evidence (`client/*.json`) пишется вне выходов задачи.
Поэтому `desktopTest` с любым свойством `talentforge.*` никогда не берётся из build cache и не
считается UP-TO-DATE (`build.gradle.kts`), а скрипт дополнительно передаёт `--rerun`. Если Gradle
всё же сообщит `desktopTest FROM-CACHE` / `UP-TO-DATE` / `SKIPPED` / `NO-SOURCE`, отчёт о тестах не
засчитывается: K0 и все клиентские сценарии получают BLOCKED. Проверка evidence (K1) не
ослаблена. Так прогон 40707232402 и получил BLOCKED: кэш восстановил отчёт 6/6 без
`client/theCandidateReplacesTheBaseOverHttps.json`.

**Регрессия на PR.** После первого прогона workflow переносит отчёт в сторону и запускает тот же
стенд ещё раз — в тот же путь, с теми же сборками и коммитом, на только что прогретом кэше
(шаг «Re-run on the warm Gradle cache», артефакт `ota-staging-rerun-<run>-<attempt>`). Задача
зелёная только при PASS обоих прогонов.

**Не автоматизировано, NOT RUN (M1, M2):**

* **Android, эмулятор и физическое устройство.** Для пары 70450 → 70532 исторический APK не
  нужен: обычная debug-сборка бандлит 70450 как продукт и не содержит 70532, то есть это и есть
  приложение до обновления.

  ```bash
  python scripts/ota_staging_stand.py prepare ~/tf-ota-stand \
      --base 1.60.1.70450 --candidate 1.60.1.70532 --commit <sha>
  python scripts/ota_staging_stand.py activate ~/tf-ota-stand 1-a-staged
  python scripts/ota_staging_stand.py serve ~/tf-ota-stand --port 8443      # отдельный терминал
  source ~/tf-ota-stand/android-staging.env
  cd mobile && ./gradlew :composeApp:assembleDebug -Ptalentforge.applicationIdSuffix=.otastaging
  ```

  Затем шаги [android-device-smoke.md](android-device-smoke.md) §3 и таблица §6 отчёта
  [M5.OTA.AUTO.2](M5.OTA.AUTO.2-replay-report.md) с заменой 70374 → 70450 и 70450 → 70532
  (`activate … 2-b-staged`, About → Check for updates, сохранённый билд, холодный старт, обрыв).
  Для физического устройства — `adb reverse` (android-device-smoke.md §1.3). Суффикс `.otastaging`
  ставит отдельное приложение; пользовательский TalentForge и его билды не затрагиваются.
* **iOS, симулятор и устройство.** Для кандидата не запускалось. Существующий iOS-прогон
  M5.IOS.2 ([`scripts/ios_ota_e2e.py`](../../scripts/ios_ota_e2e.py), задача «iOS OTA staging E2E»
  в `mobile-ci.yml`) гоняет настоящий iOS-клиент на симуляторе против того же стенда, устанавливая
  его CA в trust store симулятора, но только для исторической пары стенда по умолчанию
  70374 → 70450; переданные `base`/`candidate` он не проверяет. Его обобщение на пару кандидата и
  физическое устройство — отдельная задача.

## 7. Чем это не является

* Не staging-smoke на удалённом staging (VK Cloud, CDN): стенд — локальный HTTPS-origin на
  runner. Поведение CDN, кешей и публичного TLS здесь не проверяется (M5.OTA.4b).
* Не подготовка новой сборки: данные обеих версий уже должны быть в `main`. Подготовку делает
  [auto-prepare.md](auto-prepare.md).
* Не approval-запись. Манифест, собранный здесь, отличается от будущего production-манифеста
  `createdAt` (и SHA, если коммит другой), поэтому его SHA-256 не пинится в approval-записи. Файлы
  (путь, SHA-256, размер) при том же SHA совпадают — это и проверяют `P2` и `K1`. Пакет
  production-релиза привязывает PASS этого прогона к своим файлам по `staging-report.json`,
  `releases/<candidate>-r1/release.json` и evidence клиента
  ([production-release.md](production-release.md) §3). Артефакт хранится 30 дней, поэтому пакет
  собирают, пока он доступен.
* Не подтверждение live-версии: для 70532 владелец подтвердил, что сборка работала на серверах во
  время беты ([auto-prepare.md](auto-prepare.md), раздел «Live-build evidence: 1.60.1.70532»);
  какая сборка работает после запуска, не установлено.

## 8. Тесты

* `tests/unit/test_ota_staging_verify.py` (офлайн): выбор ревизии во временных git-репозиториях
  (пусто, ref, тег, короткий и полный SHA; опция вместо ref, несуществующий ref, коммит вне `main`,
  неканоническая или старая версия, нет датасета, несколько причин сразу); сверки файлов и
  staged-store; разбор гейтов publisher; итог и коды выхода; JUnit; поиск приватных ключей;
  настоящий publish-прогон 70450 → 70532 со `--skip-client`; заблокированные входы с отчётом;
  клиентская фаза с подменой Gradle — PASS, evidence другой пары (FAIL), другая установленная
  копия (FAIL), упавший или пропущенный тест, Gradle с ошибкой при зелёных тестах, нет
  результатов, Gradle не запускается; полный прогон с кодом 0; шаг `Final status` в bash для
  каждого кода; входы, права и исключения upload в workflow.
* `tests/unit/ota/test_ota_staging_stand.py`: стенд для произвольной пары и отказ на одинаковой.
* Kotlin: `OtaCandidateStagingTest` (opt-in) и исторический `OtaHistoricalReplayTest` на общем
  `StagedUpdate`.

## 9. Прогон 70450 → 70532 (2026-10-28, локально)

`python scripts/ota_staging_verify.py run <out> --base 1.60.1.70450 --candidate 1.60.1.70532`
(без `--revision`) на macOS (Apple Silicon), Python 3.12, Gradle/JDK проекта; checkout — ветка этого
PR на коммите `2c21031`, чистое рабочее дерево. 2026-10-28 09:56:59Z → 10:00:27Z, **PASS, exit 0**.
Прогон workflow на GitHub указывается в описании PR и в этот отчёт не входит.

| | |
| --- | --- |
| Ревизия | пусто → HEAD `origin/main` = `b6959169197bdffe1175832cd5f13d8d6206f582` (merge PR #97) |
| Base / candidate | `1.60.1.70450` / `1.60.1.70532` |
| Релизы | `1.60.1.70450-r1`: 397 файлов, 5 077 048 байт; `1.60.1.70532-r1`: 397 файлов, 5 078 690 байт (SHA-256 манифестов зависят от `createdAt` прогона) |
| Staging-ключ | `tf-staging-local-042b8ec5`, создан прогоном и удалён в его конце |
| Клиент | `OtaCandidateStagingTest`: 6/6 PASS, 202 с |

Все 21 автоматическая проверка — PASS (R1, P1–P5, K0–K1, C1–C6, N1–N4, G1–G2); M1 и M2 — NOT RUN.

* **Publisher.** G1–G8 на staging для обоих релизов — PASS; production keyring отвергает
  staging-каталог (`UNTRUSTED_SIGNER`); G6-попытка `1.60.1.70532-r3` отвергнута (`validate` и
  `stage` exit 1, store не изменился); `verify-live` после каждой из 4 публикаций — exit 0.
* **Сеть.** По HTTPS пришли манифест и 391 файл, 5 044 378 байт (dataset 10, localization 9,
  tooltips 9, visuals 363); 392 blob-запроса. Из установленного 70450 скопировано 6 файлов, из
  bundle — 0. Установленная копия — 397 файлов; путь, SHA-256 и размер каждого равны собранному
  релизу (K1) и, по P2, закоммиченным данным SHA.
* **Сохранённые билды** на 70450 — Mage (6 узлов), Priest (3), Warrior (1) — открываются на 70450 с
  теми же аллокациями после активации и после холодного старта; повторная проверка после рестарта —
  1 запрос каталога, 0 blob-ов.
* **Отрицательные сценарии.** Подпись каталога → `SignatureInvalid`; манифест →
  `ManifestHashMismatch`; blob `dataset/forever/1.60.1.70532/druid.json` → `BlobHashMismatch`. Во всех
  трёх активный релиз, продукт и библиотека не изменились, следующая чистая проверка установила
  70532. Обрыв после 196 blob-ответов → `Network`; после рестарта сохранено 195 проверенных
  загрузок, докачано 196 файлов, ни один не скачан повторно. Без сети → `Network`, store побайтно
  тот же, затем 70532 установлен.

**Наблюдение: почему 391 файл, а не только данные.** У закоммиченного 70532 353 из 359 иконок
отличаются от иконок 70450 байтами PNG при одинаковых пикселях (проверено декодированием: 0
отличий); байт в байт совпадают 6 иконок. Поэтому обновление 70450 → 70532 скачивает около 4,9 МБ
перекодированных иконок, хотя по смыслу они те же. Данные в этом PR не менялись.

**Исторический replay 70374 → 70450 после обобщения harness:**
`python scripts/ota_historical_replay.py run <out> --skip-prepare --no-production-guard` —
клиент 13/13 PASS (`OtaHistoricalReplayTest` и `OtaStagingE2ETest`, 402 с), D1–D3, S1, S3–S5,
C1–C10, N1–N7, G1 — PASS; P0, S2 (подготовка через wago.tools) и G2 (чтение production) — NOT RUN
по флагам, поэтому итог прогона — NOT RUN (exit 3). Полный replay с подготовкой здесь не
повторялся.

## 10. Ограничения

1. **JVM, не устройства.** Android (эмулятор, физическое устройство) и iOS (симулятор,
   устройство) для кандидата — NOT RUN (§6); iOS-симулятор проверяется в CI только на
   исторической паре 70374 → 70450 (M5.IOS.2).
2. **Локальный стенд, не удалённый staging.** CDN, кеши и публичный TLS не проверяются.
3. **Инструменты из checkout workflow.** Данные релизов и bundle берутся из зафиксированного SHA,
   а код клиента и скриптов — из ветки, на которой запущен workflow (`facts.tooling.head`).
4. **Хранилище библиотеки в памяти** (§3.1): платформенные `BuildLibraryStorage` здесь не
   участвуют, чтобы не трогать пользовательские данные.
5. **70532 и live-сборка.** Подтверждение владельца относится к бете; версия серверов после
   запуска не установлена. Approval-записи `1.60.1.70532-r1` нет; staging PASS её не заменяет.
