"""Bounded immutable work must stop submission and drain before returning failure."""

from concurrent.futures import ThreadPoolExecutor
from threading import Barrier, Event, Lock

import pytest


def test_bounded_order_and_overlap():
    from talent_data.ota.blob_io import run_bounded

    barrier = Barrier(2, timeout=5)
    active = peak = 0
    lock = Lock()

    def worker(value):
        nonlocal active, peak
        with lock:
            active += 1
            peak = max(peak, active)
        try:
            barrier.wait()
            return value * 2
        finally:
            with lock:
                active -= 1

    assert run_bounded(range(6), worker, max_workers=2) == (0, 2, 4, 6, 8, 10)
    assert peak == 2 and active == 0


def test_failure_stops_submission_and_drains_started_worker():
    from talent_data.ota.blob_io import run_bounded

    started, failed, release, finished, closed = (Event() for _ in range(5))
    consumed = []

    def values():
        try:
            for value in range(100):
                consumed.append(value)
                yield value
        finally:
            closed.set()

    def worker(value):
        if value == 0:
            started.set()
            assert release.wait(5)
            finished.set()
            raise ValueError("first input failure")
        assert started.wait(5)
        failed.set()
        raise RuntimeError("second input failure")

    with ThreadPoolExecutor(max_workers=1) as observer:
        result = observer.submit(run_bounded, values(), worker, max_workers=2)
        try:
            assert failed.wait(5)
            assert not result.done()
            assert consumed == [0, 1]
        finally:
            release.set()
        with pytest.raises(ValueError, match="first input failure"):
            result.result(timeout=5)
    assert finished.is_set() and closed.is_set()


@pytest.mark.parametrize("workers", [1, 2, 8])
def test_serial_equivalence(workers):
    from talent_data.ota.blob_io import run_bounded

    assert run_bounded(range(9), str, max_workers=workers) == tuple(map(str, range(9)))
    assert run_bounded([], str, max_workers=workers) == ()


@pytest.mark.parametrize("workers", [0, -1, True, 1.5])
def test_invalid_limit_does_not_consume_input(workers):
    from talent_data.ota.blob_io import run_bounded

    def values():
        pytest.fail("invalid limit consumed input")
        yield 0

    with pytest.raises(ValueError):
        run_bounded(values(), str, max_workers=workers)


def test_input_failure_also_drains_workers():
    from talent_data.ota.blob_io import run_bounded

    done = Event()

    def values():
        yield 1
        assert done.wait(5)
        raise LookupError("input failed")

    def worker(value):
        done.set()
        return value

    with pytest.raises(LookupError, match="input failed"):
        run_bounded(values(), worker, max_workers=2)
    # Started work can finish or be cancelled, but no task remains after return.
    assert done.is_set()
