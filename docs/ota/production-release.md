# Production OTA-релиз: пакет из CI и публикация владельцем (M5.OTA.PROD.4)

Документ для **владельца проекта**. Он описывает, как выпустить новые данные в существующий
production OTA без пересборки Android и iOS. Установленные приложения `1.0.0 (4)` уже работают с
production OTA ON и сейчас получают `1.60.1.70450-r1`.

Процесс разделён на две части:

* **CI готовит пакет релиза.** Workflow **OTA production release package** собирает релиз
  существующим publisher, привязывает к нему пройденную staging-проверку и выкладывает
  самодостаточный пакет как артефакт. Production signing key и S3-ключа в CI нет. CI ничего не
  публикует, production не читает и не пишет, approval не оформляет.
* **Владелец публикует пакет.** Помощник `scripts/ota_production_release.py` оборачивает команды
  publisher'а (`promote`, `attach-signature`, `verify-live`), не отключая ни одной их проверки.
  Он работает с AWS-профилем `talentforge-production`, а подпись делает владелец офлайн, как
  раньше ([production-deployment.md](production-deployment.md) §4–§5).

**Автоматической публикации нет.** Её не запускают ни freshness-сигнал, ни merge, ни успешный
staging, ни готовый пакет. Каждая запись в production — отдельная команда владельца с
`--execute`.

---

## 1. Порядок: собрать manifest → approval → merge → публикация

| шаг | кто | что меняется |
| --- | --- | --- |
| 1. Пакет релиза | CI (`workflow_dispatch`) | ничего: артефакт с точными байтами `release.json` и всеми blob'ами |
| 2. Approval record | владелец, PR в `main` | только `docs/ota/releases/<releaseId>.md` |
| 3. `check`, `prepare --execute` | владелец | в бакет загружаются **immutable blob'ы**; каталог не меняется, клиенты ничего не видят |
| 4. Офлайн-подпись | владелец, офлайн-машина | ничего: `.der` над PAE-файлом |
| 5. `publish --execute` | владелец | `catalog.json`: **активация**, после неё клиенты видят релиз |
| 6. `verify` | владелец | ничего: проверка live и preflight |

**Почему merge approval record не меняет утверждённый хеш.** Манифест закрепляет коммит данных
(`provenance.repositoryCommit`), а не HEAD `main`. После merge документации коммит данных
остаётся предком `main`, поэтому G1 проходит, G8 воспроизводит те же байты из того же коммита, а
`Manifest SHA-256` в approval record совпадает с пакетом. Пакет **не пересобирается**: новая
сборка получила бы новый `createdAt` (и, возможно, новый коммит), то есть другой хеш, и
утверждённая запись перестала бы подходить. Если пакет потерян, см. §7.

## 2. Workflow «OTA production release package»

Файл: [`.github/workflows/ota-release-package.yml`](../../.github/workflows/ota-release-package.yml).
Скрипт: [`scripts/ota_release_package.py`](../../scripts/ota_release_package.py). Формат пакета:
[`src/talent_data/ota/release_package.py`](../../src/talent_data/ota/release_package.py).

| вход | обязателен | по умолчанию | смысл |
| --- | --- | --- | --- |
| `build` | да | — | публикуемая сборка, например `1.60.1.70532` |
| `release_revision` | да | `1` | `n` в `<build>-r<n>`; `1` для первого релиза сборки |
| `source_revision` | нет | пусто = HEAD `main` | коммит или ref на `main`, из которого собирается релиз |
| `staging_run` | да | — | id прогона **OTA staging verification** с PASS для этой сборки |
| `base` | да | `1.60.1.70450` | сборка, на которой сейчас работают production-клиенты: с ней сравнивают diff и CI-гейты |

Настройки:

* Права только `contents: read` и `actions: read` (метаданные и артефакт staging-прогона). Секретов
  нет, S3- и signing-ключей нет. Push, PR и merge workflow не делает.
