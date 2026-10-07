"""wago.tools implementation of :class:`CascFileSource`.

Endpoint (public, no auth), verified against the live service on 2026-10-07:

* ``GET /api/casc/<FileDataID>?version=<build>`` - the raw client file of that build,
  ``application/octet-stream``.

Observed answers the code relies on (pinned by tests with the real payloads):

* the ``version`` parameter matters: without it wago.tools serves its *latest* copy of the
  file, which for FileDataID 132269 differs byte-for-byte from the 1.60.1.70009 one. The
  build is therefore always sent, and it is part of the cache identity;
* unknown build -> HTTP 400 ``{"error":"Version not found"}``;
* unknown FileDataID -> HTTP **200** ``{"error":"File not found","0":400}``. A 200 is not
  proof of a file, so every payload is checked for a JSON error object before it may
  become a cache entry.

Files are cached at ``<cache_root>/<build>/casc/<FileDataID>.bin`` with the usual
``.meta.json`` sidecar. The sidecar records the build-pinned URL; a cache entry whose
recorded URL is not the one this build would request is treated as absent, so a file
copied in from another build's directory is never reused.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

from talent_data.sources.casc import CascFileError, CascFileErrorKind
from talent_data.sources.http import DownloadError, HttpClient, is_valid_cache_entry
from talent_data.sources.wago.source import WAGO_BASE_URL
from talent_data.versions import ClientVersion

log = logging.getLogger(__name__)

_FILE_NOT_FOUND = "File not found"
_VERSION_NOT_FOUND = "Version not found"


class _ErrorPayload(DownloadError):
    """The body is wago.tools' JSON error object instead of a client file."""

    def __init__(self, message: str, *, error: str) -> None:
        super().__init__(message)
        self.error = error


class WagoCascFileSource:
    name = "wago.tools"

    def __init__(
        self,
        http: HttpClient | None,
        cache_root: Path,
        base_url: str = WAGO_BASE_URL,
        offline: bool = False,
    ) -> None:
        if http is None and not offline:
            raise ValueError("an HttpClient is required unless offline=True")
        self._http = http
        self._cache_root = cache_root
        self._base_url = base_url.rstrip("/")
        self._offline = offline

    def file_path(self, version: ClientVersion, file_data_id: int) -> Path:
        return self._cache_root / str(version) / "casc" / f"{file_data_id}.bin"

    def file_url(self, version: ClientVersion, file_data_id: int) -> str:
        return f"{self._base_url}/api/casc/{file_data_id}?version={version}"

    def is_cached(self, version: ClientVersion, file_data_id: int) -> bool:
        """A valid cache entry that was recorded for exactly this build's URL."""
        path = self.file_path(version, file_data_id)
        if not is_valid_cache_entry(path):
            return False
        meta = path.with_name(path.name + ".meta.json")
        try:
            recorded = json.loads(meta.read_text(encoding="utf-8")).get("url")
        except (OSError, ValueError, AttributeError):
            return False
        return bool(recorded == self.file_url(version, file_data_id))

    def fetch_file(self, version: ClientVersion, file_data_id: int) -> Path:
        if file_data_id <= 0:
            raise CascFileError(
                CascFileErrorKind.FILE_NOT_FOUND, version, file_data_id, "not a FileDataID"
            )
        path = self.file_path(version, file_data_id)
        if self.is_cached(version, file_data_id):
            return path
        if self._offline or self._http is None:
            raise CascFileError(
                CascFileErrorKind.NOT_CACHED,
                version,
                file_data_id,
                f"not in the local cache ({path}); run "
                f"`python -m talent_data visuals fetch --build {version}` online",
            )
        try:
            # force: a present-but-unpinned entry (see is_cached) must be replaced, not reused.
            self._http.download_file(
                self.file_url(version, file_data_id),
                path,
                validate=_reject_error_payload,
                force=True,
            )
        except _ErrorPayload as exc:
            kind = (
                CascFileErrorKind.FILE_NOT_FOUND
                if exc.error == _FILE_NOT_FOUND
                else CascFileErrorKind.INVALID_PAYLOAD
            )
            raise CascFileError(kind, version, file_data_id, str(exc)) from exc
        except DownloadError as exc:
            kind = (
                CascFileErrorKind.UNKNOWN_BUILD
                if _json_error(exc.body) == _VERSION_NOT_FOUND
                else CascFileErrorKind.TRANSPORT
            )
            raise CascFileError(kind, version, file_data_id, str(exc)) from exc
        return path


def _json_error(body: str | None) -> str | None:
    """The ``error`` text of a wago.tools JSON error object, or None for anything else."""
    if body is None:
        return None
    try:
        payload = json.loads(body)
    except ValueError:
        return None
    if isinstance(payload, dict) and isinstance(payload.get("error"), str):
        return str(payload["error"])
    return None


def _reject_error_payload(path: Path) -> None:
    """Refuse a JSON error object served with HTTP 200 in place of the file."""
    with path.open("rb") as fh:
        head = fh.read(4096)
    if not head.lstrip().startswith(b"{"):
        return
    try:
        text = head.decode("utf-8")
    except UnicodeDecodeError:
        return
    error = _json_error(text)
    if error is not None:
        raise _ErrorPayload(f"{path.name}: wago.tools answered {text.strip()!r}", error=error)
