# Политика конфиденциальности iOS (M5.IOS.1)

| | |
|---|---|
| Статус | **Подготовлена, не опубликована → BLOCKED.** На 2026-10-23 `https://legal.mythweld.com/privacy-ios.html` и `…/privacy-ios-en.html` отвечают **403** (страниц нет). Публикует только владелец |
| Страницы | [`privacy-ios.html`](privacy-ios.html) (RU), [`privacy-ios-en.html`](privacy-ios-en.html) (EN) |
| Что вписывает владелец | только дату редакции вместо `{{PUBLICATION_DATE}}` (формат опубликованной Android-редакции: «23 октября 2026 года» / «23 October 2026») |
| Android-политика | не меняется: `privacy.html` / `privacy-en.html` (опубликованы, `Last-Modified: Wed, 21 Oct 2026 20:13:04 GMT`, редакция «22 октября 2026») описывают только Android-приложение |
| Юридический статус | LR-1…LR-5 без изменений; это не юридическая консультация |

## 1. Почему отдельные страницы

Опубликованная политика прямо говорит «Android-приложение TalentForge» и содержит Android-факты, неверные для iOS
(разрешение Android на доступ к интернету, отключённое резервное копирование Android, «настройки Android»).
Отдельные адреса не меняют уже опубликованную и указанную в Google Play / RuStore / AppGallery страницу.

## 2. Основа и отличия от Android-редакции

Основа — опубликованная Android-редакция от 22 октября 2026 года (разметка, шапка, подвал, скрипт языка, оператор, контакт —
без изменений). Отличаются только iOS-факты:

| Раздел | Android (опубликовано) | iOS (эта редакция) | Факт в коде |
|---|---|---|---|
| Заголовок, «О политике» | Android-приложение | приложение для iPhone и iPad; ссылка на Android-политику | — |
| 1. Данные | — | + «нет средств отслеживания» | `NSPrivacyTracking = false`, нет SDK третьих сторон |
| 2. Обновления | «не запрашивают разрешение на доступ к интернету»; «стандартное разрешение Android» | на iOS разрешения на сеть нет: «версии без кнопки не выполняют сетевых запросов»; отключение — сотовые данные в настройках iOS или отключение от сети | `IosOta.runtime()`, `PlatformBlobFetcher`; сборка M5.IOS.1 — OTA OFF |
| 2. Разрешения | местоположение, контакты, камера, микрофон, файлы, уведомления | + фото | `Info.plist` не содержит ни одного `NS*UsageDescription` |
| 3. Буфер, отправка | меню отправки Android | меню «Поделиться» iOS; «iOS может попросить разрешить вставку» | `IosBuildCodeSharing` (`UIActivityViewController`, `UIPasteboard`) |
| 4. Резервные копии | бэкап Android отключён | резервная копия iCloud/компьютера может включать данные приложения; загруженные обновления исключены из бэкапа | `NSUserDefaults` (входит в бэкап); `NSURLIsExcludedFromBackupKey` для `Application Support/TalentForge/ota`; экран About (`about_privacy_backup_ios`) |
| 4. Удаление | настройки Android или удаление приложения | удаление билда в приложении или удаление приложения | экран About (`about_privacy_delete_ios`, M5.IOS.1) |
| 6. Магазины | Google Play, RuStore, AppGallery | App Store (Apple) | — |
| 5, 7, 8 | — | без изменений | — |

## 3. Публикация (выполняет только владелец)

Обновлено в M5.IOS.3: подстановка даты и проверка опубликованных страниц выполняются скриптом
[`scripts/ios_privacy_pages.py`](../../../scripts/ios_privacy_pages.py), а не вручную. Сам скрипт ничего не загружает.

На 2026-10-24 обе страницы отвечают **403** (`verify-live` → FAIL). Сайт отвечает 403 и на несуществующие объекты
(например, `index-en.html`), поэтому 403 здесь означает «не опубликовано».

1. **Дата.** Одна дата для RU и EN — день фактической загрузки, в формате `YYYY-MM-DD` (в страницах она станет
   «25 октября 2026 года» / «25 October 2026», как в опубликованной Android-редакции). Если загрузка переносится на
   другой день, повторите шаг 2 с новой датой.
2. **Подготовка файлов** (из корня репозитория; каталог `legal-publish/` не в git):

   ```bash
   python3 scripts/ios_privacy_pages.py render --date 2026-10-25 --out legal-publish/ios
   ```

   Скрипт проверяет, что в каждой странице ровно одна метка `{{PUBLICATION_DATE}}` и что после подстановки `{{` не
   осталось, и печатает SHA-256 обоих файлов. После этого шага файлы не редактируйте.
3. **Загрузка** `legal-publish/ios/privacy-ios.html` и `legal-publish/ios/privacy-ios-en.html` в корень сайта
   `legal.mythweld.com` (тот же бакет VK Cloud, что и `privacy.html`), с метаданными объекта:

   | Метаданные | Значение |
   |---|---|
   | Имя объекта | `privacy-ios.html`, `privacy-ios-en.html` (в корне, без каталога) |
   | `Content-Type` | **`text/html; charset=utf-8`** — точно так; без `charset` проверка шага 4 не пройдёт |
   | Доступ | публичное чтение, как у `privacy.html` |

   `style.css` уже опубликован; `privacy.html` / `privacy-en.html` (Android) **не трогать**.
4. **Проверка HTTP 200** — с той же датой, что на шаге 2:

   ```bash
   python3 scripts/ios_privacy_pages.py verify-live --date 2026-10-25
   ```

   Ожидается `RESULT: PASS`: для каждой страницы `200`, `Content-Type: text/html; charset=utf-8`, тело побайтно равно
   файлу из шага 2. Та же проверка вручную:

   ```bash
   curl -sI https://legal.mythweld.com/privacy-ios.html
   ```

   ```bash
   curl -sI https://legal.mythweld.com/privacy-ios-en.html
   ```

   Ожидается `HTTP/2 200` и `content-type: text/html; charset=utf-8`. Если кэш ещё отдаёт старый ответ, повторите
   проверку через несколько минут; не меняйте файлы, чтобы «пройти» её.
5. По желанию добавьте ссылку на iOS-редакцию в раздел «Документы» главной страницы `index.html` (единственная главная
   страница сайта; `index-en.html` не существует — 403).
6. Сообщите агенту дату публикации. Только после `verify-live` → PASS агент готовит **отдельный** маленький PR, который
   включает ссылку в iOS-экране About (`platformPrivacyPolicy` на iOS сейчас `NotPublished`: ссылка намеренно не
   показывается, пока страница не существует, иначе это была бы ложная кнопка). M5.IOS.3 ссылку не меняет.
7. В App Store Connect укажите Privacy Policy URL: EN-локализация — `https://legal.mythweld.com/privacy-ios-en.html`,
   RU — `https://legal.mythweld.com/privacy-ios.html` (файлы `privacy_url.txt` [пакета отправки](../../release/apple-appstore/submission/README.md)).

До шага 6 статус App Store-готовности по политике — **BLOCKED** ([readiness M5.IOS.3](../../release/apple-appstore/m5-ios-3-readiness.md)).
