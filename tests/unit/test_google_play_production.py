"""Production delivery contract; all store requests use offline fakes."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

import httpx
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa

from tests.unit import test_android_store_delivery as apk_tests
from tests.unit import test_store_delivery as legacy

sys.path.insert(0, str(legacy.ROOT / "scripts"))
import android_release_notes as notes
import android_store_delivery as public
import google_play_production as p


class Play(legacy.FakePlay):
    def __init__(self, key: rsa.RSAPrivateKey) -> None:
        super().__init__(key)
        self.denied = False
        self.lost_commit = False
        self.validations = 0

    def handler(self, request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith(":validate"):
            self.validations += 1
            return httpx.Response(200, json={})
        if (
            self.denied
            and request.method == "PUT"
            and request.url.path.endswith("/tracks/production")
        ):
            return httpx.Response(
                403, json={"error": {"message": "insufficient production permission"}}
            )
        response = super().handler(request)
        if self.lost_commit and request.url.path.endswith(":commit"):
            raise httpx.ReadTimeout("lost response")
        return response

    def media(self, request: httpx.Request, full: str) -> httpx.Response:
        response = super().media(request, full)
        if request.method == "PUT":
            for edit in self.edits.values():
                if "3" in edit["bundles"]:
                    edit["bundles"]["6"] = edit["bundles"].pop("3")
            return httpx.Response(200, json={**response.json(), "versionCode": 6})
        return response

    def production(self, key: rsa.RSAPrivateKey) -> Any:
        return p.ProductionPlay(
            legacy.d.parse_service_account(legacy.service_account_json(key)),
            httpx.Client(transport=httpx.MockTransport(self.handler)),
            sleep=lambda _: None,
        )


@pytest.fixture
def delivery(tmp_path: Path) -> tuple[Any, ...]:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    fake = Play(key)
    aab = tmp_path / "release.aab"
    aab.write_bytes(b"approved bundle")
    manifest = {
        "versionCode": 6,
        "aabSha256": p.core.file_sha256(aab),
        "releaseNotes": {"ru-RU": "RU notes", "en-US": "EN notes"},
    }
    return fake, fake.production(key), aab, manifest, public.MemoryJournal()


def test_production_notes_commit_and_repeat_never_uploads_twice(delivery: tuple[Any, ...]) -> None:
    fake, api, aab, manifest, journal = delivery
    before_alpha = json.loads(json.dumps(fake.tracks["alpha"]))
    assert p.deliver(api, manifest, aab, journal, verify=False)["status"] == "SUBMITTED"
    assert p.deliver(api, manifest, aab, journal, verify=False)["status"] == "SUBMITTED"
    assert fake.tracks["alpha"] == before_alpha
    assert fake.tracks["production"][0]["releaseNotes"] == [
        {"language": k, "text": v} for k, v in manifest["releaseNotes"].items()
    ]
    assert sum(c.startswith("PUT session") for c in fake.calls) == 1
    assert journal.get("play-commit-done") == {"committed": True}


@pytest.mark.parametrize("track", ["alpha", "internal", "wear:production", "production"])
def test_occupied_code_in_any_track_prevents_upload(delivery: tuple[Any, ...], track: str) -> None:
    fake, api, aab, manifest, journal = delivery
    fake.tracks[track] = [{"versionCodes": ["6"], "status": "draft"}]
    with pytest.raises(public.DeliveryError, match="CONFLICT"):
        p.deliver(api, manifest, aab, journal, verify=False)
    assert not any("session" in c for c in fake.calls)


@pytest.mark.parametrize("code", [5, 7, "6", True, None])
def test_wrong_version_code_fails_before_api(delivery: tuple[Any, ...], code: Any) -> None:
    fake, api, aab, manifest, journal = delivery
    manifest["versionCode"] = code
    with pytest.raises(public.DeliveryError, match="GATE"):
        p.deliver(api, manifest, aab, journal, verify=False)
    assert fake.calls == []


def test_verify_does_not_upload_or_commit(delivery: tuple[Any, ...]) -> None:
    fake, api, aab, manifest, journal = delivery
    assert p.deliver(api, manifest, aab, journal, verify=True)["status"] == "VERIFIED"
    assert not any("session" in c or ":commit" in c for c in fake.calls)
    assert journal.records == {} and fake.edits == {}


def test_insufficient_production_permission_stops_before_upload(delivery: tuple[Any, ...]) -> None:
    fake, api, aab, manifest, journal = delivery
    fake.denied = True
    with pytest.raises(p.core.DeliveryError):
        p.deliver(api, manifest, aab, journal, verify=False)
    assert journal.records == {} and not any("session" in c for c in fake.calls)


def test_lost_commit_is_reconciled_without_reupload(delivery: tuple[Any, ...]) -> None:
    fake, api, aab, manifest, journal = delivery
    fake.lost_commit = True
    with pytest.raises(p.core.DeliveryError):
        p.deliver(api, manifest, aab, journal, verify=False)
    fake.lost_commit = False
    assert p.deliver(api, manifest, aab, journal, verify=False)["status"] == "SUBMITTED"
    assert sum(c.startswith("PUT session") for c in fake.calls) == 1


def test_rejected_commit_is_not_automatically_uploaded_again(delivery: tuple[Any, ...]) -> None:
    fake, api, aab, manifest, journal = delivery
    fake.commit_error = "draft app needs console setup"
    with pytest.raises(p.core.DeliveryError):
        p.deliver(api, manifest, aab, journal, verify=False)
    with pytest.raises(public.DeliveryError, match="UNKNOWN"):
        p.deliver(api, manifest, aab, journal, verify=False)
    assert sum(c.startswith("PUT session") for c in fake.calls) == 1


@pytest.mark.parametrize("status", ["draft", "inProgress", "halted", "unknown"])
def test_foreign_pending_production_is_preserved(delivery: tuple[Any, ...], status: str) -> None:
    fake, api, aab, manifest, journal = delivery
    release = {"versionCodes": ["4"], "status": status}
    fake.tracks["production"] = [release]
    with pytest.raises(public.DeliveryError):
        p.deliver(api, manifest, aab, journal, verify=False)
    assert fake.tracks["production"] == [release]
    assert not any("session" in c for c in fake.calls)


@pytest.mark.parametrize(
    "bad",
    [
        None,
        {},
        {"ru-RU": "text"},
        {"ru-RU": "", "en-US": "EN"},
        {"ru-RU": "RU", "en-US": "a" * 501},
    ],
)
def test_invalid_notes_fail_before_api(delivery: tuple[Any, ...], bad: Any) -> None:
    fake, api, aab, manifest, journal = delivery
    manifest["releaseNotes"] = bad
    with pytest.raises(p.core.DeliveryError):
        p.deliver(api, manifest, aab, journal, verify=False)
    assert fake.calls == []


@pytest.mark.parametrize("content", [None, b"\xff", b" \n", b"\xef\xbb\xbfBOM", b"bad\x00text"])
def test_missing_invalid_utf8_or_empty_file(tmp_path: Path, content: bytes | None) -> None:
    directory = tmp_path / notes.NOTES_DIR
    directory.mkdir(parents=True)
    (directory / "notes-en.txt").write_text("EN", encoding="utf-8")
    if content is not None:
        (directory / "notes-ru.txt").write_bytes(content)
    with pytest.raises(p.core.DeliveryError):
        notes.load(tmp_path, "1.1.0", 6, ("play",))


def test_real_release_files_and_store_limits() -> None:
    texts = notes.load(legacy.ROOT, "1.1.0", 6, tuple(notes.LIMITS))
    assert texts["ru-RU"] != texts["en-US"]
    notes.validate({"ru-RU": "r" * 5000, "en-US": "E"}, ("rustore",))
    for store in ("play", "appgallery"):
        with pytest.raises(p.core.DeliveryError):
            notes.validate({"ru-RU": "r" * 501, "en-US": "E"}, (store,))


@pytest.mark.parametrize(
    ("state", "user", "environment"),
    [
        ("rejected", "owner", 8),
        ("approved", "someone", 8),
        ("approved", "owner", 9),
    ],
)
def test_missing_owner_approval_is_a_gate_refusal(state: str, user: str, environment: int) -> None:
    class GitHub:
        repository = "owner/repo"

        def require_protected_approval(self, name: str) -> None:
            assert name == p.ENVIRONMENT

        def call(self, method: str, path: str) -> Any:
            if path.startswith("environments/"):
                return {"id": 8}
            return [
                {"state": state, "user": {"login": user}, "environments": [{"id": environment}]}
            ]

    with pytest.raises(public.DeliveryError, match="GATE"):
        p.require_approval(GitHub(), 123)


def test_combined_delivery_holds_apk_stores_when_production_approval_fails() -> None:
    results = {"gate": "success", "public-approval": "success", "play-production": "failure"}
    assert not legacy.public_job_selected("public-journal", results, target="production")
    assert legacy.public_job_selected(
        "public-journal", {**results, "play-production": "success"}, target="production"
    )


def test_localized_manifest_keeps_legacy_identity_and_uses_russian_notes() -> None:
    old = apk_tests.manifest()
    assert public.Release.parse(old).value == old
    value = {
        **old,
        "schema": "talentforge-android-public/2",
        "versionCode": 6,
        "versionName": "1.1.0",
        "whatsNew": "RU",
        "releaseNotes": {"ru-RU": "RU", "en-US": "EN"},
    }
    assert public.Release.parse(value).value["whatsNew"] == "RU"
    with pytest.raises(public.DeliveryError):
        public.Release.parse({**value, "whatsNew": "obsolete"})


@pytest.mark.parametrize("lost_language", [None, "ru-RU", "en-US"])
def test_appgallery_localized_notes_precede_submit_and_lost_receipt_stops(
    tmp_path: Path, lost_language: str | None
) -> None:
    old, apk = apk_tests.release_file(tmp_path)
    release = public.Release.parse(
        {
            **old.value,
            "schema": "talentforge-android-public/2",
            "versionCode": 6,
            "versionName": "1.1.0",
            "whatsNew": "RU",
            "releaseNotes": {"ru-RU": "RU", "en-US": "EN"},
        }
    )
    fake = apk_tests.HuaweiStore(release)
    fake.packages = [
        {"pkgVersion": "p6", "versionCode": 6, "shaCode": release.apk_hash, "packageSize": 12}
    ]
    fake.state.update(releaseState=7, versionCode=6, versionId="p6")
    writes = []

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("app-language-info"):
            value = json.loads(request.content)
            assert request.method == "PUT" and request.url.params["releaseType"] == "1"
            assert set(value) == {"lang", "newFeatures"}
            assert value["newFeatures"] == release.value["releaseNotes"][value["lang"]]
            writes.append(value["lang"])
            if value["lang"] == lost_language:
                raise httpx.ReadTimeout("accepted; response lost")
            return httpx.Response(200, json={"ret": {"code": 0}})
        if request.url.path.endswith("app-submit"):
            assert writes == ["ru-RU", "en-US"]
        return fake.handler(request)

    api = public.AppGallery(
        httpx.Client(transport=httpx.MockTransport(handler)), "119465493", "jwt"
    )
    journal = public.MemoryJournal()
    if lost_language:
        with pytest.raises(public.DeliveryError):
            public.advance_appgallery(api, release, apk, journal, now=1000)
        with pytest.raises(public.DeliveryError, match="UNKNOWN"):
            public.advance_appgallery(api, release, apk, journal, now=1200)
        assert fake.submissions == 0
        assert writes.count(lost_language) == 1
    else:
        assert (
            public.advance_appgallery(api, release, apk, journal, now=1000)["status"] == "IN_REVIEW"
        )
        assert (
            public.advance_appgallery(api, release, apk, journal, now=1200)["status"] == "IN_REVIEW"
        )
        assert fake.submissions == 1 and writes == ["ru-RU", "en-US"]
    assert fake.uploads == 0


def test_rustore_uses_current_russian_text_before_upload_error(tmp_path: Path) -> None:
    old, apk = apk_tests.release_file(tmp_path)
    release = public.Release.parse(
        {
            **old.value,
            "schema": "talentforge-android-public/2",
            "versionCode": 6,
            "versionName": "1.1.0",
            "whatsNew": "RU",
            "releaseNotes": {"ru-RU": "RU", "en-US": "EN"},
        }
    )
    store = apk_tests.RussianStore()

    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "POST" and request.url.path.endswith("/version"):
            assert json.loads(request.content)["whatsNew"] == "RU"
        if request.url.path.endswith("/apk"):
            return httpx.Response(503)
        return store.handler(request)

    api = public.RuStore(httpx.Client(transport=httpx.MockTransport(handler)), "token")
    journal = public.MemoryJournal()
    with pytest.raises(public.DeliveryError):
        public.advance_rustore(api, release, apk, journal)
    assert journal.get("rustore-create-done") == {"versionId": 9}
    assert journal.get("rustore-upload-intent") is not None
    assert store.submissions == 0


@pytest.mark.parametrize("bad", ["6", True, 6.0, None])
def test_production_reader_refuses_coerced_wire_codes(delivery: tuple[Any, ...], bad: Any) -> None:
    fake, api, *_ = delivery
    base_handler = fake.handler

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/bundles") and request.method == "GET":
            return httpx.Response(200, json={"bundles": [{"versionCode": bad, "sha256": "a" * 64}]})
        return base_handler(request)

    api.client = httpx.Client(transport=httpx.MockTransport(handler))
    edit = api.insert_edit()
    with pytest.raises(public.DeliveryError, match="CONTRACT"):
        api.state(edit)


def test_apk_audit_blocks_pending_version5_without_touching_its_journal(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    store = apk_tests.RussianStore()
    store.created = True
    api = public.RuStore(httpx.Client(transport=httpx.MockTransport(store.handler)), "token")
    monkeypatch.setattr(public, "store_api", lambda *_: api)
    result = public.audit_stores(api.client, ["rustore"], "119465493", 6)
    assert result["rustore"]["status"] == "CONFLICT"
    assert store.uploads == store.submissions == 0


def test_partial_stores_keep_independent_results() -> None:
    def advance(store: str) -> dict[str, str]:
        if store == "appgallery":
            raise public.DeliveryError("STORE", "notes rejected")
        return {"status": "IN_REVIEW"}

    result = public.each_store(["rustore", "appgallery"], advance)
    assert result["rustore"]["status"] == "IN_REVIEW"
    assert result["appgallery"]["status"] == "STORE"


def test_closed_testing_accepts_localized_notes_without_production_access(
    delivery: tuple[Any, ...],
) -> None:
    fake, api, aab, manifest, _ = delivery
    fake.denied = True
    result = legacy.d.play_deliver(
        api,
        gate=legacy.gate(android={"versionName": "1.1.0", "versionCode": 6}),
        aab=aab,
        track="alpha",
        release_status="completed",
        changes_not_sent_for_review=False,
        release_notes=manifest["releaseNotes"],
    )
    assert result["status"] == "DELIVERED" and fake.tracks["production"] == []
    assert len(fake.tracks["alpha"][0]["releaseNotes"]) == 2


def test_higher_code_and_changed_artifact_are_refused(delivery: tuple[Any, ...]) -> None:
    fake, api, aab, manifest, journal = delivery
    fake.bundles[7] = "f" * 64
    with pytest.raises(public.DeliveryError, match="CONFLICT"):
        p.deliver(api, manifest, aab, journal, verify=False)
    fake.calls.clear()
    aab.write_bytes(b"replacement")
    with pytest.raises(public.DeliveryError, match="MISMATCH"):
        p.deliver(api, manifest, aab, journal, verify=False)
    assert fake.calls == []


@pytest.mark.parametrize("bad", ["6", 6.9, True, None])
def test_upload_response_wrong_wire_type_never_commits(
    delivery: tuple[Any, ...],
    bad: Any,
) -> None:
    fake, api, aab, manifest, journal = delivery
    base_handler = fake.handler

    def handler(request: httpx.Request) -> httpx.Response:
        response = base_handler(request)
        if request.method == "PUT" and request.url.params.get("upload_id"):
            return httpx.Response(200, json={**response.json(), "versionCode": bad})
        return response

    api.client = httpx.Client(transport=httpx.MockTransport(handler))
    with pytest.raises(public.DeliveryError, match="CONTRACT"):
        p.deliver(api, manifest, aab, journal, verify=False)
    assert not any(":commit" in call for call in fake.calls)
    assert journal.get("play-upload-done") is None
    assert fake.tracks["production"] == []


@pytest.mark.parametrize("lost", [False, True])
def test_production_telegram_is_journaled_and_never_repeated(
    monkeypatch: pytest.MonkeyPatch,
    lost: bool,
) -> None:
    journal = public.MemoryJournal()

    class GitHub:
        def __init__(self, *_: Any) -> None:
            pass

        def pages(self, path: str) -> Any:
            assert path == "releases"
            return [
                {
                    "tag_name": "play-production-6",
                    "body": "a" * 64,
                    "id": 1,
                    "author": {"login": "github-actions[bot]"},
                }
            ]

    sent = []

    def send(*args: Any) -> Any:
        sent.append(args[-1])
        if lost:
            raise public.DeliveryError("UNKNOWN", "Telegram response lost")
        return {"status": "SENT"}

    for name in ("GITHUB_REPOSITORY", "GH_TOKEN", "TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID"):
        monkeypatch.setenv(name, "test")
    monkeypatch.setattr(public, "GitHub", GitHub)
    monkeypatch.setattr(public, "AssetJournal", lambda *_: journal)
    monkeypatch.setattr(public, "send_telegram", send)
    report = {"manifestSha256": "a" * 64, "status": "SUBMITTED", "detail": "Awaiting Google review"}
    for _ in range(2):
        if lost:
            with pytest.raises(public.DeliveryError, match="UNKNOWN"):
                p.notify(report, httpx.Client())
        else:
            assert p.notify(report, httpx.Client())["status"] == "SENT"
    assert len(sent) == 1


def test_release_delivery_changes_trigger_mobile_artifact_validation() -> None:
    workflow = (legacy.ROOT / ".github/workflows/mobile-ci.yml").read_text()
    for path in (
        "scripts/store_delivery.py",
        "scripts/android_store_delivery.py",
        "scripts/android_release_notes.py",
        "scripts/google_play_production.py",
        "docs/release/m7-android-1.1.0/notes-*.txt",
        ".github/workflows/store-delivery.yml",
    ):
        assert f'      - "{path}"' in workflow


@pytest.mark.parametrize("explicit", [False, True])
def test_scoped_reconciliation_never_loads_version5(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    explicit: bool,
) -> None:
    for key, value in {
        "GITHUB_REF": p.core.MAIN_REF,
        "GITHUB_REPOSITORY": "owner/repo",
        "GH_TOKEN": "token",
    }.items():
        monkeypatch.setenv(key, value)
    old = {"id": 5, "tag_name": "android-store-5"}
    new = {"id": 6, "tag_name": "android-store-6"}
    monkeypatch.setattr(public.GitHub, "pages", lambda *_: [old, new])
    monkeypatch.setattr(public.GitHub, "call", lambda *_: old)
    seen = []

    def reconcile(_github: Any, remotes: Any, *_: Any) -> Any:
        seen.extend(remotes)
        return {"releases": [], "errors": []}

    monkeypatch.setattr(public, "reconcile_releases", reconcile)
    args = ["reconcile", "--version-code", "6", "--out", str(tmp_path / "report.json")]
    if explicit:
        args += ["--release-id", "5"]
        with pytest.raises(public.DeliveryError, match="GATE"):
            public.run(public.parse_args(args))
        assert seen == []
    else:
        assert public.run(public.parse_args(args)) == 0
        assert seen == [new]
    workflow = (legacy.ROOT / ".github/workflows/android-store-reconcile.yml").read_text()
    assert 'if [ "$DELIVERY_COMMAND" = reconcile ]; then args+=(--version-code 6); fi' in workflow


@pytest.mark.parametrize("outcome", ["failure", "cancelled"])
def test_preflight_failure_report_exists_without_preflight_json(
    tmp_path: Path,
    outcome: str,
) -> None:
    manifest = {
        "schema": "talentforge-play-production/1",
        "sourceSha": "a" * 40,
        "workflowSha": "b" * 40,
        "versionCode": 6,
        "versionName": "1.1.0",
    }
    result = p.preflight_failure(manifest, outcome=outcome, run_id=123)
    assert result["status"] == "GATE" and result["phase"] == "ota-preflight"
    assert result["manifestSha256"] == p.digest(manifest)
    assert result["runId"] == 123
    assert "no upload" in result["detail"].lower()


def test_delivery_preflight_has_its_own_timeout_and_failed_report_step() -> None:
    jobs = legacy.workflow_jobs((legacy.ROOT / ".github/workflows/store-delivery.yml").read_text())
    for name in ("play-production", "public-deliver"):
        body = jobs[name]
        assert "timeout-minutes: 90" in body
        assert "timeout-minutes: 45" in body
        assert "--min-validity-days 30" in body and "--expect published" in body
        assert "id: ota-preflight" in body
    assert "preflight-failure" in jobs["play-production"]


@pytest.mark.parametrize("lost", [False, True])
def test_early_preflight_telegram_works_without_production_journal_and_deduplicates(
    monkeypatch: pytest.MonkeyPatch,
    lost: bool,
) -> None:
    manifest = {
        "schema": "talentforge-play-production/1",
        "sourceSha": "a" * 40,
        "workflowSha": "b" * 40,
        "versionCode": 6,
        "versionName": "1.1.0",
    }
    report = p.preflight_failure(manifest, outcome="failure", run_id=123)
    journal = public.MemoryJournal()
    releases: list[dict[str, Any]] = []
    calls = []

    class GitHub:
        repository = "owner/repo"

        def __init__(self, *_: Any) -> None:
            pass

        def pages(self, path: str) -> Any:
            assert path == "releases"
            return releases

        def call(self, method: str, path: str, **kwargs: Any) -> Any:
            assert (method, path) == ("POST", "releases")
            value = kwargs["json"]
            assert value["tag_name"] == "play-preflight-alert-123"
            assert value["draft"] is True
            releases.append({**value, "id": 91, "author": {"login": "github-actions[bot]"}})
            return releases[0]

    def send(*args: Any) -> Any:
        calls.append(args[-1])
        assert "BLOCKED (OTA preflight)" in args[-1] and "no upload attempted" in args[-1]
        assert "https://github.com/owner/repo/actions/runs/123" in args[-1]
        if lost:
            raise public.DeliveryError("UNKNOWN", "response lost")
        return {"status": "SENT"}

    for key in ("GITHUB_REPOSITORY", "GH_TOKEN", "TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID"):
        monkeypatch.setenv(key, "test")
    monkeypatch.setattr(public, "GitHub", GitHub)
    monkeypatch.setattr(public, "AssetJournal", lambda *_: journal)
    monkeypatch.setattr(public, "send_telegram", send)
    for _ in range(2):
        if lost:
            with pytest.raises(public.DeliveryError, match="UNKNOWN"):
                p.notify(report, httpx.Client())
        else:
            assert p.notify(report, httpx.Client())["status"] == "SENT"
    assert len(calls) == len(releases) == 1
    assert journal.get("telegram-preflight-intent") == report


def test_preflight_failure_cli_preserves_report_when_ota_report_is_missing(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    manifest = {
        "schema": "talentforge-play-production/1",
        "sourceSha": "a" * 40,
        "workflowSha": "b" * 40,
        "versionCode": 6,
        "versionName": "1.1.0",
    }
    (tmp_path / "production-manifest.json").write_text(json.dumps(manifest))
    monkeypatch.setenv("APPROVED_PRODUCTION_SHA256", p.digest(manifest))
    monkeypatch.setenv("GITHUB_RUN_ID", "123")
    out = tmp_path / "production.json"
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "google_play_production.py",
            "preflight-failure",
            "--repo",
            str(tmp_path),
            "--directory",
            str(tmp_path),
            "--outcome",
            "failure",
            "--out",
            str(out),
        ],
    )
    assert p.main() == 0
    result = json.loads(out.read_text())
    assert result["status"] == "GATE" and result["phase"] == "ota-preflight"
    assert not (tmp_path / "production-ota-preflight.json").exists()


def test_report_artifacts_refresh_on_retry_and_apk_failures_have_a_report(tmp_path: Path) -> None:
    import os
    import subprocess

    jobs = legacy.workflow_jobs((legacy.ROOT / ".github/workflows/store-delivery.yml").read_text())
    body = jobs["public-deliver"]
    section = body.split("      - name: Preserve APK preflight refusal for Telegram", 1)[1].split(
        "      - name:", 1
    )[0]
    script = section.split("        run: |\n", 1)[1]
    script = "\n".join(line[10:] for line in script.splitlines())
    subprocess.run(
        ["bash", "-c", script], cwd=tmp_path, check=True, env={**os.environ, "RELEASE_ID": "666"}
    )
    report = json.loads((tmp_path / "delivery/public/report.json").read_text())
    assert report["releases"] == []
    assert report["errors"][0]["status"] == "GATE" and report["errors"][0]["releaseId"] == 666
    assert "no APK store upload" in report["errors"][0]["detail"]
    for job in ("play-production", "play-production-notify", "public-deliver"):
        assert "overwrite: true # operational report" in jobs[job]
    assert "overwrite:" not in jobs["public-approval"]


def test_scheduled_reconcile_cannot_bypass_failed_preflight() -> None:
    workflow = (legacy.ROOT / ".github/workflows/android-store-reconcile.yml").read_text()
    job = legacy.workflow_jobs(workflow)["reconcile"]
    assert job.index("--expect published") < job.index("- name: Inspect or reconcile")
    assert "--min-validity-days 30" in job
    assert "timeout-minutes: 90" in job and "timeout-minutes: 45" in job
    assert "if: ${{ !inputs.inspect_only }}" in job
    assert "preflight-failure" in job


def test_scheduled_preflight_refusal_reports_only_version6(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    for key, value in {
        "GITHUB_REF": p.core.MAIN_REF,
        "GITHUB_REPOSITORY": "owner/repo",
        "GH_TOKEN": "token",
    }.items():
        monkeypatch.setenv(key, value)
    monkeypatch.setattr(
        public.GitHub,
        "pages",
        lambda *_: [
            {"id": 5, "tag_name": "android-store-5"},
            {"id": 6, "tag_name": "android-store-6"},
        ],
    )
    monkeypatch.setattr(
        public, "store_api", lambda *_: pytest.fail("no store access during failure reporting")
    )
    out = tmp_path / "report.json"
    assert (
        public.run(
            public.parse_args(["preflight-failure", "--version-code", "6", "--out", str(out)])
        )
        == 0
    )
    report = json.loads(out.read_text())
    assert report["releases"] == []
    assert report["errors"] == [
        {
            "releaseId": 6,
            "status": "GATE",
            "detail": (
                "OTA preflight failed or timed out; no APK store upload attempted by this job. "
                "Inspect the preflight logs."
            ),
        }
    ]


@pytest.mark.parametrize(
    "change",
    [{"schema": "unknown"}, {"status": "SUBMITTED"}, {"phase": "upload"}, {"unexpected": True}],
)
def test_invalid_early_report_cannot_send_or_claim_publication(
    monkeypatch: pytest.MonkeyPatch,
    change: dict[str, Any],
) -> None:
    manifest = {
        "schema": "talentforge-play-production/1",
        "sourceSha": "a" * 40,
        "workflowSha": "b" * 40,
        "versionCode": 6,
        "versionName": "1.1.0",
    }
    report = {**p.preflight_failure(manifest, outcome="failure", run_id=123), **change}
    for key in ("GITHUB_REPOSITORY", "GH_TOKEN", "TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID"):
        monkeypatch.setenv(key, "test")
    monkeypatch.setenv("GITHUB_REPOSITORY", "owner/repo")
    monkeypatch.setattr(
        public.GitHub, "pages", lambda *_: pytest.fail("invalid report must not access journal")
    )
    monkeypatch.setattr(
        public, "send_telegram", lambda *_: pytest.fail("invalid report must not send")
    )
    with pytest.raises(public.DeliveryError, match="CONTRACT"):
        p.notify(report, httpx.Client())
