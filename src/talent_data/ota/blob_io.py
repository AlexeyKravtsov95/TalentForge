"""Bounded independent immutable I/O; caller retains all mutable coordination."""

from collections.abc import Callable, Iterable
from concurrent.futures import FIRST_COMPLETED, Future, ThreadPoolExecutor, wait


def run_bounded[T, R](
    items: Iterable[T], worker: Callable[[T], R], *, max_workers: int
) -> tuple[R, ...]:
    """Return input-ordered results, draining started work before any failure escapes.

    Only one window is submitted at a time. Once a failure is observed no more
    input is consumed. Among failed submitted jobs the lowest input index wins.
    """
    if type(max_workers) is not int or max_workers < 1:
        raise ValueError("max_workers must be a positive integer")
    iterator = iter(items)
    results: dict[int, R] = {}
    submitted: dict[Future[R], int] = {}
    pending: set[Future[R]] = set()
    input_error: BaseException | None = None
    failed = False
    exhausted = False
    pool = ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="ota-blob")
    try:
        while not exhausted or pending:
            while not exhausted and len(pending) < max_workers:
                try:
                    value = next(iterator)
                except StopIteration:
                    exhausted = True
                    break
                future = pool.submit(worker, value)
                submitted[future] = len(submitted)
                pending.add(future)
            if not pending:
                break
            done, pending = wait(pending, return_when=FIRST_COMPLETED)
            if any(future.exception() is not None for future in done):
                failed = True
                break
            for future in done:
                results[submitted[future]] = future.result()
    except BaseException as exc:  # noqa: BLE001 - drain even on cancellation/KeyboardInterrupt
        input_error = exc
        failed = True
    finally:
        if failed:
            for future in pending:
                future.cancel()
        pool.shutdown(wait=True, cancel_futures=failed)
        close = getattr(iterator, "close", None)
        if close is not None:
            try:
                close()
            except BaseException as exc:  # noqa: BLE001 - preserve the original task failure
                if input_error is None:
                    input_error = exc
    # Evaluate failures only after every started task has stopped.
    for future, index in sorted(submitted.items(), key=lambda item: item[1]):
        if not future.cancelled():
            error = future.exception()
            if error is not None:
                raise error
            results[index] = future.result()
    if input_error is not None:
        raise input_error
    return tuple(results[index] for index in range(len(results)))
