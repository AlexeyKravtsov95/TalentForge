# M7.IOS.RC.1 — существующая отправка, build 5

Статус отправки: **HOLD / NO-GO до закрытия release gates** из
[отчёта RC](../m7-ios-rc-1.md). Этот checklist — инструкция владельцу,
а не разрешение выполнить действия. Подготовка и signing RC разрешены;
загрузка IPA, изменение деклараций, сообщение Apple, App Review и публикация
в этой задаче не выполняются. Не создавать вторую запись приложения.

## Проверенная исходная точка

17 ноября 2026, Asia/Novosibirsk, read-only ASC:

- Приложение [6826780044](https://appstoreconnect.apple.com/apps/6826780044/distribution),
  bundle `io.github.alexeykravtsov95.talentforge`.
- Версия магазина **1.0**, выбран **1.0.0 (4)**. Существующее сочетание
  подтверждено интерфейсом; build 5 ещё не загружен этой задачей.
  Не менять Version на `1.0.0` ради косметического совпадения.
- [Отправка 0a3acce7-75fb-4f82-9f93-54edf5310eb6](https://appstoreconnect.apple.com/apps/6826780044/distribution/reviewsubmissions/details/0a3acce7-75fb-4f82-9f93-54edf5310eb6):
  Unresolved Issues, единственный item отклонён, 4.1.0 Design: Copycats,
  Resubmit отключён. Apple 16 ноября просит повторную отправку после исправлений;
  это не одобрение контента или прав.
- В ASC остаются старые branded name/subtitle/copy. Репозиторные исправления
  PR #151/#152 не применены автоматически.
- Content Rights сейчас утверждает наличие необходимых прав на сторонний контент.
  Документальное основание этим RC не установлено.
- Выбран **Automatic Release**. Требуемый для этой отправки **Manual Release**
  ещё не выбран.

[Наблюдение](../evidence/m7-ios-rc-1/asc-read-only-observation.json) не содержит
контактных данных, секретов или копии всей страницы.

## Gate перед любыми внешними действиями

- [ ] Владелец проверил достаточное правовое основание использования всех
  сторонних игровых текстов/иконок, class art и App Icon и сохранил доказательства.
- [ ] Декларация Content Rights правдива относительно фактического бинарника,
  OTA-материалов и изображений. Не выбирать «не содержит сторонний контент»,
  если он остаётся, и не утверждать наличие прав без достаточного основания.
- [ ] Закрыта приёмка iPhone/iPad, OTA/offline и необходимые нативные жесты.
- [ ] Скриншоты получены из build 5 Release OTA ON, проверены по manifest;
  исторические build 4 изображения не используются как доказательство build 5.
- [ ] Duo принимается отдельно: нет значка/заявления об оптимизации без
  приёмки Outer/Inner, переходов состояния и native multi-touch.
- [ ] CI и Mobile CI успешны на точном финальном HEAD PR, после последнего push.
- [ ] Проверены подписанный archive и экспортированный IPA, записаны SHA-256;
  дата production-каталога даёт минимум 30 дней до истечения при загрузке.
- [ ] Владелец отдельно разрешил конкретную загрузку и изменения ASC.

## Замена build 4 на build 5

После отдельного разрешения владелец загружает **тот же проверенный IPA** либо
проверенный archive через Organizer. Убедиться, что upload не меняет build number:
Manage Version and Build Number выключено; ожидается **1.0.0 (5)**.
Дождаться успешной обработки, проверить warnings/compliance и build metadata.
Если build 5 уже использован либо не появился, остановиться и установить причину;
не менять номер/запись магазина самовольно.

В существующем приложении открыть Distribution → iOS **1.0** → Build.
Навести на выбранный `(4)` и нажать **– / Удалить** рядом с билдом, Save;
это отсоединяет билд от версии, не удаляет его из аккаунта. Нажать **+**, выбрать
**1.0.0 (5)** → Done → Save. Проверить номер и версию в строке Build.
Если build 5 недоступен для версии 1.0, сохранить сообщение ASC и передать владельцу:
не создавать новую запись/версию для обхода несовпадения.
[Порядок Apple](https://developer.apple.com/help/app-store-connect/manage-builds/choose-a-build-to-submit).

## EN/RU метаданные и изображения

App Information: для English (U.S.) и Russian установить Name **TalentForge**,
Subtitle **Talent Build Planner** / **Планировщик талантов** из
[field files](metadata/). Проверить оба языка после Save.

Distribution → iOS 1.0: для каждого языка скопировать promotional text,
description, keywords и support URL из соответствующего файла без заголовков.
Privacy URL проверить в App Privacy для соответствующего языка; Review Notes
используют один общий английский файл `review_information/notes.txt`.
Не добавлять What's New для первой версии.

В App Previews and Screenshots / Media Manager заменить все затронутые
изображения согласованным набором build 5: iPhone Dynamic Island и iPad 13-inch,
EN/RU, одинаковый порядок сценариев. Проверить реальные превью и слоты размеров;
не оставлять build 4 library/favorites кадры. Duo Outer/Inner добавлять только
после его отдельного gate. Удаление/загрузка скриншотов — действие владельца.
Проверить App Icon: новая сборка не разрешает вопрос его происхождения.

## Content Rights и Manual Release

App Information → General Information → **Content Rights / Права на контент** →
Edit: владелец сопоставляет ответ с документальным основанием и фактическим
контентом. RC signing, валидная OTA-подпись и CI не подтверждают права.
При отсутствии основания отправка остаётся NO-GO; требуется урегулирование
использования либо отдельное согласованное изменение материалов.

Distribution → iOS 1.0 → **App Store Version Release** выбрать
**Manually release this version / Выпустить эту версию вручную** → Save.
Повторно открыть страницу: Manual выбран, Automatic и расписание не выбраны.
Сохранить наблюдение. После одобрения ожидается Pending Developer Release;
**Release This Version** — отдельное решение владельца, не часть повторного review.
[Настройка Apple](https://developer.apple.com/help/app-store-connect/manage-your-apps-availability/select-an-app-store-version-release-option).

## Unresolved Issues → повторная отправка

Открыть App Review → In Progress → Resolve для **существующей отправки** →
**Edit / Изменить** напротив отклонённого iOS item. Внести все согласованные
исправления и проверить build, оба языка, изображения, Notes, Content Rights
и Manual Release перед **Add for Review / Обновить данные для проверки**.
Apple указывает, что item можно отредактировать один раз перед resubmit:
готовить полный комплект заранее.

Когда все отклонённые items исправлены/добавлены для review, вернуться к деталям
отправки и проверить доступность **Resubmit to App Review**. Простое сообщение
Apple не заменяет исправление item. Не удалять единственный отклонённый item
для обхода: удалённый item нельзя добавить обратно в ту же отправку.
Если кнопка остаётся отключённой, проверить незавершённые обязательные поля,
статус item и текст ошибок; зафиксировать их, не отменять отправку автоматически.
[Unresolved Issues: порядок Apple](https://developer.apple.com/help/app-store-connect/manage-submissions-to-app-review/manage-a-submission-with-unresolved-issues).

- [ ] Владелец отдельно одобрил окончательные материалы и повторную отправку.
- [ ] Владелец нажал Resubmit to App Review; записаны submission ID, build 5,
  время, фактический новый статус и выбранный Manual Release.
- [ ] Никакой **Release This Version** не выполняется в рамках этого checklist.

Исторический [first-submission runbook](testflight-runbook.md) не применять
для создания New App; этот документ определяет маршрут build 5 в существующем app.
