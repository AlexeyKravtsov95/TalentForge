"""Optional, process-local OTA object I/O observation; never authorization evidence."""

import re
from collections.abc import Callable, Iterator, Mapping
from contextlib import AbstractContextManager, contextmanager
from contextvars import ContextVar
from functools import wraps
from typing import Literal, Protocol

Operation = Literal["GET", "HEAD", "PUT"]
Phase = Literal[
    "CONFIG",
    "INVOCATION",
    "JOURNAL",
    "PUBLIC_CATALOG",
    "RECOVERY",
    "PUBLIC_VERIFY",
    "PLANNING",
    "AUTHORIZATION",
    "PROVENANCE",
    "APPROVAL_GATES",
    "PUBLISH",
    "UPLOAD",
    "RENEWAL",
    "SIGNER",
    "CATALOG_WRITE",
    "PRIVATE_READBACK",
    "PRIVATE_REFERENCES",
    "CLEANUP",
    "QUEUE",
    "CI",
    "PREFLIGHT",
    "NOTIFICATION",
]


class Observer(Protocol):
    def count(
        self, operation: Operation, *, bytes: int, retry: bool = False, sha_ref: str | None = None
    ) -> None: ...
    def add_bytes(self, amount: int) -> None: ...
    def incomplete(self) -> None: ...
    def phase(self, name: Phase) -> AbstractContextManager[None]: ...

    def record_failure(
        self, diagnostic: Mapping[str, str | int | float | None], stage: Phase
    ) -> None: ...


_observer: ContextVar[Observer | None] = ContextVar("ota_io_observer", default=None)
_phase: ContextVar[Phase] = ContextVar("ota_io_phase", default="CONFIG")


@contextmanager
def observe(observer: Observer) -> Iterator[None]:
    token = _observer.set(observer)
    try:
        yield
    finally:
        _observer.reset(token)


def count(
    operation: Operation, *, bytes: int, retry: bool = False, sha_ref: str | None = None
) -> None:
    if observer := _observer.get():
        observer.count(operation, bytes=bytes, retry=retry, sha_ref=sha_ref)


def add_bytes(amount: int) -> None:
    if observer := _observer.get():
        observer.add_bytes(amount)


def incomplete() -> None:
    if observer := _observer.get():
        observer.incomplete()


def object_hash(key: str) -> str | None:
    match = re.fullmatch(r"(?:.*/)?v1/blobs/sha256/([0-9a-f]{64})", key)
    return "sha256:" + match[1] if match else None


def record_failure(diagnostic: Mapping[str, str | int | float | None]) -> None:
    if observer := _observer.get():
        observer.record_failure(diagnostic, _phase.get())


@contextmanager
def phase(name: Phase) -> Iterator[None]:
    observer = _observer.get()
    if observer is None:
        yield
    else:
        token = _phase.set(name)
        try:
            with observer.phase(name):
                yield
        finally:
            _phase.reset(token)


def instrument[**P, R](name: Phase) -> Callable[[Callable[P, R]], Callable[P, R]]:
    def decorate(function: Callable[P, R]) -> Callable[P, R]:
        @wraps(function)
        def wrapped(*args: P.args, **kwargs: P.kwargs) -> R:
            with phase(name):
                return function(*args, **kwargs)

        return wrapped

    return decorate
