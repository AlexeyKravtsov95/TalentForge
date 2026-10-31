# Автоматическое предложение OTA-релиза (PR B / Task 3)

Это подготовка **draft PR**, не публикация. Signer, S3 credentials, загрузка production blobs,
запись каталога и автоматический merge отсутствуют. G1–G8 и решения OWNER не меняются.
`1.60.1.70532-r1` повторно не предлагается.

## Обычный путь

1. Владелец проверяет и вливает data PR. `CI` успешно завершает `checks` для точного push SHA
   на main. `OTA release preparation` запускается через `workflow_run` автоматически.
2. Python проверяет API metadata текущего workflow и CI: repository/head repository, точные
   path/event/branch, run/attempt, full SHA, main ancestry, успешный обязательный job.
   Код checkout закреплён на `github.sha`, входной artifact не исполняется.
3. Из committed tree **этого SHA** выбирается единственный нормализованный Forever build,
   который новее active, отсутствует в подписанном каталоге и ещё не имеет merged request.
   Свободный PR body и freshness issue не выбирают релиз. Несколько кандидатов — BLOCKED.
   Если новых данных нет, результат NO_ACTION, staging/package не запускаются.
4. Подписанный production-каталог и все его manifests/files проверяются reference verifier.
   Активные releaseId и manifest SHA фиксируют base. Запускается прежний
   `ota-staging-verify.yml` с точными source/base/candidate; затем прежний
   `ota-release-package.yml` с конкретными staging run/attempt. Оба используют только
   локальный staging stand/TEST keys и штатный publisher builder.
5. Проверяются metadata каждого completed attempt, единственный непосроченный artifact,
   digest ZIP, closed index, воспроизводимость, staging binding и installed-files evidence.
   `notExecuted` обязан присутствовать и быть null; все шесть JVM tests и K0/K1/C1–C6/N1–N4
   должны PASS. Cached, skipped, failed, чужие и неоднозначные результаты — BLOCKED.
6. Отдельный job в `ota-preparation` повторно получает exact package по id, проверяет
   provenance из PR A и live base, затем App installation token создаёт:
   - ветку `ota-release/<releaseId>` от pinned source commit;
   - `docs/ota/release-requests/<releaseId>.json` (строгие машинные pins);
   - `docs/ota/releases/<releaseId>.md` (approval template с незаполненными OWNER-полями);
   - один draft PR в main с source/manifest/base pins, runs, gates, diff и объёмом загрузки.

Diff и CI gates в пакете по-прежнему используют base build, воспроизведённый из source SHA:
это явно отмечено в template/report. Окончательный publisher preflight против live catalog
остаётся отдельным этапом; этот PR его не реализует. Подготовка не устанавливает G7 evidence
и не принимает G5/G7 от имени владельца.

## Повторы и отказ

Корреляция child runs вычисляется из source SHA, target release и base release/manifest pin.
GitHub возвращает id нового dispatch (`return_run_details=true`); повтор прежде всего ищет
сохранённый request/точный package run. Run-name используется только для discovery: доверие
даёт последующая проверка API metadata, attempt и bytes. Неполный listing — отказ.

Повтор не меняет createdAt или manifest SHA и не обновляет release branch/PR body. Если job
упал между package upload и открытием PR, следующий запуск использует прежний artifact.
Просроченный/удалённый artifact — BLOCKED, пересборки под старый approval нет. Ответ на
неоднозначный dispatch/неуспешный child run не повторяется автоматически внутри запуска:
сначала диагностика и проверка сохранившегося run, затем штатный retry.

Ручной commit, изменённые request/template, закрытый PR, новый source или новый live base
дают явный отказ. Owner edits не затираются. Для замены source/base/package требуется явное
решение владельца о supersession старого draft и **новое review**; автоматика не удаляет
старые branches/artifacts и не переносит approval. Переподпись с тем же active/manifest pin
не меняет base.

`workflow_dispatch` — только повтор существующего CI pin, например:

```bash
gh workflow run ota-release-prepare.yml --ref main -f ci_run_id=<CI_RUN_ID> -f ci_attempt=<ATTEMPT>
```

Перезапуск с новым attempt CI того же SHA сохраняет уже созданный пакет. Историю matching
runs/actions artifacts сохраняют: удаление/истечение не является разрешением скрыто заменить
bytes. Pending preparation runs сериализованы одной concurrency group без cancel-in-progress;
долговечная production queue/reconciliation относится к Tasks 4–6.

## Разовая настройка GitHub App владельцем

До этой настройки автономная цепочка **не готова**: proposal job завершится `BLOCKED:
APP_CONFIG_MISSING`, а не зелёным «PR создан». В разработке App/настоящие ключи не создаются,
не запрашиваются и не пересылаются в чат.

1. В GitHub Developer settings создать App для этой автоматизации. Webhook не нужен.
   Repository permissions: **Contents — read/write**, **Pull requests — read/write**;
   обязательный Metadata read-only. Actions, Secrets, Workflows, Administration и прочие
   дополнительные permissions не выдавать.
2. Установить App через **Only select repositories → TalentForge**. Для каждого token action
   дополнительно указаны owner `AlexeyKravtsov95`, repositories `TalentForge`, contents/PR write.
   Release proposer проверяет, что installation token видит ровно TalentForge.
3. Создать GitHub Environment **ota-preparation**, разрешить deployments только с main.
   Для полностью автоматических предложений не добавлять required reviewer этого environment:
   OWNER review будет на data/release PR. В этом environment:
   - variable `OTA_APP_CLIENT_ID` — Client ID App;
   - secret `OTA_APP_PRIVATE_KEY` — приватный PEM App, добавленный владельцем через GitHub UI.
   Это **не** OTA production signing key. Никакие S3/signing secrets сюда не добавлять.
4. Workflow `ota-prepare.yml` использует тот же App только в job `propose` для data PR.
   Чтение artifacts выполняется штатным GITHUB_TOKEN. События App-created PR запускают CI
   без прежнего close/reopen. App token автоматически отзывается в post step action и не
   передаётся staging/package jobs, artifacts или будущему signer.
5. `GITHUB_TOKEN` нового read-only preparation job имеет `actions: write` только потому,
   что dispatch existing workflows требует этого permission. App Actions permission не нужен.
   Остальные jobs имеют contents/actions read; producer workflows не получают App key/token.
6. Проверить первый реальный **следующий** data merge. Текущий 70532 не использовать для
   переиздания. Настоящий GitHub dispatch/App/real JVM stand/cloud origin smoke не выполняется
   при offline unit validation: это отдельная проверка после review/merge и настройки.

Actions в jobs с App key закреплены полными SHA; installation token не передаётся в command
arguments и не выводится. Компрометация trusted main job с доступом к App key всё равно даёт
его repository write-права: main-only environment не заменяет review изменений main.

Sources: [GitHub workflow dispatch API](https://docs.github.com/en/rest/actions/workflows#create-a-workflow-dispatch-event),
[dispatch run id response](https://github.blog/changelog/2026-02-19-workflow-dispatch-api-now-returns-run-ids/),
[App token action](https://github.com/actions/create-github-app-token/tree/64ab729a6294000f5aa4a4bc0b73d1f32bc45510),
[App permissions](https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/choosing-permissions-for-a-github-app).
