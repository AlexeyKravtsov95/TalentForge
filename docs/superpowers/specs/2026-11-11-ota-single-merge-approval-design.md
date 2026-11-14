# OTA: один MR, его слияние владельцем разрешает production

Дата: 2026-11-11. Статус: дизайн для согласования, реализация не начата.
База: `5050d0d8f9c588333f53a835a581cc4c7b553799`.

## Решение владельца и результат для пользователя

Владелец явно выбрал:

1. Единственный MR — первый MR с новыми данными, как #138 для 70888.
2. Слияние этого MR владельцем означает «всё ок, разрешаю production».
3. Staging и точный пакет готовятся **до** слияния.
4. Второго релизного MR, отдельного файла с ручным G7 и повторного подтверждения нет.

Бот открывает один draft MR сразу после подготовки кандидата. В MR видны сборка,
изменения, результаты проверок, размер обновления и явное правило: «Слияние владельцем
разрешает публикацию именно этого пакета для live-серверов». Внутренние обозначения
гейтов остаются в техническом отчёте, а не отдельным ручным шагом. После успешных CI,
staging и упаковки бот переводит его в ready. Владелец делает одно действие — merge.
Далее автоматически выполняются оставшиеся production-проверки, публикация и полная
публичная проверка. Сообщения владельцу — готовность MR, завершение либо существенная
ошибка/действительно необходимое действие; промежуточные здоровые состояния тихие.

## Почему текущий путь заменяется

Сегодня `ota-prepare.yml` / `prepare/propose.py` открывают data MR. После его слияния
`ota-release-prepare.yml` / `ota/automation/prepare.py` готовят staging/package и
открывают второй MR с request JSON и незаполненным approval MD. Слияние такого
шаблона не разрешает G7: probe возвращает `WAIT / APPROVAL_PENDING`.

Для 70888 пришлось дополнительно сливать #140. После его слияния в 13:59:37 UTC
публичный proof завершился в 14:13:28.236548 UTC: 831.236548 с, **MISS** относительно
600 с. Публикация подтверждена независимой проверкой всех 403 файлов; этот релиз уже
завершён и не переиздаётся ради новой схемы.

## Рассмотренные подходы

| Подход | Цена/выигрыш | Решение |
| --- | --- | --- |
| Один data MR; staging/package до merge, merge разрешает готовый пакет | Требует безопасной подготовки из проверенного candidate ref; проверки доступны до решения, после merge остаётся короткий путь | Выбран владельцем |
| Один data MR; вся подготовка после merge | Меньше переноса этапов, но CI/staging/package входят в ожидание после merge | Не выбран |
| Сохранить второй релизный MR и только автоматически заполнить G7 | Сохраняет второе ручное действие | Не соответствует запросу |

Ни один вариант не требует, чтобы бот сливал MR или писал коммиты напрямую в main.

## Жизненный цикл одного MR

1. **DISCOVERED / DRAFT.** Существующее обнаружение и проверка данных формируют bot-only
   candidate commit `D`. Источник — только его committed bytes. MR привязан к одному
   game/build/revision и версии политики `owner-merge-v1`.
2. **PREPARING.** Доверенное tooling из main проверяет происхождение кандидата, выполняет
   staging/client scenarios и собирает frozen manifest/blobs/archive из `D`. На этом этапе
   production signing/S3 credentials недоступны. Пакет ещё не разрешён к production.
3. **BOUND.** Бот fast-forward добавляет к тому же MR только metadata commit `P` с exact
   request и декларацией режима `owner-merge-v1`. `D` остаётся sourceCommit пакета;
   approved payload не пересобирается из `P`. Correlation, createdAt и manifest hash заморожены.
4. **READY.** Полный PR CI проверяет окончательный head `H` после добавления metadata.
   Staging/package proofs относятся к `D`, metadata — к этому exact archive. Все нужные
   checks зелёные; на MR видны точные pins и правило merge-as-approval. Только теперь ready.
5. **MERGED / AUTHORIZED.** GitHub API подтверждает merge MR разрешённым владельцем,
   exact head `H`, base main, тот же repository/head-repository, согласованный merge tree,
   policy marker и package binding. Серверный `merged_at` становится t0.
6. **PUBLISH / VERIFIED.** Доверенное main tooling повторно проверяет authorization,
   actual live base, пакет, G1–G8, current main и журнал. После единственной безопасной
   записи каталога проверяет подпись и все публичные файлы на стабильном payload.

Если проверки не завершились или завершились отказом, MR остаётся draft/blocked с точной
причиной. Ручное снятие draft само по себе разрешения не даёт: production проверяет proofs.

