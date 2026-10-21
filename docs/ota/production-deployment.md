# Production OTA на VK Cloud: развёртывание и эксплуатация (M5.OTA.PROD.1)

Документ для **владельца проекта**. Все действия в облаке (VK Cloud, DNS домена `mythweld.com`)
владелец выполняет сам, вручную, через веб-консоль. Инструменты репозитория облачные ресурсы не
создают, не меняют и не удаляют. Production signing keys не генерируют и не читают.

> **M5.OTA.PROD.3 (2026-10-21).** Android-клиент подготовлен к production OTA: origin
> `https://ota.mythweld.com` и keyring `docs/ota/keys/production-keyring.json` закреплены в Gradle,
> канал включается только настройкой владельца `talentforge.ota.android.production.enabled=true`,
> `INTERNET` в release/play — ровно при включённом канале. По умолчанию OTA выключен, iOS — выключен.
> Процедура, ручные проверки и откат: [android-production-enablement.md](android-production-enablement.md).
> Абзац «Состояние» ниже описывает M5.OTA.PROD.1 (исторически).

Состояние на момент M5.OTA.PROD.1:

* Production OTA **выключен** во всех сборках Android и iOS. `AndroidManifest`, `INTERNET` и
  privacy policy не менялись.
* Production-бакета, production-ключей, домена `ota.mythweld.com` и production-каталога **ещё
  нет**. Ничего не публиковалось.
* В репозитории подготовлено:
  * production S3 publisher: `talent-data ota … --backend s3` с изоляцией каналов и guard'ом;
  * read-only preflight: `scripts/ota_production_preflight.py`;
  * хелпер публичного keyring: `scripts/ota_production_keys.py`;
  * шаблоны: `deploy/ota/vkcloud/production/`.

Связанные документы: [publisher-runbook.md](publisher-runbook.md) (общие процедуры publisher'а),
[publisher-and-security.md](publisher-and-security.md) §5 (ключи) и §6 (модель угроз),
[ota-contract-v1.md](ota-contract-v1.md), [test-plan.md](test-plan.md) §6 (гейты релиза).

Названия пунктов консоли VK Cloud приведены по документации VK Cloud на 2026-10-20. В интерфейсе
они могут немного отличаться. Если пункта нет, **остановитесь** и не подбирайте похожий вариант
наугад: сверьтесь с этим документом или задайте вопрос в PR.

---

## 0. Что будет создано (параметры ресурсов)

| Ресурс | Значение | Кто создаёт |
| --- | --- | --- |
| Бакет | `talentforge-ota-production`, VK Cloud Object Storage, регион `ru-msk`, класс хранения Hotbox (endpoint `hb.ru-msk`), ACL **private** | владелец |
| S3 API (только publisher) | `https://hb.ru-msk.vkcloud-storage.ru` | — |
| Публичный origin для клиентов | `https://ota.mythweld.com` (`<origin>/v1/…`) | владелец (DNS + CDN, §3) |
| Хост бакета (за CDN) | `https://talentforge-ota-production.hb.ru-msk.vkcloud-storage.ru` | появится с бакетом |
| Анонимный доступ | только `s3:GetObject` на `v1/channels/production/catalog.json` и `v1/blobs/sha256/*` (`deploy/ota/vkcloud/production/bucket-policy.json`) | владелец |
| Ключ publisher'а (S3) | **отдельный** prefix access key: бакет `talentforge-ota-production`, префикс `v1/`; профиль AWS CLI `talentforge-production` | владелец |
| Signing keys | `tf-prod-2026a` (основной), `tf-prod-2026b` (резервный), ECDSA P-256, офлайн | владелец (§4) |
| Публичный keyring | `docs/ota/keys/production-keyring.json` (только публичные ключи) | владелец передаёт публичные PEM, keyring коммитится в M5.OTA.PROD.2 |

Эти значения закоммичены в `src/talent_data/ota/s3_channels.py` (`PRODUCTION_PROFILE`). Если
владелец выбирает другое имя бакета или домена, сначала меняется профиль (через PR), потом
облако. Иначе publisher откажется работать с бакетом («unknown OTA bucket»).

Чего в облаке **не должно** быть никогда:

* приватных signing keys;
* staging-ключей или staging-каталога в production-бакете;
* публичной записи или листинга бакета;
* lifecycle-правил, которые удаляют объекты.

---

## 1. Решение владельца: как получить `https://ota.mythweld.com`

Клиент требует HTTPS с публичным доверенным сертификатом на имени, которым владеет проект. Тогда
смена провайдера не потребует обновлять приложение (OD-1). В VK Cloud есть два поддерживаемых
способа:

| | **A. CDN VK Cloud перед бакетом (рекомендуется)** | **B. «Привязать домен» к бакету + сертификат через поддержку** |
| --- | --- | --- |
| Домен | любой персональный домен, CNAME на CDN | URL-шаблон в документации VK: `<bucket>.<домен>`. Работает ли ровно `ota.mythweld.com`, документация не подтверждает |
| Сертификат | **Let's Encrypt, выпуск и продление автоматически** (CDN → SSL-сертификат) | свой сертификат, установка через тикет в техподдержку; продление тоже вручную (Let's Encrypt: каждые ~60 дней) |
| TLS-версии | настраиваются (оставить TLS 1.2 и 1.3) | не настраиваются |
| Кэш и нагрузка на origin | edge-кэш с учётом `Cache-Control` origin'а | нет |
| Стоимость | **платный ресурс**: оплачивается трафик CDN (pay-as-you-go). Объём маленький: один релиз ≈ 2,3 МБ на устройство с gzip | без новых сервисов. Платный сертификат, если не Let's Encrypt |
| Сложность эксплуатации | минимальная: всё настраивается один раз | регулярные ручные продления, риск истечения сертификата |

