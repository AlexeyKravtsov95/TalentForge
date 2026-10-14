"""Localized wago.tools tables: build+locale pinning, cache identity, and the proof that the
locale was really applied (no network: scripted transport and real fixture rows)."""

from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest

from talent_data.localization.source import (
    LocalizationSourceError,
    LocalizationSourceErrorKind,
    WagoLocalizedDb2Source,
    require_supported_locale,
    verify_localized_table,
)
from talent_data.sources.base import Db2Table
from talent_data.sources.http import HttpClient, HttpConfig
from tests.helpers import NEW_BUILD, PRODUCT_BUILD
from tests.unit.localization.conftest import LOCALE, english_source, russian_source

CSV = "ID,Name_lang\n12282,Улучшенный\n".encode()
URL = f"https://wago.tools/db2/SpellName/csv?build={PRODUCT_BUILD}&locale=ruRU"


class Server:
    def __init__(self, *responses: httpx.Response) -> None:
        self.responses = list(responses)
        self.requests: list[httpx.Request] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        return self.responses.pop(0)


def source(server: Server, cache: Path, locale: str = LOCALE) -> WagoLocalizedDb2Source:
    http = HttpClient(
        HttpConfig(max_attempts=1, backoff_base=0),
        transport=httpx.MockTransport(server),
        sleep=lambda _: None,
    )
    return WagoLocalizedDb2Source(http, cache, locale)


def csv_ok(body: bytes = CSV) -> httpx.Response:
    return httpx.Response(200, content=body, headers={"content-type": "text/csv; charset=UTF-8"})


def test_request_and_cache_are_pinned_to_build_and_locale(tmp_path: Path) -> None:
    server = Server(csv_ok())
    table = source(server, tmp_path).load_table(PRODUCT_BUILD, "SpellName")
    assert str(server.requests[0].url) == URL
    path = tmp_path / str(PRODUCT_BUILD) / "locale" / "ruRU" / "SpellName.csv"
    assert json.loads(path.with_name(path.name + ".meta.json").read_text())["url"] == URL
    assert table.rows[0]["Name_lang"] == "Улучшенный"


def test_cached_table_is_not_downloaded_again(tmp_path: Path) -> None:
    source(Server(csv_ok()), tmp_path).ensure_table(PRODUCT_BUILD, "SpellName")
    server = Server()  # any request would fail
    source(server, tmp_path).ensure_table(PRODUCT_BUILD, "SpellName")
    assert server.requests == []


def test_a_table_recorded_for_another_url_is_never_reused(tmp_path: Path) -> None:
    """An export of another build copied into this build's locale directory is not reused."""
    source(Server(csv_ok()), tmp_path).ensure_table(NEW_BUILD, "SpellName")
    old = tmp_path / str(NEW_BUILD) / "locale" / "ruRU"
    new = tmp_path / str(PRODUCT_BUILD) / "locale" / "ruRU"
    new.mkdir(parents=True)
    for name in ("SpellName.csv", "SpellName.csv.meta.json"):
        (new / name).write_bytes((old / name).read_bytes())
    server = Server(csv_ok())
    source(server, tmp_path).ensure_table(PRODUCT_BUILD, "SpellName")
    assert [str(r.url) for r in server.requests] == [URL]


def test_offline_without_cache_is_a_typed_failure(tmp_path: Path) -> None:
    offline = WagoLocalizedDb2Source(None, tmp_path, LOCALE, offline=True)
    with pytest.raises(LocalizationSourceError) as caught:
        offline.load_table(PRODUCT_BUILD, "SpellName")
    assert caught.value.kind is LocalizationSourceErrorKind.NOT_CACHED


def test_unknown_build_is_a_transport_failure(tmp_path: Path) -> None:
    page = httpx.Response(400, content=b"<html>error</html>", headers={"content-type": "text/html"})
    with pytest.raises(LocalizationSourceError) as caught:
        source(Server(page), tmp_path).ensure_table(PRODUCT_BUILD, "SpellName")
    assert caught.value.kind is LocalizationSourceErrorKind.TRANSPORT


