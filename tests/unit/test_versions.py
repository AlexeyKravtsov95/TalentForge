from __future__ import annotations

import pytest

from talent_data.versions import ClientVersion


def test_parse_and_order() -> None:
    a = ClientVersion.parse("1.60.1.69977")
    b = ClientVersion.parse("1.60.1.70009")
    assert a < b
    assert a.branch == "1.60"
    assert str(b) == "1.60.1.70009"
    # numeric, not lexicographic ordering
    assert ClientVersion.parse("1.60.10.1") > ClientVersion.parse("1.60.9.99999")


@pytest.mark.parametrize("text", ["1.60.1", "1.60.x.1", "", "v1.60.1.1"])
def test_invalid_versions(text: str) -> None:
    with pytest.raises(ValueError, match="not a WoW client version"):
        ClientVersion.parse(text)
