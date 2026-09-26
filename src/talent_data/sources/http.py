"""Robust HTTP download helper shared by all network sources.

Guarantees:

* every request has an explicit timeout;
* non-2xx responses raise :class:`DownloadError` with URL + status + a body excerpt;
* transient failures (connection errors, timeouts, HTTP 429 and 5xx) are retried with
  exponential backoff; permanent failures (other 4xx) are not;
* files are written atomically (``<name>.part`` -> rename) and a sidecar
  ``<name>.meta.json`` (size + sha256) is written *after* the rename. A cache entry is
  considered valid only when the sidecar exists and matches the file, so an interrupted
  download can never be mistaken for a successful one;
* an optional ``validate`` callback can reject a downloaded payload (e.g. HTML error page
  instead of CSV) before it becomes a cache entry.
"""

from __future__ import annotations

import hashlib
import json
import logging
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

import httpx

log = logging.getLogger(__name__)

USER_AGENT = "talent-data/0.1 (+https://github.com/AlexeyKravtsov95/TalentForge)"

# Status codes that indicate a temporary server-side/rate-limit condition worth retrying.
RETRYABLE_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504})


class DownloadError(RuntimeError):
    """Raised when a resource cannot be fetched or fails validation."""


@dataclass(frozen=True)
class HttpConfig:
    # Connect/read timeouts in seconds. DB2 CSVs such as SpellEffect are tens of MB, so the
    # read timeout applies per chunk, not to the whole transfer.
    connect_timeout: float = 10.0
    read_timeout: float = 60.0
    max_attempts: int = 4
    backoff_base: float = 1.0  # seconds; delay = base * 2**(attempt-1)


class HttpClient:
    def __init__(
        self,
        config: HttpConfig | None = None,
        transport: httpx.BaseTransport | None = None,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self._config = config or HttpConfig()
        self._sleep = sleep
        timeout = httpx.Timeout(self._config.read_timeout, connect=self._config.connect_timeout)
        self._client = httpx.Client(
            timeout=timeout,
            follow_redirects=True,
            headers={"User-Agent": USER_AGENT},
            transport=transport,
        )

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> HttpClient:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    # ------------------------------------------------------------------ JSON
    def get_json(self, url: str, params: dict[str, str] | None = None) -> object:
        def attempt() -> object:
            response = self._client.get(url, params=params)
            _raise_for_status(response)
            try:
                return response.json()
            except ValueError as exc:
                raise DownloadError(
                    f"GET {response.url} returned non-JSON payload "
                    f"(content-type={response.headers.get('content-type')!r})"
                ) from exc

        return self._with_retries(url, attempt)

    # ------------------------------------------------------------------ files
    def download_file(
        self,
        url: str,
        dest: Path,
        *,
        params: dict[str, str] | None = None,
        validate: Callable[[Path], None] | None = None,
        force: bool = False,
    ) -> Path:
        """Download ``url`` into ``dest`` unless a valid cache entry already exists."""
        if not force and is_valid_cache_entry(dest):
            log.debug("cache hit: %s", dest)
            return dest

        dest.parent.mkdir(parents=True, exist_ok=True)
        part = dest.with_name(dest.name + ".part")
        meta = _meta_path(dest)
        # Invalidate any previous entry before we start overwriting it.
        meta.unlink(missing_ok=True)

        def attempt() -> Path:
            try:
                size, sha256 = self._stream_to_file(url, params, part)
                if validate is not None:
                    validate(part)
            except BaseException:
                part.unlink(missing_ok=True)
                raise
            part.replace(dest)
            meta.write_text(
                json.dumps({"url": url, "size": size, "sha256": sha256}), encoding="utf-8"
            )
            return dest

        return self._with_retries(url, attempt)

    def _stream_to_file(
        self, url: str, params: dict[str, str] | None, part: Path
    ) -> tuple[int, str]:
        digest = hashlib.sha256()
        size = 0
        with self._client.stream("GET", url, params=params) as response:
            if response.is_error:
                response.read()
                _raise_for_status(response)
            with part.open("wb") as fh:
                for chunk in response.iter_bytes():
                    fh.write(chunk)
                    digest.update(chunk)
                    size += len(chunk)
            expected = response.headers.get("content-length")
            encoding = response.headers.get("content-encoding")
            # Content-Length is only comparable when the body was not transfer-decoded.
            if expected is not None and not encoding and int(expected) != size:
                raise httpx.ReadError(f"truncated body: expected {expected} bytes, got {size}")
        if size == 0:
            raise DownloadError(f"GET {url} returned an empty body")
        return size, digest.hexdigest()

    # ------------------------------------------------------------------ retry
    def _with_retries[T](self, url: str, fn: Callable[[], T]) -> T:
        last: Exception | None = None
        for n in range(1, self._config.max_attempts + 1):
            try:
                return fn()
            except _Retryable as exc:
                last = exc
                reason = str(exc)
            except (httpx.TransportError, httpx.TimeoutException) as exc:
                last = exc
                reason = f"{type(exc).__name__}: {exc}"
            if n < self._config.max_attempts:
                delay = self._config.backoff_base * 2 ** (n - 1)
                log.warning(
                    "GET %s failed (%s); retry %d/%d in %.1fs",
                    url,
                    reason,
                    n,
                    self._config.max_attempts - 1,
                    delay,
                )
                self._sleep(delay)
        raise DownloadError(
            f"GET {url} failed after {self._config.max_attempts} attempts: {last}"
        ) from last


class _Retryable(DownloadError):
    """Internal marker: transient HTTP status that should be retried."""


def _raise_for_status(response: httpx.Response) -> None:
    if response.is_success:
        return
    content_type = response.headers.get("content-type", "")
    if not response.content:
        excerpt = ""
    elif "html" in content_type:
        excerpt = "(HTML error page)"
    else:
        excerpt = response.text[:200].replace("\n", " ")
    message = f"GET {response.request.url} -> HTTP {response.status_code} {excerpt}".strip()
    if response.status_code in RETRYABLE_STATUS:
        raise _Retryable(message)
    raise DownloadError(message)


def _meta_path(dest: Path) -> Path:
    return dest.with_name(dest.name + ".meta.json")


def is_valid_cache_entry(dest: Path) -> bool:
    """A cached file is valid only if its sidecar exists and size + sha256 match."""
    meta = _meta_path(dest)
    if not dest.is_file() or not meta.is_file():
        return False
    try:
        info = json.loads(meta.read_text(encoding="utf-8"))
        if dest.stat().st_size != info["size"]:
            return False
        digest = hashlib.sha256()
        with dest.open("rb") as fh:
            for block in iter(lambda: fh.read(1 << 20), b""):
                digest.update(block)
        return bool(digest.hexdigest() == info["sha256"])
    except (OSError, ValueError, KeyError) as exc:
        log.warning("ignoring corrupt cache metadata %s: %s", meta, exc)
        return False
