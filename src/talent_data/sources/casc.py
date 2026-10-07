"""Client file (CASC) source boundary.

A :class:`CascFileSource` hands out the raw bytes of one client file, addressed by
FileDataID, for one exact client build. It is deliberately separate from
:class:`~talent_data.sources.base.Db2Source`: a DB2 source is table-shaped, this one is
blob-shaped, and nothing but the build-time visuals pipeline needs it.

The contract is format-neutral: a source proves "these are the bytes of file N in build B"
and nothing about what the bytes mean. Callers validate the container they expect.
"""

from __future__ import annotations

from enum import StrEnum
from pathlib import Path
from typing import Protocol

from talent_data.sources.base import SourceError
from talent_data.versions import ClientVersion


class CascFileErrorKind(StrEnum):
    UNKNOWN_BUILD = "unknown-build"  # the source does not know this client build
    FILE_NOT_FOUND = "file-not-found"  # the build has no file with this FileDataID
    NOT_CACHED = "not-cached"  # offline, and the file was never fetched for this build
    INVALID_PAYLOAD = "invalid-payload"  # the source answered, but not with the file
    TRANSPORT = "transport"  # network / server failure; says nothing about the file


class CascFileError(SourceError):
    """A client file could not be provided. ``kind`` is the machine-readable reason."""

    def __init__(
        self,
        kind: CascFileErrorKind,
        version: ClientVersion,
        file_data_id: int,
        detail: str,
    ) -> None:
        super().__init__(f"file {file_data_id} of build {version}: {kind.value}: {detail}")
        self.kind = kind
        self.version = version
        self.file_data_id = file_data_id


class CascFileSource(Protocol):
    """Provides client files of a concrete client build by FileDataID."""

    name: str

    def fetch_file(self, version: ClientVersion, file_data_id: int) -> Path:
        """Path of the file's bytes for exactly ``version`` (fetching them first if needed).

        Never answers with a file of another build. Raises :class:`CascFileError`.
        """
        ...