**Рекомендация: вариант A.** Его минимальная сложность в эксплуатации, а ручное продление
сертификата в варианте B — главный риск простоя. Но CDN — **дополнительный платный ресурс**,
поэтому решение за владельцем. Ниже описан вариант A. Вариант B кратко описан в §3.6. Preflight
одинаково проверяет оба варианта.

---

## 2. Бакет и политика доступа (консоль VK Cloud)

1. **Объектное хранилище → Бакеты → Создать бакет** (Cloud storage → Buckets → Create).
   * Имя: `talentforge-ota-production` (точно так, см. §0).
   * Класс хранения: **Hotbox** (как у staging; endpoint `hb.ru-msk.vkcloud-storage.ru`).
   * ACL по умолчанию: **private**. Не ставьте `public-read`: такой ACL открывает анонимный
     листинг бакета.
2. **Версионирование:** если в настройках бакета есть versioning, **включите его**. Предыдущие
   версии `catalog.json` — способ восстановиться после случайного удаления или перезаписи
   каталога (§9.4). Lifecycle-правил не создавайте.
3. **CORS, webhooks, статический хостинг:** не включайте. Нативному клиенту они не нужны.
4. **Политика бакета:** примените `deploy/ota/vkcloud/production/bucket-policy.json` тем же
   способом, что и для staging. Например, с **административным** профилем владельца (не с
   профилем publisher'а):

   ```bash
   aws s3api put-bucket-policy --bucket talentforge-ota-production \
       --policy file://deploy/ota/vkcloud/production/bucket-policy.json \
       --endpoint-url https://hb.ru-msk.vkcloud-storage.ru --profile <ваш-админ-профиль>
   aws s3api get-bucket-policy --bucket talentforge-ota-production \
       --endpoint-url https://hb.ru-msk.vkcloud-storage.ru --profile <ваш-админ-профиль>
   ```

   Политика разрешает **только** анонимный `s3:GetObject` на
   `v1/channels/production/catalog.json` и `v1/blobs/sha256/*`. Листинг, чтение ACL и политики,
   любые записи и любые другие ключи остаются закрытыми. Строку `write.anonymous` в preflight
   (статус `MANUAL`) закрывает именно этот шаг: убедитесь, что в консоли у бакета ACL `private`
   и нет других грантов.

## 3. CDN, домен и HTTPS (вариант A)

### 3.1 CDN-ресурс

Создайте ресурс **вручную** в разделе **CDN → CDN-ресурсы → Добавить ресурс**. Вкладку «CDN» в
самом бакете не используйте: по документации VK она требует ACL `public-read` на бакете, а это
открыло бы листинг.

| Параметр | Значение | Почему |
| --- | --- | --- |
| Источник (origin) | `talentforge-ota-production.hb.ru-msk.vkcloud-storage.ru` | публичный хост бакета |
| Протокол взаимодействия с источником | **HTTPS** | до origin тоже шифрование |
| Заголовок Host | **Пользовательский**: `talentforge-ota-production.hb.ru-msk.vkcloud-storage.ru` | при «Пересылать» (Forward) уйдёт `ota.mythweld.com`, и хранилище не найдёт бакет |
| Персональный домен | `ota.mythweld.com` | постоянный адрес из профиля |
| Кэширование CDN | **включено, «Настройки источника»** | соблюдать `Cache-Control` объектов: каталог `public, max-age=300`, blob'ы `immutable` на год |
| Кэширование в браузере | «Настройки источника» | то же |
| Игнорировать параметры запроса | не включать (по умолчанию) | — |
| Сжатие | gzip — можно. **Brotli и любые «оптимизации контента» (минификация, преобразование изображений) — выключить** | байты обязаны совпадать с SHA-256. Клиент умеет только gzip |
| Ограничение HTTP-методов (если есть) | GET, HEAD | — |
| Разрешённые версии TLS (вкладка «Безопасность») | **TLS 1.2, TLS 1.3** | по умолчанию VK разрешает SSL 3.0–TLS 1.3 |
| Редирект HTTP→HTTPS (если есть) | можно включить **только для http://** | клиенты никогда не ходят по http. Редиректы на https-путях запрещены: клиент их не принимает |
| Гео-, IP- и referer-ограничения | не включать | — |

Сохраните **CNAME-адрес**, который VK покажет для ресурса (вида `cl-….cdn.msk.vkcs.cloud`).

### 3.2 DNS

У DNS-провайдера `mythweld.com` создайте запись:

```
ota.mythweld.com.  CNAME  <адрес из 3.1>.
```

Если у `mythweld.com` есть CAA-записи, разрешите в них `letsencrypt.org`, иначе сертификат не
выпустится.

### 3.3 Сертификат

**CDN → CDN-ресурсы → ресурс → SSL-сертификат → Let's Encrypt.** Сертификат выпускается после
того, как CNAME начнёт резолвиться (до ~30 минут), и продлевается автоматически. Цепочка
Let's Encrypt (ISRG Root X1) доверена на Android 7.1.1+, minSdk приложения — 26. Trust anchor в
приложении **не нужен и не допускается** для production (правила Gradle).

### 3.4 Проверка после настройки (только чтение)

На своей машине, в checkout репозитория:

```bash
pip install -e '.[ota]'
python scripts/ota_production_preflight.py --expect empty \
    --expect-cname-suffix <домен из CNAME, например cdn.msk.vkcs.cloud>
```

Ожидаемо до первой публикации:

* `dns`, `dns.cname`, `tls`, `tls.expiry`, `private.origin`, `private.bucket` — `PASS`;
* `tls.legacy` — `PASS` (если `WARN`, вернитесь к TLS-версиям в 3.1);
* `http.plain` — `PASS` или `WARN` (обычный `http://` отдаётся; для клиентов это не опасно, но
  лучше включить редирект только для http);
* `catalog` — `PENDING` (каталога ещё нет), `releases` — `SKIP`, `write.anonymous` — `MANUAL`
  (подтвердите §2.4);
* итог `result: OK`, код выхода 0.

Preflight шлёт только анонимные `GET`/`HEAD`, редиректам не следует и ничего не меняет. Он
работает по принципу fail-closed:

* приватный путь считается закрытым **только** при ответе 403 или 404. Сетевая ошибка, тайм-аут,
  429, 5xx или любой другой ответ дают `FAIL`, а не `PASS`;
* если каталог опубликован, `result: OK` возможен только тогда, когда подпись проверена по
  `--keyring` и содержимое **каждого** релиза перехешировано. Без `--keyring` опубликованный
  каталог даёт `FAIL`;
* `--expect published` без `--keyring` — ошибка запуска (код 2), запросов при этом нет.
`--json preflight.json` сохраняет отчёт: его удобно приложить к PR M5.OTA.PROD.2.

### 3.5 Ключ publisher'а и изоляция ключей

1. Создайте **отдельный** prefix access key (как для staging): бакет
   `talentforge-ota-production`, префикс `v1/`. Ключ staging не переиспользуйте, ключ аккаунта
   с доступом ко всем бакетам — тоже.
2. На машине publisher'а:

   ```bash
   aws configure --profile talentforge-production   # region ru-msk; ключ попадёт в ~/.aws/credentials
   ```

   Конфигурация без секретов — `deploy/ota/vkcloud/production/aws-cli-profile.example`. Ключи
   никогда не попадают в git, CI, чат или PR.
3. Проверка изоляции (только чтение; каждая команда должна дать ожидаемый ответ):

   ```bash
   E=--endpoint-url=https://hb.ru-msk.vkcloud-storage.ru
   aws s3api list-objects-v2 --bucket talentforge-ota-production --prefix v1/ --max-items 5 $E --profile talentforge-production  # OK (пусто)
   aws s3api list-objects-v2 --bucket talentforge-ota-staging    --prefix v1/ --max-items 5 $E --profile talentforge-production  # AccessDenied
   aws s3api list-objects-v2 --bucket talentforge-ota-production --prefix v1/ --max-items 5 $E --profile talentforge-staging     # AccessDenied
   ```

   Если production-ключ видит staging (или наоборот), **остановитесь**: ключи не изолированы.
4. Publisher проверяет профиль сам, **до** обращения к S3:
   * любая запись в production (`promote`/`attach-signature --execute`) требует ровно
     `--aws-profile talentforge-production`, а запись в staging — ровно `talentforge-staging`.
     Без профиля, с другим профилем или с ключами в переменных окружения (`AWS_ACCESS_KEY_ID`,
     `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`) команда отказывает;
   * профиль другого канала отклоняется даже для чтения;
   * read-only команды (`validate`, `report`, `verify-live`, dry run) могут работать и без
     профиля.
5. Ограничение инструмента: судя по документации VK, prefix-ключ нельзя сделать «запись без
   удаления». Поэтому запрет удаления реализован в коде. Production-стор publisher'а никогда не
   вызывает `DELETE`, `gc` на production только перечисляет объекты, и каталог не удаляется
   никогда. Остаточный риск — утечка S3-ключа. Он описан в §9.5.

### 3.6 Вариант B (без CDN), если владелец откажется от платного CDN

1. Бакет → вкладка **«Привязать домен»** (Bind Domain): поддомен для бакета.
2. Сертификат: по документации VK — тикет в техподдержку с проектом, бакетом и сертификатом.
   Сертификат нужно получить самостоятельно и **продлевать до истечения**. Preflight
   (`tls.expiry`) предупреждает за 20 дней.
3. Если VK не позволит привязать ровно `ota.mythweld.com`, публичный origin меняется. Это правка
   `PRODUCTION_PROFILE.public_origin` через PR **до** M5.OTA.PROD.2: адрес компилируется в
   приложение.

---

## 4. Production signing keys (офлайн, только владелец)

Два **разных** ключа ECDSA P-256 — основной `tf-prod-2026a` и резервный `tf-prod-2026b`
(publisher-and-security §5). Их **никогда** не видят инструменты репозитория, CI, облако, чат,
менеджер паролей в облаке. `ota sign` отказывается от любых `tf-prod-*`.
`scripts/ota_production_keys.py` отклоняет любой PEM с приватным ключом ещё до разбора.

### 4.1 Генерация (офлайн-машина: без сети, лучше загруженная с live-носителя)

```bash
mkdir tf-prod-ceremony && cd tf-prod-ceremony
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -aes-256-cbc -out tf-prod-2026a.key.pem
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -aes-256-cbc -out tf-prod-2026b.key.pem
openssl pkey -in tf-prod-2026a.key.pem -pubout -out tf-prod-2026a.pub.pem
openssl pkey -in tf-prod-2026b.key.pem -pubout -out tf-prod-2026b.pub.pem
```

* Ключ шифруется (AES-256) сразу при создании: открытого приватного ключа на диске не бывает.
  Для двух ключей задайте **разные** длинные парольные фразы.
* Ровно эти команды прогоняет тест `test_the_runbook_openssl_ceremony_works_as_written` на
  одноразовых ключах.

### 4.2 Тестовая подпись (до того как приложение закрепит ключи)

На рабочей машине, в checkout:

```bash
python scripts/ota_production_keys.py keyring \
    --key tf-prod-2026a=tf-prod-2026a.pub.pem --key tf-prod-2026b=tf-prod-2026b.pub.pem \
    --out production-keyring.json          # в stderr — SPKI SHA-256 отпечатки: запишите их
python scripts/ota_production_keys.py ceremony-pae --out ceremony/
```

Перенесите `ceremony/ceremony.pae.bin` на офлайн-машину. Это публичные данные, а не каталог.
Подпишите **каждым** ключом:

```bash
openssl dgst -sha256 -sign tf-prod-2026a.key.pem -out ceremony-a.der ceremony.pae.bin
openssl dgst -sha256 -sign tf-prod-2026b.key.pem -out ceremony-b.der ceremony.pae.bin
```

Верните `.der` и проверьте:

```bash
python scripts/ota_production_keys.py check-signature --keyring production-keyring.json \
    --key-id tf-prod-2026a --pae ceremony/ceremony.pae.bin --signature ceremony-a.der
python scripts/ota_production_keys.py check-signature --keyring production-keyring.json \
    --key-id tf-prod-2026b --pae ceremony/ceremony.pae.bin --signature ceremony-b.der
```

Обе команды должны напечатать `OK`. Это и есть «test-sign and verify with both keys»
(publisher-and-security §5.2).

### 4.3 Хранение

| Что | Где | Где нельзя |
| --- | --- | --- |
| `tf-prod-2026a.key.pem` (основной, зашифрован) | офлайн-носитель №1 (зашифрованная флешка) у владельца | облако, git, CI, рабочий ноутбук с сетью, мессенджеры |
| `tf-prod-2026b.key.pem` (резервный, зашифрован) | **два** отдельных офлайн-носителя в разных местах (например, флешка в сейфе + бумажная или QR-копия) | там же, где основной; повседневное использование |
| Парольные фразы | отдельно от носителей (бумага в сейфе и/или локальный офлайн-менеджер паролей) | рядом с файлом ключа |
| Публичные PEM, keyring, отпечатки | репозиторий: `docs/ota/keys/` (M5.OTA.PROD.2) | — |

Восстановление проверьте сразу: подпишите `ceremony.pae.bin` ключом **с резервного носителя** и
выполните `check-signature`. Ротация ключей, потеря и компрометация описаны в
publisher-and-security §5.2 и publisher-runbook §5.

---

## 5. Первая публикация в production

Предусловия:

* §2–§4 выполнены, preflight `--expect empty` даёт `OK`;
* публичный keyring закоммичен (M5.OTA.PROD.2);
* staging-smoke пройден на этом же релизе (test-plan §4);
* approval record `docs/ota/releases/<releaseId>.md` смёржен в `main`. Он содержит строку
  `Manifest SHA-256` и live-build evidence (G7), см. publisher-runbook §7.

**Один publisher за раз.** VK Cloud игнорирует условные записи. Публикует только владелец, с
одной машины, CI S3-ключа не имеет.

```bash
pip install -e '.[ota,s3]'
P="--backend s3 --s3-bucket talentforge-ota-production \
   --s3-endpoint https://hb.ru-msk.vkcloud-storage.ru --s3-region ru-msk \
   --aws-profile talentforge-production"
K=docs/ota/keys/production-keyring.json
M=artifacts/ota/<build>-r1/release.json

# 1. сборка релиза из коммита main (детерминированная, G8)
talent-data ota build-release --game forever --build <build> --commit <40-hex sha в main> \
    --created-at <UTC> --out artifacts/ota
# 2. гейты G1–G8 и review-diff (только чтение)
talent-data ota validate --channel production --manifest $M $P --keyring $K
talent-data ota report --manifest $M $P --keyring $K
# 3. promote: сначала dry run, потом запись blob'ов (≈ 8–9 минут на релиз)
talent-data ota promote --manifest $M $P --keyring $K --out artifacts/ota
talent-data ota promote --manifest $M $P --keyring $K --out artifacts/ota --execute
#    печатает "PAE SHA-256 to sign offline: sha256:…" и пишет
#    artifacts/ota/pending-production.payload.json (+ .meta.json, + pending-production.pae.bin)
```

4. Перенесите `pending-production.pae.bin` на офлайн-машину. Сверьте хеш с напечатанным
   (`shasum -a 256 pending-production.pae.bin`). Подпишите **основным** ключом:

   ```bash
   openssl dgst -sha256 -sign tf-prod-2026a.key.pem -out pending-production.sig.der pending-production.pae.bin
   ```

5. Верните `.sig.der` и запишите каталог (это последняя запись всей процедуры):

   ```bash
   talent-data ota verify-live --channel production $P --keyring $K   # до первой публикации — ошибка NOT_FOUND, это норма
   talent-data ota attach-signature --payload artifacts/ota/pending-production.payload.json \
       --signature pending-production.sig.der --key-id tf-prod-2026a $P --keyring $K   # dry run
   talent-data ota attach-signature --payload artifacts/ota/pending-production.payload.json \
       --signature pending-production.sig.der --key-id tf-prod-2026a $P --keyring $K --execute
   talent-data ota verify-live --channel production $P --keyring $K   # sequence 1, все релизы OK
   python scripts/ota_production_preflight.py --expect published --keyring $K --json preflight.json
   ```

6. Архив: сохраните опубликованный конверт (только чтение) в локальный архив вне git:

   ```bash
   curl -fsS https://ota.mythweld.com/v1/channels/production/catalog.json \
       -o ~/talentforge-ota-archive/production-catalog-seq<N>.json
   ```

Что проверяет publisher перед записью каталога (ничего не нужно делать руками):

* `attach-signature` заново доказывает всю публикацию: подпись, pin payload, канал и игру,
  sequence = live + 1, append-only, все объекты и G1–G8 для добавленных релизов.
* Production-стор **ещё раз** проверяет конверт сам (`ProductionCatalogGuard`): подписи только
  `tf-prod-*` из production keyring, проверка на **реальных** часах (`--now` для production-записи
  запрещён), не меньше 7 дней валидности, `issuedAt` не в будущем, точный наследник live-каталога.
* Непосредственно перед `PUT` перечитывается ETag live-каталога (замена `If-Match`, который VK
  игнорирует), после `PUT` каталог читается обратно и сравнивается побайтно.

## 6. Обновление данных при новом патче

Процедура та же, что в §5, с новым build. `promote` дописывает релиз (append-only) и делает его
активным. Перед этим:

* freshness watcher **только сигнализирует**: публикация по его сигналу не запускается
  автоматически никогда. Решение принимает владелец по live-build evidence (G7, OD-15);
* новый релиз сначала проходит staging (`stage` в `talentforge-ota-staging`) и staging-smoke;
* `ota report` — прочитайте diff;
* до и после `attach-signature` выполните `verify-live`. Sequence после должна быть ровно той,
  которую напечатал `attach-signature`.

## 7. Rollback

```bash
talent-data ota rollback --channel production --active <старый releaseId> $P --keyring $K --out artifacts/ota          # dry run
talent-data ota rollback --channel production --active <старый releaseId> $P --keyring $K --out artifacts/ota --execute
# офлайн: openssl dgst -sha256 -sign tf-prod-2026a.key.pem -out pending-production.sig.der pending-production.pae.bin
talent-data ota attach-signature --payload artifacts/ota/pending-production.payload.json \
    --signature pending-production.sig.der --key-id tf-prod-2026a $P --keyring $K --execute
talent-data ota verify-live --channel production $P --keyring $K
```

Rollback — **новый** каталог с большим sequence. Релизы не снимаются с публикации (OD-7).
Клиенты увидят изменение не позже чем через `max-age=300` секунд (CDN и устройства). Ускорить
можно так: CDN → ресурс → **Очистка кэша** только для пути
`/v1/channels/production/catalog.json`. Blob'ы чистить не нужно.

## 8. Повторная подпись каталога до истечения срока

Каталог действует 45 дней (OD-13). Переподписывайте его **каждые ~30 дней** или вместе с
любым изменением. Поставьте напоминание на дату `issuedAt + 30 дней`.

```bash
talent-data ota resign --channel production $P --keyring $K --out artifacts/ota --execute
# офлайн-подпись pending-production.pae.bin, затем attach-signature … --execute и verify-live, как в §7
```

* Preflight пишет `catalog.validity WARN`, если осталось меньше 15 дней, и `FAIL`, если меньше 7.
* Production-guard не запишет каталог, у которого осталось меньше 7 дней.
* Если срок уже истёк, `resign` остаётся способом восстановления: истёкший live-каталог
  проверяется по подписи и структуре, «прощается» только окно срока (publisher-runbook §4).
  Клиенты отклоняют истёкший каталог, пока его не заменят. Данные у них при этом не ломаются.

## 9. Восстановление после сбоя

| Ситуация | Что делать |
| --- | --- |
| 9.1 `promote --execute` прервался (сеть, VK не принял соединение) | Запустите ту же команду ещё раз: существующие blob'ы пропускаются, каталог не трогается |
| 9.2 `attach-signature`: «concurrent publication» / `PRECONDITION_FAILED` | Live-каталог изменился после чтения. Ничего не записано. `verify-live`, затем заново `promote`/`rollback`/`resign` от актуального состояния и новая подпись |
| 9.3 `VERIFY_FAILED` после записи каталога (read-back не совпал) | Остановитесь. `verify-live`. Если live — валидный каталог с ожидаемым sequence, всё в порядке. Если нет — гонка двух publisher'ов: выпустите следующий sequence от проверенного состояния (`resign` → подпись → `attach-signature`). Клиенты не принимают меньший sequence или чужие байты с тем же sequence |
| 9.4 `catalog.json` случайно удалён или перезаписан в консоли | Publisher не может «восстановить» sequence N: guard требует sequence = live + 1, а без live — 1. Восстановите **последнюю версию объекта** через versioning бакета (§2.2) или загрузите архивный конверт из §5.6 административным профилем, без streaming-checksum (иначе VK отдаст `Content-Encoding: aws-chunked`): `AWS_REQUEST_CHECKSUM_CALCULATION=when_required aws s3api put-object --bucket talentforge-ota-production --key v1/channels/production/catalog.json --body production-catalog-seq<N>.json --content-type application/json --cache-control "public, max-age=300" $E --profile <админ>`. Затем `verify-live` и preflight `--expect published` (он проверит заголовки) |
| 9.5 Утечка S3-ключа publisher'а | Удалите prefix-ключ (DeletePrefixKey) и создайте новый, обновите профиль. Подписи это не затрагивает: без signing key злоумышленник не сделает каталог, который примут клиенты. Он может удалить или испортить объекты: клиенты откажут (hash/signature), восстановление через versioning или повторный `promote`. Затем `verify-live` и preflight |
| 9.6 Потеря или компрометация signing key | publisher-runbook §5, publisher-and-security §5.2 (резервный ключ уже закреплён в приложении) |
| 9.7 Сертификат не продлился / TLS-ошибки | Preflight `tls`/`tls.expiry`. Проверьте CNAME и CAA (§3.2), статус Let's Encrypt в CDN-ресурсе |
| 9.8 CDN отдаёт старый каталог | Подождите `max-age` (5 минут) или очистите кэш пути каталога (§7). `catalog.bucket WARN` в preflight показывает такое расхождение |

## 10. Проверки перед включением OTA в Android-релизе (вход в M5.OTA.PROD.2 и далее)

**M5.OTA.PROD.3:** Gradle-конфигурация, `INTERNET` и тексты для OTA ON подготовлены
([android-production-enablement.md](android-production-enablement.md),
[ota-prod3-privacy-and-stores.md](../release/ota-prod3-privacy-and-stores.md)); условия ниже по-прежнему
выполняет владелец перед выпуском OTA ON сборки.

Включение OTA — **отдельная задача** (M5.OTA.PROD.2 или позже): `INTERNET`, privacy policy,
data-safety, production keyring и base URL в Gradle-конфигурации. В M5.OTA.PROD.1 ничего из этого
не меняется. Перед включением должны выполняться все условия:

1. Preflight `--expect published --keyring docs/ota/keys/production-keyring.json` — `result: OK`,
   все `WARN` разобраны. Отчёт приложен к PR.
2. `talent-data ota verify-live --channel production …` — все релизы `OK`.
3. Отпечатки SPKI в `docs/ota/keys/` совпадают с записанными на церемонии (§4.2). Gradle-правила
   (`verifyOtaSettingsRules`) проходят: в production только `tf-prod-*`, нет trust anchor, нет
   staging-ключей.
4. test-plan §6A полностью: в частности A.6 (ключи и восстановление резервного), A.7 (hosting,
   HTTPS на своём домене), A.9 (напоминание о переподписи), A.10 (rollback отрепетирован на
   staging).
5. test-plan §6B: реальные HTTPS-загрузки с `https://ota.mythweld.com` на эмуляторе API 26 и на
   актуальном API, smoke на физическом устройстве, kill switch, privacy и store compliance.
6. Каталог продержится до выхода релиза и первых обновлений: осталось ≥ 30 дней, или
   переподписан непосредственно перед релизом.

---

## Приложение: что гарантирует код (M5.OTA.PROD.1)

* **Изоляция каналов** (`talent_data.ota.s3_channels`): канал открывается только на своём
  закоммиченном бакете, endpoint и регионе. Staging-бакет как production → отказ («is the
  staging bucket; refusing it for the production channel»), и наоборот. Ни один стор не пишет
  каталог другого канала. CLI определяет канал по бакету и отказывает команде, адресованной
  другому каналу, **до** подключения.
* **Изоляция ключей S3:** запись — только с профилем своего канала, без ключей в окружении;
  профиль чужого канала отклоняется всегда, ещё до создания клиента.
* **Fail-closed preflight:** приватный путь закрыт только при 403/404; опубликованный каталог
  не даёт `OK` без проверки подписи по keyring и перехеширования всех релизов.
* **Production без опасных операций:** нет удаления, нет `--now` при записи, нет fault injection.
  Staging-хелпер `scripts/ota_vkcloud_staging.py` открывает только staging-профиль. Guard
  отклоняет плохую подпись, staging/test-ключи, короткоживущий («expired fault»), истёкший,
  датированный будущим, staging-канальный, чужой игры, пропуск sequence, повтор sequence, снятие
  релиза, не-конверт, превышение 1 MiB.
* **Сохранено из M5.OTA.2–4c:** immutable SHA-256 blob'ы (hash = ключ, без перезаписи, `HEAD` +
  проверка ETag/заголовков после `PUT`), каталог — последней записью, проверка sequence,
  read-back, G1–G8, fail-closed, dry run по умолчанию (`--execute`).
* **Тесты:** `tests/unit/ota/test_ota_s3_production.py`, `test_ota_preflight.py`,
  `test_ota_production_keys.py`, обновлённый `test_ota_s3_store.py`. Всё офлайн: in-memory S3 в
  режиме VK (условные записи игнорируются), тестовые ключи из публичных seed'ов под
  `tf-prod-*` id, только внутри прогона тестов.
