#!/usr/bin/env python3
"""Approved Google Play production edits, isolated from the legacy closed-test path."""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import subprocess
import sys
from contextlib import suppress
from pathlib import Path
from typing import Any

import android_release_notes as notes
import android_store_delivery as public
import httpx
import store_delivery as core

ENVIRONMENT = "google-play-production"


def release_notes(repo: Path, gate: dict[str, Any], revision: str | None = None) -> dict[str, str]:
    version = gate["android"]
    if revision is None:
        return notes.load(repo, version["versionName"], version["versionCode"], tuple(notes.LIMITS))
    if (
        version != {"versionName": "1.1.0", "versionCode": 6}
        or type(version["versionCode"]) is not int
    ):
        raise public.DeliveryError("GATE", "production requires Android 1.1.0 (6)")
    result = {}
    for language, short in zip(notes.LANGUAGES, ("ru", "en"), strict=True):
        data = subprocess.run(
            ["git", "-C", str(repo), "show", f"{revision}:{notes.NOTES_DIR}/notes-{short}.txt"],
            check=True,
            capture_output=True,
        ).stdout
        result[language] = data.decode("utf-8").strip()
    return notes.validate(result, tuple(notes.LIMITS))


def prepare(gate: dict[str, Any], directory: Path, repo: Path) -> dict[str, Any]:
    localized = release_notes(repo, gate)
    base = directory / "TalentForge-1.1.0-6"
    apk, aab = Path(str(base) + ".apk"), Path(str(base) + ".aab")
    check = core.check_android_release(
        gate=gate,
        apk=apk,
        aab=aab,
        apk_report=core.read_json_object(directory / "apk-report.json"),
        aab_report=core.read_json_object(directory / "aab-report.json"),
    )
    return {
        "schema": "talentforge-play-production/1",
        "sourceSha": gate["sourceSha"],
        "workflowSha": gate["workflowSha"],
        "applicationId": core.APPLICATION_ID,
        "versionName": "1.1.0",
        "versionCode": 6,
        "apkSha256": core.file_sha256(apk),
        "aabSha256": core.file_sha256(aab),
        "releaseNotes": localized,
        "check": check,
    }


def digest(manifest: dict[str, Any]) -> str:
    return hashlib.sha256(public.canonical(manifest)).hexdigest()


def release_name(manifest: dict[str, Any]) -> str:
    # Stay below the Console release-name bound; full identity remains in the journal.
    return "TalentForge 1.1.0 (6) " + digest(manifest)[:24]


def require_approval(github: public.GitHub, run_id: int) -> None:
    github.require_protected_approval(ENVIRONMENT)
    environment = public.obj(github.call("GET", "environments/" + ENVIRONMENT))
    eid = public.integer(environment.get("id"), minimum=1)
    reviews = public.items(github.call("GET", f"actions/runs/{run_id}/approvals"))
    owner = github.repository.split("/")[0].lower()
    matching = [
        r for r in reviews if any(e.get("id") == eid for e in public.items(r.get("environments")))
    ]
    if (
        not matching
        or any(r.get("state") == "rejected" for r in matching)
        or not any(
            r.get("state") == "approved"
            and public.obj(r.get("user")).get("login", "").lower() == owner
            for r in matching
        )
    ):
        raise public.DeliveryError("GATE", "repository owner has not approved Production")