## Доверие к candidate до merge

Нельзя запускать произвольный код MR с credentials. Подготовка вызывается только
доверенным default-branch workflow, который независимо перечитывает GitHub metadata,
candidate artifact и committed tree. Скрипты/зависимости/Actions берутся из main.

Допустим только generated candidate того же репозитория: ожидаемый App/bot, единственный
MR, правильные base/branch/game/build, origin artifact/run/attempt, полный безопасный diff
и обычные файловые modes. Политика разрешённых data/derived путей следует существующему
candidate validator; произвольные code/workflow/config/key/schema edits запрещены.
Fork, другой actor, смешанный MR, недоказанная история, path traversal, symlink, неверный
тип/неизвестное поле или изменённые pins дают типизированный отказ.

Существующий G1 в `gates.py` умеет проверять commit относительно заданного ref, но текущие
staging/package entrypoints разрешают только main. Добавляется **явный preparation-only
контекст** с проверенным candidate ref. Его доказательство утверждает «committed candidate
на проверенном ref», а не «уже на main». Этот контекст никогда не принимается publisher
как production G1. После merge G1 заново доказывает ancestry `D` в актуальном main.
Никакой STOP не принимается owner merge и не превращается в PASS настройкой публикации.

Добавляется строгий внутренний `candidate-binding.v1` proof: repository, policy version,
MR number, candidate source/tree, expected head/base, trusted tooling/workflow/run/attempt,
staging/package identities и точные hashes. Он хранится в пакете как attachment и в
проверяемой immutable correlation metadata. Не дописывать неизвестные поля в существующие
v1 модели. Публичные manifest/catalog/DSSE/data schemas не меняются.

В этом режиме `workflow_run.head_sha` остаётся SHA доверенного main tooling `T`, а
`sourceCommit` пакета — candidate SHA `D`. Нельзя подменять одно другим ради старой
проверки равенства. Candidate-mode provenance отдельно связывает exact run/attempt/jobs,
переданные source inputs, `candidate-binding.v1` и фактические bytes из `D`.
Для legacy package сохраняются прежние правила равенства; новый контекст не делает
произвольный workflow или чужой source допустимым.

## Как merge заменяет ручной G7

`owner-merge-v1` — отдельный явный режим авторизации, а не автоматическое заполнение
старого шаблона ложным личным свидетельством. До merge запись выражает условие будущего
одобрения; после merge authorization строится из фактов GitHub и закреплённых pins.

Разрешённый actor первоначально один: `AlexeyKravtsov95`. Используется `merged_by`,
не автор MR, автор коммита или произвольный комментарий. Merge ботом/другим человеком,
закрытие MR без merge, чужой head, пропавший proof, squash/rebase вместо ожидаемого merge
не авторизуют публикацию. V1 использует обычный merge commit, чтобы сохранить ancestry `D`.
В MR заранее явно указано это требование; обнаруженный неподдерживаемый способ даёт ошибку.

G7 фиксирует **принятие владельцем по правилу merge**, со ссылкой на MR, actor, merge SHA
и серверное время. Бот не утверждает, что владелец лично играл или что источник версии
стал Proven. G5 принимается этим действием только для FLAG, который уже явно показан
в готовом MR и привязан к frozen gate report. Новый G5 FLAG после изменения live base
не принимается молча. G1–G4/G6/G8 STOP остаются безусловным отказом.

Точный request JSON остаётся в main вместе с первым MR. История его bytes и conditional
authorization record проверяется так же строго, как legacy request/approval. Immutable
package tag/archive сохраняются. После merge бот не делает дополнительный commit в main
и не создаёт второй MR. Journal сохраняет request digest и проверяемый merge authorization.

## CI после merge

Полный CI обязателен на финальном PR head. После merge разрешён короткий
`validated-merge` путь main CI только при одновременно доказанных условиях:

- allowed bot data MR, разрешённый owner, обычный merge в тот же main;
- complete-success exact PR workflow/run/attempt/jobs, правильный repository/headrepo;
- merge commit tree **побайтно равен** tree проверенного финального PR head;
- tooling/workflows/dependencies на ветке не менялись; diff только допустимых candidate
  data/derived/metadata путей; source `D` и package pins те же;
- actual base parent совпадает с базой, для которой подготовлен финальный proof.

