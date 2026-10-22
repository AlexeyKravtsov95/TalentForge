"""Automatic preparation of a new Forever build for a later OTA release (M5.OTA.AUTO.1).

``detect`` decides whether a build needs preparing (the wago.tools build index through the
existing freshness check, the app's ``PRODUCT_BUNDLE`` and the *signed* production catalog);
``orchestrate`` runs the existing pipeline commands and the existing publisher gates over
the candidate in a disposable clone and writes a machine-readable report; ``runner`` is the
command line used by ``.github/workflows/ota-prepare.yml``. Nothing here publishes, signs,
commits to a shared branch or changes the app: the result is a set of files for review.
Runbook: ``docs/ota/auto-prepare.md``.
"""