* Checkout с `fetch-depth: 0` и `persist-credentials: false`.
* Артефакт `ota-release-package-<run id>` хранится **90 дней**. Сохраните локальную копию пакета:
  approval record утверждает именно его байты.
* Pull request, меняющий инструмент пакета, запускает первую поставку (`1.60.1.70532` поверх
  `1.60.1.70450`, staging-прогон `40691815787`) как самопроверку. Артефакт этого staging-прогона
  хранится 30 дней, до 2026-11-27. После этого значение по умолчанию для pull request нужно
  заменить на более новый staging-прогон.
* Итог зелёный только при коде `0` (`READY FOR REVIEW`): пакет доказан, STOP-гейтов нет, staging
  привязан. `1` — FAIL, `3` — нет staging evidence, `2` — ошибка входов. Пакет выкладывается в любом
  случае, для разбора.

### 2.1 Что делает прогон

1. **Фиксирует SHA один раз.** Ревизия превращается в полный SHA на `origin/main`. На этом SHA
   должны быть закоммичены данные `build` и `base`.
2. **Собирает релиз** командой `build-release` с этим SHA и одним `createdAt` (время запуска).
   Точные байты `release.json` и все blob'ы попадают в пакет.
3. **Гейты G1–G8 и diff.** Production-канал, сравнение с `base`, собранным из того же SHA. CI не
   читает production, поэтому это заменитель live-релиза. Окончательный прогон против live
   выполняет `check` владельца (§4). Approval record из `origin/main` учитывается, только если он
   закрепляет **именно эти** байты.
4. **Привязывает staging** (§3).
5. **Пишет пакет и перепроверяет его:** каждый файл в `package.json` с SHA-256 и размером. Пакет
   затем заново загружается и доказывается.

### 2.2 Содержимое пакета

```
<releaseId>/
  package.json                  индекс: идентичность, коммит, createdAt, хеш манифеста, staging,
                                SHA-256 и размер каждого файла пакета
  release.json                  точные байты манифеста (их пинит approval record)
  blobs/sha256/<64 hex>         все файлы релиза по хешам
  approval-record.template.md   черновик docs/ota/releases/<releaseId>.md с пинами
  report/conditions.md|.json    условия публикации: MET / FAIL / MISSING / OWNER
  report/gates.json             G1–G8 из CI
  report/diff.txt               ota report против base
  report/staging-binding.json   результат привязки staging
  staging/                      копии staging-отчёта, staged-манифеста, evidence клиента, прогона
  publish/                      рабочий каталог владельца (создаёт помощник; в индекс не входит)
summary.md                      то же, что job summary
```

Пакет **закрыт**: помощник отказывается работать при отсутствующем, лишнем или изменённом файле,
при blob'е, который не хешируется в своё имя, и при наборе blob'ов, отличном от манифеста.
Недостающие байты не докачиваются и не пересобираются молча.

## 3. Привязка staging evidence

Staging PASS переносится на релиз только по проверяемым данным, не по номеру сборки:

* прогон относится к `.github/workflows/ota-staging-verify.yml` этого репозитория и завершён со
  статусом `success`;
* отчёт принадлежит этому прогону (`facts.tooling.head` равен коммиту прогона), его `verdict` —
  `PASS`, `candidateBuild` совпадает со сборкой пакета;
* staged-манифест закреплён в отчёте. Его game, сборка, data level и **файлы** (путь, SHA-256,
  размер) равны файлам пакета;
* копия, которую установил клиент (`installedFiles` теста `theCandidateReplacesTheBaseOverHttps`),
  равна файлам пакета, а запрошенная пара — `base → build`.

Режимы: `sameCommit` — staging собран из того же коммита; `equalFiles` — из другого коммита, но
все файлы доказанно равны. Если файлы различаются, привязка получает FAIL: прежний PASS новому
релизу не приписывается. Манифесты staging и пакета различаются `createdAt`, поэтому их хеши
разные — это ожидаемо, привязываются файлы.

