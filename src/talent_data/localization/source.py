"""Build- and locale-pinned localized DB2 tables, and the proof that a locale was applied.

Endpoint (public, no auth), probed against the live service on 2026-10-14
(``docs/research/forever-ruRU-localization-feasibility.md``):

* ``GET /db2/<Table>/csv?build=<version>&locale=<locale>`` - one DB2 table of one build in
  one client locale, as CSV with the same columns and rows as the default (enUS) export.

Observed answers the code relies on:

* the ``locale`` value is case-sensitive and **an unsupported value is not an error**:
  ``locale=ruru`` and ``locale=xxXX`` answer HTTP 200 with the enUS export byte for byte. A
  successful response is therefore no proof of localized content; every localized table is
  compared with the enUS table of the same build (:func:`verify_localized_table`);
* the build is honoured together with the locale: ``SpellName`` ruRU of 1.60.1.70009 and
  1.60.1.70338 differ, and each has exactly the row set of the enUS export of its own build
  (the Forever-only spells of 70338 exist only in the 70338 export);
* an unknown build answers HTTP 400 with an HTML page (as for the default export);
* untranslated rows are served with the English string in the localized export (identical
  text), and a localized export may lack a row the enUS export has (``SpellName``/``Spell``
  1318286 in 1.60.1.70245).

Files are cached at ``<cache_root>/<build>/locale/<locale>/<Table>.csv`` with the usual
``.meta.json`` sidecar. The sidecar records the build- and locale-pinned URL; a cache entry
recorded for another URL is treated as absent, so an enUS file (or another build's file)
copied into the locale directory is never reused.
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Protocol

from talent_data.models.localization import CYRILLIC, SUPPORTED_LOCALES, Locale
from talent_data.sources.base import Db2Table, SourceError
from talent_data.sources.csvio import read_csv_table
from talent_data.sources.http import DownloadError, HttpClient, is_valid_cache_entry
from talent_data.sources.wago.source import WAGO_BASE_URL, _canonical_column, _validate_csv
from talent_data.versions import ClientVersion

# The client tables whose localized columns the overlay reads. Presentation only, like
# TOOLTIP_TABLES: allocation never depends on them.
LOCALIZATION_TABLES: tuple[str, ...] = (
    "SpellName",  # Name_lang: talent names (the dataset's name rule)
    "TraitDefinition",  # OverrideName_lang / OverrideDescription_lang
    "Spell",  # Description_lang: description templates, also of nested spells
    "GlobalStrings",  # the duration unit formats of the locale
)

# Tables whose localized export must contain translated text, i.e. the tables that prove the
# locale was applied. TraitDefinition is exempt: its override columns hold no translatable
# text in the observed Forever builds (enUS and ruRU exports are byte-identical), so its
# localized strings can never be proven - and the generator treats an override that equals
# the English one as untranslated, which is safe whatever the source did.
LOCALE_EVIDENCE_TABLES: frozenset[str] = frozenset({"SpellName", "Spell", "GlobalStrings"})


class TableReader(Protocol):
    """The one thing localization needs from a source: a whole table of one build."""

    def load_table(self, version: ClientVersion, table: str) -> Db2Table: ...


class LocalizationSourceErrorKind(StrEnum):
    UNSUPPORTED_LOCALE = "unsupportedLocale"
    NOT_CACHED = "notCached"
    TRANSPORT = "transport"
    COLUMN_MISMATCH = "columnMismatch"
    DUPLICATE_ROW = "duplicateRow"
    UNEXPECTED_ROW = "unexpectedRow"
    NON_TEXT_MISMATCH = "nonTextMismatch"
    LOCALE_NOT_APPLIED = "localeNotApplied"


class LocalizationSourceError(SourceError):
    """A localized table cannot be trusted as the given locale of the given build."""

    def __init__(self, kind: LocalizationSourceErrorKind, message: str) -> None:
        super().__init__(message)
        self.kind = kind


def require_supported_locale(locale: str) -> Locale:
    for supported in SUPPORTED_LOCALES:
        if locale == supported:
            return supported
    raise LocalizationSourceError(
        LocalizationSourceErrorKind.UNSUPPORTED_LOCALE,
        f"locale {locale!r} is not supported (supported: {', '.join(SUPPORTED_LOCALES)}; "
        "the value is case-sensitive)",
    )


def table_url(base_url: str, version: ClientVersion, table: str, locale: str) -> str:
    """The build- and locale-pinned export URL (enUS is the default export, no parameter)."""
    base = f"{base_url.rstrip('/')}/db2/{table}/csv?build={version}"
    return base if locale == "enUS" else f"{base}&locale={locale}"


class WagoLocalizedDb2Source:
    """Downloads one locale's DB2 CSVs into ``<cache_root>/<build>/locale/<locale>/``."""

    name = "wago.tools"

    def __init__(
        self,
        http: HttpClient | None,
        cache_root: Path,
        locale: str,
        base_url: str = WAGO_BASE_URL,
        offline: bool = False,
    ) -> None:
        if http is None and not offline:
            raise ValueError("an HttpClient is required unless offline=True")
        self.locale = require_supported_locale(locale)
        self._http = http
        self._cache_root = cache_root
        self._base_url = base_url.rstrip("/")
        self._offline = offline

    def table_path(self, version: ClientVersion, table: str) -> Path:
        return self._cache_root / str(version) / "locale" / self.locale / f"{table}.csv"

    def table_url(self, version: ClientVersion, table: str) -> str:
        return table_url(self._base_url, version, table, self.locale)

    def is_cached(self, version: ClientVersion, table: str) -> bool:
        """A valid cache entry that was recorded for exactly this build's and locale's URL."""
        path = self.table_path(version, table)
        if not is_valid_cache_entry(path):
            return False
        meta = path.with_name(path.name + ".meta.json")
        try:
            recorded = json.loads(meta.read_text(encoding="utf-8")).get("url")
        except (OSError, ValueError, AttributeError):
            return False
        return bool(recorded == self.table_url(version, table))

    def ensure_table(self, version: ClientVersion, table: str) -> None:
        if self.is_cached(version, table):
            return
        path = self.table_path(version, table)
        if self._offline or self._http is None:
            raise LocalizationSourceError(
                LocalizationSourceErrorKind.NOT_CACHED,
                f"{table} {self.locale} for {version} is not in the local cache ({path}); run "
                f"`python -m talent_data localization fetch --build {version} "
                f"--locale {self.locale}` online",
            )
        try:
            # force: a present-but-unpinned entry (see is_cached) must be replaced, not reused.
            self._http.download_file(
                self.table_url(version, table), path, validate=_validate_csv, force=True
            )
        except DownloadError as exc:
            raise LocalizationSourceError(
                LocalizationSourceErrorKind.TRANSPORT,
                f"could not fetch {table} {self.locale} for build {version} from wago.tools "
                f"(unknown build/table, or wago.tools unavailable): {exc}",
            ) from exc

    def load_table(self, version: ClientVersion, table: str) -> Db2Table:
        self.ensure_table(version, table)
        return read_csv_table(self.table_path(version, table), table, rename=_canonical_column)


