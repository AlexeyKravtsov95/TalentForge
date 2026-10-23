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

1. Скопируйте оба файла в отдельный каталог, подставьте дату фактической публикации вместо `{{PUBLICATION_DATE}}`
   (одна дата в RU и EN). Убедитесь, что меток не осталось: `grep -c '{{' privacy-ios*.html` → `0`.
2. Загрузите `privacy-ios.html` и `privacy-ios-en.html` в корень сайта `legal.mythweld.com` (тот же бакет VK Cloud, что и
   `privacy.html`), с `Content-Type: text/html; charset=utf-8`. `style.css` уже опубликован, загружать его не нужно.
3. По желанию добавьте ссылку на iOS-редакцию в раздел «Документы» главной страницы `index.html` (единственная главная страница сайта; `index-en.html` не существует — 403).
4. Проверьте:

   ```bash
   curl -sI https://legal.mythweld.com/privacy-ios.html
   ```

   ```bash
   curl -sI https://legal.mythweld.com/privacy-ios-en.html
   ```

   Ожидается `200` и `content-type: text/html`.
5. Сообщите о публикации: агент проверит страницы (хэш опубликованного файла = файл с датой) и подготовит отдельный
   маленький PR, который включает ссылку в iOS-экране About (`platformPrivacyPolicy` на iOS сейчас `NotPublished`, ссылка
   намеренно не показывается, пока страница не существует — иначе это была бы ложная кнопка).
6. В App Store Connect укажите Privacy Policy URL: EN-локализация — `…/privacy-ios-en.html`, RU — `…/privacy-ios.html`.

До шага 5 статус App Store-готовности по политике — **BLOCKED** ([readiness](../../release/apple-appstore/readiness.md)).