class ProductionPlay(core.GooglePlay):
    """Preserve wire types before parsing; the legacy closed-test reader is unchanged."""

    def bundle_identity(self, body: Any) -> tuple[int, str]:
        value = public.obj(body)
        if set(value) - {"versionCode", "sha256", "sha1"}:
            raise public.DeliveryError("CONTRACT", "unknown Play upload response fields")
        code = public.integer(value.get("versionCode"), minimum=1)
        sha = public.string(value.get("sha256"))
        if not public.HASH.fullmatch(sha):
            raise public.DeliveryError("CONTRACT", "invalid Play upload digest")
        return code, sha

    def state(self, edit: str) -> core.PlayState:
        binaries = []
        for resource in ("bundles", "apks"):
            body = public.obj(self._call("GET", f"edits/{edit}/{resource}", retry=True).json())
            if set(body) - {"kind", resource}:
                raise public.DeliveryError("CONTRACT", "unknown Play binary response fields")
            rows = public.items(body.get(resource, []))
            parsed = {}
            for row in rows:
                allowed = (
                    {"versionCode", "sha1", "sha256"}
                    if resource == "bundles"
                    else {"versionCode", "binary"}
                )
                if set(row) - allowed:
                    raise public.DeliveryError("CONTRACT", "unknown Play binary fields")
                code = public.integer(row.get("versionCode"), minimum=1)
                if code in parsed:
                    raise public.DeliveryError("CONTRACT", "duplicate Play binary code")
                binary = row if resource == "bundles" else public.obj(row.get("binary"))
                parsed[code] = public.string(binary.get("sha256"))
            binaries.append(parsed)
        body = public.obj(self._call("GET", f"edits/{edit}/tracks", retry=True).json())
        if set(body) - {"kind", "tracks"}:
            raise public.DeliveryError("CONTRACT", "unknown/incomplete Play tracks response")
        tracks = {}
        for row in public.items(body.get("tracks", [])):
            track = public.string(row.get("track"))
            if set(row) - {"track", "releases"} or track in tracks:
                raise public.DeliveryError("CONTRACT", "unknown/duplicate Play track")
            releases = public.items(row.get("releases", []))
            for release in releases:
                if set(release) - {
                    "name",
                    "versionCodes",
                    "releaseNotes",
                    "status",
                    "userFraction",
                    "countryTargeting",
                    "inAppUpdatePriority",
                    "controls",
                }:
                    raise public.DeliveryError("CONTRACT", "unknown Play release fields")
            tracks[track] = releases
        state = core.PlayState(binaries[0], binaries[1], tracks)
        validate_state(state)
        return state


def validate_state(state: core.PlayState) -> set[int]:
    codes = set(state.bundles) | set(state.apks)
    for code, sha in {**state.apks, **state.bundles}.items():
        public.integer(code, minimum=1)
        if not isinstance(sha, str) or not public.HASH.fullmatch(sha):
            raise public.DeliveryError("CONTRACT", "invalid Google Play binary digest")
    for track, releases in state.tracks.items():
        public.string(track)
        for release in public.items(releases):
            if release.get("status") not in {"draft", "completed", "inProgress", "halted"}:
                raise public.DeliveryError("CONTRACT", "unknown Google Play release status")
            values = release.get("versionCodes")
            if not isinstance(values, list) or not values:
                raise public.DeliveryError("CONTRACT", "missing Google Play release codes")
            for code in values:
                if not isinstance(code, str) or not re.fullmatch(r"[1-9][0-9]*", code):
                    raise public.DeliveryError("CONTRACT", "invalid Google Play track versionCode")
                codes.add(int(code))
    if "production" not in state.tracks:
        raise public.DeliveryError("CONFIG", "Google Play production track is unavailable")
    return codes


def observe(
    state: core.PlayState, manifest: dict[str, Any], journal: public.Journal
) -> dict[str, Any]:
    codes = validate_state(state)
    code = manifest["versionCode"]
    if any(c > code for c in codes):
        raise public.DeliveryError("CONFLICT", "Google Play contains a higher versionCode")
    matches = [r for r in state.tracks["production"] if str(code) in r["versionCodes"]]
    if matches:
        expected = [{"language": k, "text": v} for k, v in manifest["releaseNotes"].items()]
        release = matches[0]
        if (
            len(matches) != 1
            or journal.get("play-commit-intent") is None
            or state.bundles.get(code) != manifest["aabSha256"]
            or release.get("name") != release_name(manifest)
            or sorted(release.get("releaseNotes", []), key=lambda x: x["language"])
            != sorted(expected, key=lambda x: x["language"])
        ):
            raise public.DeliveryError(
                "CONFLICT", "production release is not this approved delivery"
            )
        status = release["status"]
        if status != "completed":
            raise public.DeliveryError(
                "CONFLICT", "production draft/rollout requires owner inspection"
            )
        return {
            "status": "SUBMITTED",
            "detail": "Committed to production; review/publication is controlled by Google",
        }
    if code in codes:
        raise public.DeliveryError(
            "CONFLICT", "versionCode consumed in another track or unassigned binary"
        )
    if any(r["status"] != "completed" for r in state.tracks["production"]):
        raise public.DeliveryError(
            "CONFLICT", "foreign production draft or rollout; nothing replaced"
        )
    if journal.get("play-upload-intent") or journal.get("play-commit-intent"):
        raise public.DeliveryError(
            "UNKNOWN", "previous Play write uncertain; inspect Console, never upload again"
        )
    return {"status": "AVAILABLE"}


