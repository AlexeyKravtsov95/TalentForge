"""Scheduled dataset freshness watcher (M5.DATA.WATCH).

The watcher turns one ``product-freshness`` v1 result (``talent_data.freshness``, produced
by ``scripts/check_product_freshness.py``) into the state of a single tracked GitHub
Issue. It only reports: it never changes datasets, ``PRODUCT_BUNDLE``, ``BUNDLED_BUILDS``,
saved builds, branches or pull requests.

* :mod:`.report` - strict reader of the check's JSON and exit code;
* :mod:`.tracking` - the tracked Issue's state and the pure open/update/close decision;
* :mod:`.github` - the minimal GitHub Issues REST client;
* :mod:`.runner` - command line entry point used by ``.github/workflows/dataset-freshness.yml``.
"""
