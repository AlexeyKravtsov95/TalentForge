# M6.LIB.1 — Build library search and filters

Android emulator: Pixel 9a AVD (Android 15 / API 35), APK built with the repository's debug
configuration. Screenshots use 393 × 852 dp at 100% system font unless named
otherwise. The 320 dp screenshot uses 320 × 852 dp and 200% system font.
The emulator contains five disposable local builds bound to the committed
1.60.1.70450 class/tree identities; no live game data or network is needed.
User-given build names intentionally remain English in the Russian interface.

| State | EN | RU |
| --- | --- | --- |
| All results | [Library](android-en-library.png) | [Библиотека](android-ru-library.png) |
| Compact class dropdown | [Classes](android-en-classes.png) | [Классы](android-ru-classes.png) |
| Name search AND selected class | [Search](android-en-search-class.png) | [Поиск](android-ru-search-class.png) |
| No search results + Reset filters | [No results](android-en-no-results.png) | [Нет результатов](android-ru-no-results.png) |
| Narrow phone / large text | — | [320 dp / 200%](android-ru-320dp-200percent.png) |

Compose UI coverage exercises both languages at every combination of
320/360/393/412 dp and 100/130/175/200% text (32 combinations). Each checks the
search, one-tap clear, selected radio semantics, scrolling to the last class,
class-only no-results text, Reset filters, unchanged storage and the existing
New build action. Flow tests cover localized Draft titles, search/class
intersection, active-search rename/delete, real-localId editor opening and a
visible storage read failure with Retry. Pure Kotlin tests cover empty queries,
EN/RU, stable order and libraries of 1/10/100 records.

Storage format is unchanged: `LocalBuildLibraryCodec.STORAGE_VERSION == 1`,
`LocalBuildRecord` and `allocation-build.v1` are untouched. Search and class
selection are view state; neither performs a repository operation.