def deliver(
    play: core.GooglePlay,
    manifest: dict[str, Any],
    aab: Path,
    journal: public.Journal,
    *,
    verify: bool,
) -> dict[str, Any]:
    if manifest["versionCode"] != 6 or type(manifest["versionCode"]) is not int:
        raise public.DeliveryError("GATE", "production versionCode must be 6")
    notes.validate(manifest["releaseNotes"], ("play",))
    if core.file_sha256(aab) != manifest["aabSha256"]:
        raise public.DeliveryError("MISMATCH", "bundle differs from approved artifact")
    edit = play.insert_edit()
    committed = False
    try:
        state = play.state(edit)
        result = observe(state, manifest, journal)
        if result["status"] != "AVAILABLE":
            return result
        # An uncommitted no-op PUT probes production write permission BEFORE any upload.
        # Never commit this probe or silently fall back to draft/review bypass on API errors.
        play._call(
            "PUT",
            f"edits/{edit}/tracks/production",
            json={
                "track": "production",
                "releases": state.tracks["production"],
            },
        )
        play._call("POST", f"edits/{edit}:validate")
        if verify:
            return {
                "status": "VERIFIED",
                "detail": "Production permission and version checks passed; no upload",
            }
        public.write_once(
            journal,
            "play-upload",
            {"manifest": digest(manifest)},
            lambda: upload(play, edit, aab, manifest),
        )
        release = {
            "name": release_name(manifest),
            "versionCodes": ["6"],
            "status": "completed",
            "releaseNotes": [
                {"language": k, "text": v} for k, v in manifest["releaseNotes"].items()
            ],
        }
        # Track writes affect only this private edit. Pending foreign releases were refused above.
        play.set_track(edit, "production", release)
        play._call("POST", f"edits/{edit}:validate")
        public.write_once(
            journal, "play-commit", {"manifest": digest(manifest)}, lambda: commit(play, edit)
        )
        committed = True
    finally:
        if not committed:
            with suppress(core.DeliveryError):
                play.delete_edit(edit)
    check = play.insert_edit()
    try:
        return observe(play.state(check), manifest, journal)
    finally:
        play.delete_edit(check)


def upload(play: core.GooglePlay, edit: str, aab: Path, manifest: dict[str, Any]) -> dict[str, Any]:
    code, sha = play.upload_bundle(edit, aab)
    if (code, sha) != (6, manifest["aabSha256"]):
        raise public.DeliveryError("MISMATCH", "Google Play upload identity differs")
    return {"versionCode": code, "sha256": sha}


def commit(play: core.GooglePlay, edit: str) -> dict[str, Any]:
    play.commit(edit, changes_not_sent_for_review=False)
    return {"committed": True}


def journal_for(
    github: public.GitHub, manifest: dict[str, Any], *, create: bool = True
) -> public.Journal:
    tag = "play-production-6"
    found = [r for r in github.pages("releases") if r.get("tag_name") == tag]
    if len(found) > 1:
        raise public.DeliveryError("CONFLICT", "duplicate Play production journal")
    if not found and not create:
        return public.MemoryJournal()
    remote = (
        found[0]
        if found
        else public.obj(
            github.call(
                "POST",
                "releases",
                json={
                    "tag_name": tag,
                    "target_commitish": manifest["sourceSha"],
                    "body": digest(manifest),
                    "name": "Google Play production 1.1.0 (6)",
                    "draft": True,
                    "prerelease": False,
                },
            )
        )
    )
    if (
        remote.get("body") != digest(manifest)
        or remote.get("target_commitish") != manifest["sourceSha"]
        or public.obj(remote.get("author")).get("login") != "github-actions[bot]"
    ):
        raise public.DeliveryError(
            "CONFLICT", "Play production journal belongs to another delivery"
        )
    result = public.AssetJournal(
        github,
        public.integer(remote.get("id"), minimum=1),
        {
            "schema": "talentforge-play-checkpoint/1",
            "manifest": digest(manifest),
        },
    )
    if create:
        result.put_asset("manifest.json", public.canonical(manifest), "application/json")
    return result


