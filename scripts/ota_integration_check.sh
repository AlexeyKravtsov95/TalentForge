#!/usr/bin/env bash
# OTA integration check (M5.OTA.4a): the Python publisher and the Kotlin client, together, offline.
#
#   1. python      ruff check, ruff format --check, mypy, pytest (incl. the staging stand tests)
#   2. drift       bundled datasets and the OTA fixture mirror equal their canonical sources
#   3. stand       scripts/ota_staging_stand.py prepare: the real publisher CLI with an ephemeral
#                  tf-staging-local-* key and TLS chain, in a fresh directory OUTSIDE the repository
#   4. interop     scripts/ota_publisher_interop.py: the production rehearsal with TEST keys
#   5. kotlin      :shared:jvmTest and :composeApp:desktopTest with both stores, so
#                  OtaStagingE2ETest (HTTPS, staging key, OtaConfig.resolve) and
#                  OtaPublisherInteropTest run instead of being skipped
#   6. verdict     both opt-in suites ran with 0 skipped and 0 failed
#
# Options: --android also runs :composeApp:assembleDebug; --keep keeps the work directory
# (the stand can then be served to a device, docs/ota/android-device-smoke.md).
# Environment: PYTHON (default python3, with the dev extra installed). The checkout's own
# src/ is put first on PYTHONPATH, so another editable install cannot shadow it.
#
# Nothing here touches the network, a production key or a remote store. The stand's private
# keys live only in the work directory, which is deleted on exit unless --keep is given.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON="${PYTHON:-python3}"
ANDROID=0
KEEP=0
for arg in "$@"; do
    case "$arg" in
        --android) ANDROID=1 ;;
        --keep) KEEP=1 ;;
        *) echo "usage: $0 [--android] [--keep]" >&2; exit 2 ;;
    esac
done

export PYTHONPATH="$ROOT/src${PYTHONPATH:+:$PYTHONPATH}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/tf-ota-check.XXXXXX")"
if [[ "$KEEP" == 1 ]]; then
    trap 'echo "work directory kept: $WORK"' EXIT
else
    trap 'rm -rf "$WORK"' EXIT
fi

step() { printf '\n=== %s\n' "$*"; }

cd "$ROOT"
step "1/6 python: ruff, format, mypy, pytest"
"$PYTHON" -m ruff check .
"$PYTHON" -m ruff format --check .
"$PYTHON" -m mypy
"$PYTHON" -m pytest -q

step "2/6 drift: bundled datasets, OTA fixture mirror"
"$PYTHON" scripts/sync_mobile_datasets.py --check
"$PYTHON" scripts/sync_mobile_ota_fixtures.py --check

step "3/6 stand: real publisher, staging key, HTTPS chain ($WORK/stand)"
"$PYTHON" scripts/ota_staging_stand.py prepare "$WORK/stand"

step "4/6 interop: production rehearsal with TEST keys ($WORK/interop)"
"$PYTHON" scripts/ota_publisher_interop.py "$WORK/interop"

step "5/6 kotlin: shared JVM tests, desktop tests incl. the staging E2E and the interop"
GRADLE_TASKS=(:shared:jvmTest :composeApp:desktopTest)
if [[ "$ANDROID" == 1 ]]; then GRADLE_TASKS+=(:composeApp:assembleDebug); fi
(
    cd mobile
    ./gradlew "${GRADLE_TASKS[@]}" --console=plain \
        "-Dtalentforge.ota.staging=$WORK/stand" "-Dtalentforge.ota.interop=$WORK/interop"
)

step "6/6 verdict: the opt-in suites ran"
RESULTS="mobile/composeApp/build/test-results/desktopTest"
for suite in OtaStagingE2ETest OtaPublisherInteropTest; do
    xml="$RESULTS/TEST-io.github.alexeykravtsov95.talentforge.ui.ota.$suite.xml"
    [[ -f "$xml" ]] || { echo "FAIL $suite: no test report ($xml)" >&2; exit 1; }
    header="$(grep -m1 '<testsuite ' "$xml")"
    tests="$(sed -E 's/.* tests="([0-9]+)".*/\1/' <<<"$header")"
    skipped="$(sed -E 's/.* skipped="([0-9]+)".*/\1/' <<<"$header")"
    failed="$(sed -E 's/.* failures="([0-9]+)".*/\1/' <<<"$header")"
    errors="$(sed -E 's/.* errors="([0-9]+)".*/\1/' <<<"$header")"
    if [[ "$tests" == 0 || "$skipped" != 0 || "$failed" != 0 || "$errors" != 0 ]]; then
        echo "FAIL $suite: tests=$tests skipped=$skipped failures=$failed errors=$errors" >&2
        exit 1
    fi
    echo "PASS $suite: $tests tests, 0 skipped, 0 failed"
done
echo
cat "$WORK/stand/kotlin-e2e-results.txt"
echo
echo "OTA integration check passed."