# --------------------------------------------------------------------------- verification


@dataclass(frozen=True)
class TableVerification:
    """What the comparison of one localized table with its enUS table established."""

    table: str
    locale: Locale
    source_rows: int  # rows of the enUS table
    localized_rows: int
    translated_rows: int  # some text column differs from enUS and contains Cyrillic
    identical_rows: int  # every text column equals enUS
    missing_rows: int  # in enUS, not in the localized table


def _text_columns(columns: tuple[str, ...]) -> tuple[str, ...]:
    return tuple(c for c in columns if c.endswith("_lang"))


def _by_id(table: Db2Table) -> dict[str, Mapping[str, str]]:
    rows: dict[str, Mapping[str, str]] = {}
    for row in table.rows:
        key = row.get("ID")
        if key is None:
            raise LocalizationSourceError(
                LocalizationSourceErrorKind.COLUMN_MISMATCH, f"{table.name} has no ID column"
            )
        if key in rows:
            raise LocalizationSourceError(
                LocalizationSourceErrorKind.DUPLICATE_ROW, f"{table.name} has two rows ID={key}"
            )
        rows[key] = row
    return rows


def verify_localized_table(
    source: Db2Table, localized: Db2Table, locale: Locale
) -> TableVerification:
    """Prove that ``localized`` is ``locale`` of the same build as ``source`` (enUS).

    * the same columns, in the same order;
    * every localized row exists in the enUS table (a localized export may lack rows);
    * every non-text column is equal row by row - the two exports are one build snapshot;
    * for a table in :data:`LOCALE_EVIDENCE_TABLES`, at least one row carries translated
      (Cyrillic, different) text - otherwise the source silently served enUS.
    """
    if source.name != localized.name:
        raise ValueError(f"comparing {source.name} with {localized.name}")
    table = source.name
    if source.columns != localized.columns:
        raise LocalizationSourceError(
            LocalizationSourceErrorKind.COLUMN_MISMATCH,
            f"{table} {locale} columns {list(localized.columns)} != enUS {list(source.columns)}",
        )
    text_columns = _text_columns(source.columns)
    other_columns = tuple(c for c in source.columns if c not in text_columns)
    english, translated = _by_id(source), _by_id(localized)
    unexpected = sorted(translated.keys() - english.keys(), key=int)
    if unexpected:
        raise LocalizationSourceError(
            LocalizationSourceErrorKind.UNEXPECTED_ROW,
            f"{table} {locale} has rows the enUS table of the same build lacks: {unexpected[:10]}",
        )
    translated_rows = identical_rows = 0
    for key, row in translated.items():
        reference = english[key]
        differing = [c for c in other_columns if row[c] != reference[c]]
        if differing:
            raise LocalizationSourceError(
                LocalizationSourceErrorKind.NON_TEXT_MISMATCH,
                f"{table} {locale} row ID={key} differs from enUS in non-text columns "
                f"{differing}: not the same build snapshot",
            )
        changed = [c for c in text_columns if row[c] != reference[c]]
        if not changed:
            identical_rows += 1
        elif any(CYRILLIC.search(row[c]) for c in changed):
            translated_rows += 1
    if table in LOCALE_EVIDENCE_TABLES and translated_rows == 0:
        raise LocalizationSourceError(
            LocalizationSourceErrorKind.LOCALE_NOT_APPLIED,
            f"{table} {locale} contains no translated row: the source served the enUS "
            "export (wago.tools answers an unsupported locale with enUS and HTTP 200)",
        )
    return TableVerification(
        table=table,
        locale=locale,
        source_rows=len(english),
        localized_rows=len(translated),
        translated_rows=translated_rows,
        identical_rows=identical_rows,
        missing_rows=len(english) - len(translated),
    )