def preflight_failure(manifest: dict[str, Any], *, outcome: str, run_id: int) -> dict[str, Any]:
    """An operational refusal, never a publication receipt or permission to upload."""
    if outcome not in {"failure", "cancelled"}:
        raise public.DeliveryError("CONFIG", "preflight failure needs a failed step outcome")
    public.integer(run_id, minimum=1)
    if (
        manifest.get("schema") != "talentforge-play-production/1"
        or manifest.get("versionName") != "1.1.0"
        or type(manifest.get("versionCode")) is not int
        or manifest["versionCode"] != 6
    ):
        raise public.DeliveryError("CONTRACT", "invalid preflight alert release")
    for key in ("sourceSha", "workflowSha"):
        if not core.FULL_SHA.fullmatch(public.string(manifest.get(key))):
            raise public.DeliveryError("CONTRACT", "invalid preflight alert revision")
    return {
        "schema": "talentforge-play-preflight-failure/1",
        "phase": "ota-preflight",
        "status": "GATE",
        "outcome": outcome,
        "runId": run_id,
        "manifestSha256": digest(manifest),
        "manifest": manifest,
        "detail": f"Production OTA preflight {outcome}; no upload attempted. "
        "Full OTA verification must pass before delivery; inspect the preflight logs.",
    }


def preflight_alert_journal(github: public.GitHub, report: dict[str, Any]) -> public.AssetJournal:
    # Separate from play-production-6: an early refusal must not create/adopt a release journal.
    expected = preflight_failure(
        public.obj(report.get("manifest")),
        outcome=public.string(report.get("outcome")),
        run_id=public.integer(report.get("runId"), minimum=1),
    )
    if report != expected:
        raise public.DeliveryError("CONTRACT", "invalid preflight failure report")
    tag = f"play-preflight-alert-{report['runId']}"
    found = [r for r in github.pages("releases") if r.get("tag_name") == tag]
    if len(found) > 1:
        raise public.DeliveryError("CONFLICT", "duplicate preflight alert journal")
    remote = (
        found[0]
        if found
        else public.obj(
            github.call(
                "POST",
                "releases",
                json={
                    "tag_name": tag,
                    "target_commitish": report["manifest"]["workflowSha"],
                    "name": f"Blocked Play delivery (run {report['runId']})",
                    "body": report["manifestSha256"],
                    "draft": True,
                    "prerelease": False,
                },
            )
        )
    )
    if (
        remote.get("body") != report["manifestSha256"]
        or remote.get("target_commitish") != report["manifest"]["workflowSha"]
        or public.obj(remote.get("author")).get("login") != "github-actions[bot]"
    ):
        raise public.DeliveryError("GATE", "preflight alert journal identity differs")
    return public.AssetJournal(
        github,
        public.integer(remote.get("id"), minimum=1),
        {
            "schema": "talentforge-play-preflight-alert/1",
            "runId": report["runId"],
            "manifest": report["manifestSha256"],
        },
    )


