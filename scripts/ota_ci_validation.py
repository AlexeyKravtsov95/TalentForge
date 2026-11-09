"""Trusted workflow entry point for full/metadata CI selection."""

from talent_data.ota.automation.ci_validation import main

if __name__ == "__main__":
    raise SystemExit(main())