Помощник владельца заново доказывает привязку по копиям в `staging/`. Дополнительно он требует,
чтобы `base` staging-прогона был сборкой активного production-релиза: проверялось обновление
именно с того, на чём работают клиенты.

**Первая поставка:** `1.60.1.70532` поверх `1.60.1.70450`. Staging-прогон
[40691815787](https://github.com/AlexeyKravtsov95/TalentForge/actions/runs/40691815787) запущен из
`8108b2fea85eb2fae20466af94c2df651495b1f2`: JVM-клиент 6/6 PASS, установленные 397 файлов совпали
с релизом. Локальная репетиция пакета от HEAD `main` (`8108b2f`) дала `sameCommit`, 397 файлов,
5 078 690 байт, G7 = FLAG, остальные гейты — PASS.

## 4. Помощник владельца

Подготовка рабочей машины (один раз):

```bash
git switch main && git pull
pip install -e '.[ota,s3]'
aws configure --profile talentforge-production   # если ещё не настроен (production-deployment §3.5)
```

| команда | пишет | что делает |
| --- | --- | --- |
| `check <пакет>` | ничего | пакет цел и равен `build-release` на его коммите; staging заново привязан; approval record из `origin/main` закрепляет этот манифест; staging base = live-сборка; `ota promote` (dry run) и `ota report` против live |
| `prepare <пакет>` | ничего | то же и план записи |
| `prepare <пакет> --execute` | blob'ы в бакет, `publish/` | `ota promote --execute`: загрузка immutable blob'ов с read-back, затем `pending-production.payload.json` (+ `.meta.json`) и **точный PAE-файл** `pending-production.pae.bin`. Каталог не меняется |
| `publish <пакет> --signature <der> --key-id tf-prod-2026a` | ничего | DER-подпись проверяется по закоммиченному keyring, payload — наследник live, approval record заново читается из `origin/main`; `ota attach-signature` (dry run) |
| `publish … --execute` | `catalog.json` | `ota attach-signature --execute`; затем live перечитывается и исход классифицируется (§6) |
| `verify <пакет>` | `publish/preflight.json` | `ota verify-live` и `scripts/ota_production_preflight.py --expect published --keyring …` |

Свойства:

* По умолчанию — dry run. Запись в production происходит только с `--execute`.
* Approval record берётся **из `origin/main`**. Каждая команда сначала сама делает
  `git fetch origin main`; если fetch не удался, это отказ. Локальный незакоммиченный файл
  production не утверждает.
* Запись должна содержать `Release`, `Manifest SHA-256` и `Source commit` этого пакета и ни одной
  незаполненной строки `<OWNER: …>`. Это строже publisher'а, который проверяет пин, только если
  тот указан. Строка `Accepted gates` обязательна: пусто или только G5/G7; G7 — только с
  `Live-build evidence`.
* `publish` перечитывает запись из `origin/main` **непосредственно перед активацией** (и в dry
  run). Если запись удалили или изменили после `prepare`, это отказ, каталог не меняется. Когда
  на `main` снова окажется одобряющая запись, та же подпись подойдёт, потому что payload не
  менялся. Копия в `publish/approvals/` только передаёт байты publisher'у и разрешением не
  является: каждая команда перезаписывает её из `origin/main`. Повторный `publish` уже
  опубликованного payload записи не требует и ничего не пишет.
* Приватный ключ помощник не читает. Ключи с id вне `tf-prod-*` для production отклоняются.
* `--store`, `--keyring`, `--releases-docs`, `--now` — только для локальной репетиции
  (`--store DIR`). С production их сочетать нельзя.
* Коды выхода: `0` — готово, или уже опубликовано; `1` — отказ (этот шаг ничего не записал); `2` —
  ошибка аргументов; `3` — **AMBIGUOUS**, исход записи требует внимания владельца (§6).
* Журнал действий — `publish/journal.jsonl`.

## 5. Пример полного процесса: `1.60.1.70532-r1`

1. **Пакет.** Actions → **OTA production release package** → Run workflow: `build`
   `1.60.1.70532`, `release_revision` `1`, `source_revision` пусто, `staging_run` `40691815787`,
   `base` `1.60.1.70450`. Или из терминала:

   ```bash
   gh workflow run ota-release-package.yml -f build=1.60.1.70532 -f release_revision=1 \
       -f staging_run=40691815787 -f base=1.60.1.70450
   ```

   Workflow появится в Actions после merge этого PR в `main`.

2. **Скачать и сохранить пакет** (`<run>` — id прогона пакета):

   ```bash
   gh run download <run> -n ota-release-package-<run> -D ~/tf-ota/<run>
   P=~/tf-ota/<run>/1.60.1.70532-r1
   ```

3. **Просмотреть**: `$P/../summary.md`, `$P/report/conditions.md`, `$P/report/diff.txt`,
   `$P/report/gates.json`, `$P/report/staging-binding.json`. Затем:

   ```bash
   python scripts/ota_production_release.py check $P
   ```

   До merge approval record `check` останавливается на шаге «no approval record». Пакет, его
   воспроизводимость и staging к этому моменту уже проверены.

4. **Approval record.** Скопируйте `$P/approval-record.template.md` в
   `docs/ota/releases/1.60.1.70532-r1.md`. Заполните все строки `<OWNER: …>`, откройте PR и влейте
   его. Решение по G5/G7 принимает только владелец. Для `1.60.1.70532` подтверждено лишь то, что
   эта сборка работала на серверах **в период беты**
   ([auto-prepare.md](auto-prepare.md), «Live-build evidence: 1.60.1.70532»). Это не подтверждение
   сборки после запуска и не готовый production approval. Если других оснований нет, так и
   напишите в строке evidence.

5. **Проверка против live и подготовка** (после merge):

   ```bash
   python scripts/ota_production_release.py check $P              # готовность, diff против live
   python scripts/ota_production_release.py prepare $P --execute  # ≈ 8–9 мин: blob'ы, payload, PAE
   ```

   Команда печатает `PAE SHA-256` и команды подписи. Клиенты пока ничего не видят.

6. **Офлайн-подпись** — отдельно, на офлайн-машине. Перенесите туда только
   `$P/publish/pending-production.pae.bin`:

   ```bash
   shasum -a 256 pending-production.pae.bin     # равно напечатанному PAE SHA-256
   openssl dgst -sha256 -sign tf-prod-2026a.key.pem -out pending-production.sig.der pending-production.pae.bin
   ```

   Верните `pending-production.sig.der`, например в `$P/publish/`.

7. **Активация:**

   ```bash
   python scripts/ota_production_release.py publish $P \
       --signature $P/publish/pending-production.sig.der --key-id tf-prod-2026a            # dry run
   python scripts/ota_production_release.py publish $P \
       --signature $P/publish/pending-production.sig.der --key-id tf-prod-2026a --execute
   python scripts/ota_production_release.py verify $P
   ```

   Ожидаемо: live sequence 2, активен `1.60.1.70532-r1`, оба релиза `OK`, preflight `result: OK`.

8. **Архив опубликованного конверта** (production-deployment §5.6):

   ```bash
   curl -fsS https://ota.mythweld.com/v1/channels/production/catalog.json \
       -o ~/talentforge-ota-archive/production-catalog-seq2.json
   ```

Клиенты увидят обновление не позже чем через `max-age=300` (CDN и устройства).

## 6. Повторный запуск и неоднозначный исход

| ситуация | что делает помощник | что делать |
| --- | --- | --- |
| `prepare --execute` прервался (сеть, VK не принял соединение) | код 1; каталог не тронут; загруженные blob'ы остаются (immutable, не видны клиентам) | запустить ту же команду: присутствующие blob'ы пропускаются |
| `prepare` повторно при готовом payload | payload не меняется, команды подписи печатаются снова | подписать (или использовать уже сделанную подпись) |
| live-каталог изменился после `prepare` (rollback, resign, другой publisher) | `publish` отказывает **до записи**: payload устарел | `prepare --replace --execute` (старый payload уходит в `publish/superseded/`), **новая** офлайн-подпись, `publish` |
| подпись чужим ключом, другим ключом или над другими байтами | отказ до записи | подписать именно `pending-production.pae.bin` ключом `tf-prod-2026a` (или `tf-prod-2026b` с `--key-id tf-prod-2026b`) |
| `publish --execute` после успешной публикации | live = этот payload: запись не выполняется, только `verify-live` | ничего |
| запись сообщила ошибку, а live = этот payload | код 0, «published» | ничего; `verify` |
| запись не прошла, live не изменился | код 1, «not written» | устранить причину и повторить с той же подписью (base не изменился) |
| после записи live — **другой** каталог, или live не читается | код **3, AMBIGUOUS**; в журнале `ambiguous` | остановиться: не готовить и не подписывать новое. `verify`, затем production-deployment §9.2–§9.3. Возможно, параллельно работал другой publisher; клиенты не принимают меньший sequence или тот же sequence с другими байтами |
| второй запуск `prepare --execute` или `publish` на этом пакете | отказ: блокировка `publish/publisher.lock` | дождаться первого |

**Один publisher за раз.** Блокировка действует только на одной машине. VK Cloud игнорирует
условные записи, поэтому межмашинное правило остаётся организационным
([publisher-runbook.md](publisher-runbook.md) §6.3): публикует только владелец, с одной машины.
Остальную защиту дают перечитывание live-sequence непосредственно перед `PUT` (publisher) и
read-back после него (publisher и помощник).

## 7. Если пакет потерян или истёк

Новый прогон workflow даёт новый `createdAt`, то есть **другой** `Manifest SHA-256`. Approval
record старого пакета к нему не подходит: помощник и publisher откажут. Не правьте `createdAt`
вручную и не пересобирайте пакет под старую запись. Нужен новый пакет, новый или обновлённый
approval record (через PR) и новая подпись. Если blob'ы старого пакета уже загружены, они общие:
файлы с теми же байтами повторно не загружаются.

## 8. Проверки и тесты

* `tests/unit/ota/test_ota_release_package.py`: пакет равен `build-release`; гейты и условия,
  включая запись на `main`, закреплённую за другими байтами (G7 остаётся FLAG); шаблон
  (пины заполнены, решения — `<OWNER: …>`); девять видов повреждения пакета; привязка staging
  (`sameCommit`, `equalFiles`, другие файлы, verdict, сборка, чужой прогон, workflow,
  репозиторий, неуспешный прогон, установленная копия, запрошенная пара, незакреплённый
  манифест); пакет без staging (код 3) и с чужим staging (код 1); workflow (входы, права, без
  секретов и публикации, `Final status` в bash).
* `tests/unit/ota/test_ota_production_release.py` на локальном store с production-подобными
  ключами `tf-prod-test-*` из публичных seed'ов (только внутри теста): полный путь и повтор без
  лишнего sequence; повреждённый пакет; approval record с чужим пином, отсутствующий,
  незаполненный и принимающий STOP-гейт; чужая подпись, подпись другим ключом и над другими
  байтами; устаревший payload и его замена; блокировка; каталог другого publisher'а во время
  записи (код 3); прерванная загрузка и её продолжение без повторов; запись, сообщившая ошибку, но
  выполненная; live, который не читается после записи (код 3).

Реальная публикация этими ключами не выполнялась. Production в рамках этой задачи не менялся.