def notify(report: dict[str, Any], client: httpx.Client) -> dict[str, Any]:
    config = core.require_env(
        os.environ,
        [
            "GITHUB_REPOSITORY",
            "GH_TOKEN",
            "TELEGRAM_BOT_TOKEN",
            "TELEGRAM_CHAT_ID",
        ],
    )
    github = public.GitHub(client, config["GITHUB_REPOSITORY"], config["GH_TOKEN"])
    if "schema" in report and report["schema"] != "talentforge-play-preflight-failure/1":
        raise public.DeliveryError("CONTRACT", "unknown Production notification report schema")
    if report.get("schema") == "talentforge-play-preflight-failure/1":
        journal = preflight_alert_journal(github, report)
        return public.write_once(
            journal,
            "telegram-preflight",
            report,
            lambda: public.send_telegram(
                client,
                config["TELEGRAM_BOT_TOKEN"],
                config["TELEGRAM_CHAT_ID"],
                "Google Play Production 1.1.0 (6): BLOCKED (OTA preflight)\n"
                + report["detail"]
                + "\n"
                + f"https://github.com/{github.repository}/actions/runs/{report['runId']}",
            ),
        )
    identity = public.string(report.get("manifestSha256"))
    found = [r for r in github.pages("releases") if r.get("tag_name") == "play-production-6"]
    if not found:
        return {
            "status": "NOT_SENT",
            "detail": "No Production delivery journal; no store write started",
        }
    if (
        len(found) != 1
        or found[0].get("body") != identity
        or public.obj(found[0].get("author")).get("login") != "github-actions[bot]"
    ):
        raise public.DeliveryError("GATE", "Production notification journal differs")
    journal = public.AssetJournal(
        github,
        public.integer(found[0].get("id"), minimum=1),
        {
            "schema": "talentforge-play-checkpoint/1",
            "manifest": identity,
        },
    )
    event = {
        "status": public.string(report.get("status")),
        "detail": public.string(report.get("detail")),
    }
    name = "telegram-" + hashlib.sha256(public.canonical(event)).hexdigest()
    return public.write_once(
        journal,
        name,
        event,
        lambda: public.send_telegram(
            client,
            config["TELEGRAM_BOT_TOKEN"],
            config["TELEGRAM_CHAT_ID"],
            "Google Play Production 1.1.0 (6): " + event["status"] + "\n" + event["detail"],
        ),
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command", choices=("notes", "prepare", "run", "notify", "preflight-failure")
    )
    parser.add_argument("--gate", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--source-repo", type=Path)
    parser.add_argument("--directory", type=Path)
    parser.add_argument("--outcome", choices=("failure", "cancelled"))
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "preflight-failure":
        if args.directory is None or args.outcome is None:
            parser.error("preflight-failure requires --directory and --outcome")
        manifest = core.read_json_object(args.directory / "production-manifest.json")
        if digest(manifest) != os.environ.get("APPROVED_PRODUCTION_SHA256"):
            raise public.DeliveryError("GATE", "preflight failure approval digest differs")
        result = preflight_failure(
            manifest, outcome=args.outcome, run_id=int(os.environ["GITHUB_RUN_ID"])
        )
        core.write_json(args.out, result)
        public.summary(result)
        return 0
    if args.command == "notify":
        if args.report is None:
            parser.error("notify requires --report")
        try:
            with httpx.Client(timeout=120, follow_redirects=False) as client:
                result = notify(core.read_json_object(args.report), client)
        except (core.DeliveryError, OSError, ValueError, KeyError, TypeError) as error:
            result = {
                "status": error.code if isinstance(error, core.DeliveryError) else "CONFIG",
                "detail": "Production notification could not be confirmed",
            }
            core.write_json(args.out, result)
            return 1
        core.write_json(args.out, result)
        return 0
    if args.gate is None:
        parser.error("delivery commands require --gate")
    identity = None
    try:
        gate = core.read_json_object(args.gate)
        if args.command == "notes":
            value = release_notes(args.repo, gate, gate["sourceSha"])
        else:
            value = prepare(gate, args.directory, args.source_repo)
            identity = digest(value)
            if args.command == "prepare":
                core.set_outputs({"production_sha256": identity}, os.environ)
            else:
                if os.environ.get("GITHUB_REF") != core.MAIN_REF:
                    raise public.DeliveryError("GATE", "production runs only on main")
                if identity != os.environ.get("APPROVED_PRODUCTION_SHA256"):
                    raise public.DeliveryError("GATE", "production approval digest differs")
                config = core.require_env(
                    os.environ,
                    [
                        "GH_TOKEN",
                        "GITHUB_REPOSITORY",
                        "PLAY_PRODUCTION_SERVICE_ACCOUNT_JSON",
                        "PLAY_PRODUCTION_SERVICE_ACCOUNT_EMAIL",
                    ],
                )
                with httpx.Client(timeout=120, follow_redirects=False) as client:
                    github = public.GitHub(client, config["GITHUB_REPOSITORY"], config["GH_TOKEN"])
                    require_approval(github, int(os.environ["GITHUB_RUN_ID"]))
                    for sha in (gate["sourceSha"], gate["workflowSha"]):
                        core.resolve_revision(args.repo, sha, gate["workflowSha"])
                    core.check_ci(
                        core.GitHubApi(github.repository, config["GH_TOKEN"], client),
                        gate["sourceSha"],
                    )
                    account = core.parse_service_account(
                        config["PLAY_PRODUCTION_SERVICE_ACCOUNT_JSON"]
                    )
                    if account.email != config["PLAY_PRODUCTION_SERVICE_ACCOUNT_EMAIL"]:
                        raise public.DeliveryError(
                            "CONFIG", "unexpected production service account"
                        )
                    verify = gate["mode"] == "verify"
                    if gate["mode"] not in core.MODES:
                        raise public.DeliveryError("GATE", "unknown delivery mode")
                    journal = journal_for(github, value, create=not verify)
                    value = deliver(
                        ProductionPlay(account, client),
                        value,
                        args.directory / "TalentForge-1.1.0-6.aab",
                        journal,
                        verify=verify,
                    )
                    value["manifestSha256"] = identity
        core.write_json(args.out, value)
        public.summary(value)
    except (
        core.DeliveryError,
        OSError,
        ValueError,
        KeyError,
        TypeError,
        subprocess.CalledProcessError,
    ) as error:
        value = {
            "status": error.code if isinstance(error, core.DeliveryError) else "CONFIG",
            "detail": error.message
            if isinstance(error, core.DeliveryError)
            else "invalid or missing production input",
        }
        if identity is not None:
            value["manifestSha256"] = identity
        core.write_json(args.out, value)
        public.summary(value)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