@pytest.mark.parametrize("locale", ["ruru", "RURU", "ru", "xxXX", "deDE", "enUS", ""])
def test_only_investigated_locales_are_accepted_case_sensitively(
    tmp_path: Path, locale: str
) -> None:
    # wago.tools answers 'ruru' and 'xxXX' with the enUS export and HTTP 200 - such a value must
    # never reach the network.
    with pytest.raises(LocalizationSourceError) as caught:
        require_supported_locale(locale)
    assert caught.value.kind is LocalizationSourceErrorKind.UNSUPPORTED_LOCALE
    with pytest.raises(LocalizationSourceError):
        source(Server(), tmp_path, locale)


# --------------------------------------------------------------------------- verification


def real(table: str) -> tuple[Db2Table, Db2Table]:
    return (
        english_source().load_table(PRODUCT_BUILD, table),
        russian_source().load_table(PRODUCT_BUILD, table),
    )


def test_the_real_exports_verify_as_one_build_in_two_locales() -> None:
    english, russian = real("SpellName")
    check = verify_localized_table(english, russian, LOCALE)
    assert check.source_rows == check.localized_rows == len(english.rows)
    assert check.missing_rows == 0
    # every talent name except the Forever names the client serves in English for ruRU
    assert check.identical_rows == 10
    assert check.translated_rows == check.localized_rows - 10
    for table in ("Spell", "GlobalStrings"):
        assert verify_localized_table(*real(table), LOCALE).translated_rows > 0


def test_traitdefinition_has_no_translatable_text_and_is_exempt_from_the_evidence() -> None:
    check = verify_localized_table(*real("TraitDefinition"), LOCALE)
    assert check.translated_rows == 0
    assert check.identical_rows == check.localized_rows


def test_the_english_export_served_for_rurU_is_detected() -> None:
    """Exactly what wago.tools does for an unsupported locale value (HTTP 200, enUS body)."""
    english, _ = real("SpellName")
    with pytest.raises(LocalizationSourceError) as caught:
        verify_localized_table(english, english, LOCALE)
    assert caught.value.kind is LocalizationSourceErrorKind.LOCALE_NOT_APPLIED


def _table(
    base: Db2Table, rows: list[dict[str, str]], columns: tuple[str, ...] | None = None
) -> Db2Table:
    return Db2Table(name=base.name, columns=columns or base.columns, rows=tuple(rows))


def test_a_row_missing_from_the_locale_is_counted_not_fatal() -> None:
    english, russian = real("SpellName")
    check = verify_localized_table(
        english, _table(russian, [dict(r) for r in russian.rows][1:]), LOCALE
    )
    assert check.missing_rows == 1


@pytest.mark.parametrize(
    ("mutate", "kind"),
    [
        (
            lambda rows: [*rows, {"ID": "999999999", "Name_lang": "Лишний"}],
            LocalizationSourceErrorKind.UNEXPECTED_ROW,
        ),
        (lambda rows: [*rows, rows[0]], LocalizationSourceErrorKind.DUPLICATE_ROW),
    ],
)
def test_rows_that_cannot_belong_to_the_same_build_fail(mutate, kind) -> None:
    english, russian = real("SpellName")
    rows = mutate([dict(r) for r in russian.rows])
    with pytest.raises(LocalizationSourceError) as caught:
        verify_localized_table(english, _table(russian, rows), LOCALE)
    assert caught.value.kind is kind


def test_a_different_non_text_column_means_another_build_snapshot() -> None:
    english, russian = real("TraitDefinition")
    rows = [dict(r) for r in russian.rows]
    rows[0]["SpellID"] = str(int(rows[0]["SpellID"]) + 1)
    with pytest.raises(LocalizationSourceError) as caught:
        verify_localized_table(english, _table(russian, rows), LOCALE)
    assert caught.value.kind is LocalizationSourceErrorKind.NON_TEXT_MISMATCH


def test_different_columns_fail() -> None:
    english, russian = real("SpellName")
    renamed = _table(
        russian,
        [{"ID": r["ID"], "Name": r["Name_lang"]} for r in russian.rows],
        columns=("ID", "Name"),
    )
    with pytest.raises(LocalizationSourceError) as caught:
        verify_localized_table(english, renamed, LOCALE)
    assert caught.value.kind is LocalizationSourceErrorKind.COLUMN_MISMATCH
