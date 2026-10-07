"""Typed failures of the client tooltip capture import.

Every way a capture can be unusable has its own exception type, so callers and tests
distinguish them by type and never by message text. All of them are ``PipelineError``s:
the CLI reports them like any other pipeline failure.
"""

from __future__ import annotations

from collections.abc import Mapping

from talent_data.pipeline import PipelineError


class CaptureImportError(PipelineError):
    """A client tooltip capture cannot be imported. Nothing is written."""


# --------------------------------------------------------------------------- handoff


class MalformedSavedVariablesError(CaptureImportError):
    """The SavedVariables file is not the wrapper the capture addon writes."""


class NoStoredCaptureError(CaptureImportError):
    """The SavedVariables file holds no capture at all (never run, or cleared)."""


class MalformedPayloadError(CaptureImportError):
    """The payload is not valid base64 / UTF-8 / strict JSON of the capture shape."""


class UnsupportedCaptureFormatError(CaptureImportError):
    """The wrapper or payload declares a format version this importer does not read."""


# --------------------------------------------------------------------------- identity


class CaptureIdentityError(CaptureImportError):
    """The capture was not produced by the exact client / manifest the repository expects."""


class GameMismatchError(CaptureIdentityError):
    pass


class ClientBuildMismatchError(CaptureIdentityError):
    pass


class LocaleMismatchError(CaptureIdentityError):
    pass


class ManifestHashMismatchError(CaptureIdentityError):
    pass


class DatasetHashMismatchError(CaptureIdentityError):
    pass


# --------------------------------------------------------------------------- content


class IncompleteCaptureError(CaptureImportError):
    """The capture did not reach a final client answer for every entry and rank."""

    def __init__(
        self,
        message: str,
        *,
        not_final_by_status: Mapping[str, int] | None = None,
        not_final_by_class: Mapping[str, int] | None = None,
    ) -> None:
        super().__init__(message)
        self.not_final_by_status = dict(not_final_by_status or {})
        self.not_final_by_class = dict(not_final_by_class or {})


class CaptureCoverageError(CaptureImportError):
    """The captured records are not exactly the manifest's entry/rank pairs."""


class UnknownEntryError(CaptureCoverageError):
    pass


class RecordIdentityError(CaptureCoverageError):
    """A record's class / tree / node / spell differs from the manifest's entry."""


class RankOutOfRangeError(CaptureCoverageError):
    pass


class DuplicateRecordError(CaptureCoverageError):
    pass


class MissingRecordError(CaptureCoverageError):
    pass


class MalformedTooltipError(CaptureImportError):
    """A captured TooltipData does not have the minimal shape of a usable tooltip."""


class SemanticHashMismatchError(CaptureImportError):
    """A stored capture artifact's ``semanticHash`` does not match its content."""