Main CI всё равно создаёт собственный completed-success `checks` и machine-readable
evidence о reused validation. Production не принимает PR CI как main/push CI: он проверяет
новый main CI и всю цепочку reuse. Нельзя считать обычный metadata-CI-предок полным PR CI.
Любое несовпадение ведёт к полному CI или явному отказу, никогда к бездоказательному shortcut.

Пока main CI не завершился, event/watchdog ждёт. Потерянный event подхватывает существующий
лёгкий watchdog; daily renewal/global health остаются отдельными. Нет нового runner/server.

## Изменения, отзыв и гонки

- Любое изменение source/data после фиксации пакета делает старый READY proof недействительным.
  Старый пакет/createdAt/tag не переписываются. Новый кандидат получает отдельную identity;
  UI снимает ready и явно показывает, какой пакет предлагается. Чужие commits не перезаписываются.
- Metadata commit append допускается только на ожидаемый bot head и только fast-forward.
  Повтор на уже совпадающем head идемпотентен; отличающийся head даёт conflict.
- Изменение main/base требует повторного доказательства final head/base и actual live gates.
  Уже разрешённый пакет не заменяется новым автоматически. Изменившийся production base
  даёт диагностируемый отказ; нельзя молча менять base под тем же разрешением.
- Отзыв разрешения — явный committed per-release revocation, проверяемый перед секретами
  и перед PUT. Закрытие/удаление ветки после успешного merge само по себе не меняет факт merge.
- UNKNOWN PUT outcome ведёт только к read-only recovery. Durable journal, single writer,
  terminal receipts и запрет повторного blind PUT сохраняются. До завершения recovery
  никакой следующий candidate writer не запускается.

## Измерение времени и цель

Видны все интервалы: обнаружение → draft, draft → READY, READY → merge, merge → VERIFIED.
Нельзя спрятать время подготовки или человеческого ожидания, выдавая весь путь за 10 минут.
Цель **600 с от owner merge до полного публичного proof** сохраняется, но текущий 70888
дал MISS 831.24 с. Premerge подготовка и reuse CI убирают задержки; remaining publish path
нужно профилировать и улучшать только по измерениям, без удаления независимых барьеров.

t0 — GitHub `merged_at` единственного data MR, неизменный при retry/code fix. t1 — полный
ожидаемый signed payload и все retained manifests/files проверены, повторное чтение
каталога стабильно, target receipt VERIFIED. Timeout/MISS не прерывает неизвестную запись
и не отменяет последующую проверку. Уведомление содержит фактический результат и PASS/MISS.

## Совместимость и rollout

Новый режим применяется только к новым generated MR с явной `owner-merge-v1` политикой
после включения. Старые merge не получают ретроактивного согласия. Legacy frozen
requests/approvals/packages/tags/journals остаются читаемыми и побайтно неизменными.
70888 уже опубликован; 70862/70755 не переписываются. Старый ручной путь остаётся совместимым
для уже созданных legacy requests, но новые кандидаты идут только через один MR.

Сначала реализуется и проверяется весь связанный pipeline в одной ветке. Rollout — после
review и owner merge feature PR; никаких production dispatch/retry/перепубликаций для теста.
Настройки репозитория меняются только если необходимы и отдельно явно разрешены; дизайн
не требует branch-protection bypass или bot auto-merge. Метрики подтверждаются на следующем
обычном новом кандидате; до этого отдельно указываются test/benchmark доказательства.

## Границы работ и проверки

Область: candidate proposal/workflow, trusted premerge staging/package, conditional merge
authorization, main CI reuse, probe/publisher integration, reports/notifications и runbooks.
KMP UI, публичные data schemas, игровые semantics, keys и store delivery не меняются.
Никаких новых backend, persistence/analytics/DI или отдельных сервисов.

Обязательная интеграция: bot candidate → один draft MR → trusted staging/package → metadata
append → final PR CI → owner merge → main validated-merge CI → publish → full public proof.
Тесты сначала RED для новых правил, затем GREEN; полный regression suite и exact PR CI.
Негативные классы: fork/actor/origin mismatch, altered source/tree/base, partial CI history,
missing/expired/corrupt artifact, unknown fields/wire types, G1 source not on main at publish,
unshown G5, forbidden STOP, changed MR head after readiness, revocation before PUT, lost event,
consumer-only retry, duplicate package attempt/tag, stale base, UNKNOWN PUT recovery,
malformed/partial evidence, successful/failed publication mixed in one batch.

Перед планированием builder-check проверяет этот документ; вопросы, меняющие выбранное
поведение, возвращаются владельцу одним сообщением. Точные module boundaries, названия
CLI flags и разбиение commits выбирает builder при сохранении описанных контрактов.
