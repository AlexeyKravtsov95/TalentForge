#!/usr/bin/env python3
"""M5.GALLERY.1 — offline inspection of the Android artifacts built by run-gallery1-checks.sh.

Standard library only. Reads the APKs/AAB, the aapt2/apksigner/Gradle logs and the test/lint
reports of one worktree and writes ``gallery1-inspection.json`` plus a short text summary.

Everything here is *static* evidence about the package (manifest, permissions, ABIs, dependency
coordinates, class-name strings present in the dex files). It proves what the package declares
and contains; it does not prove runtime behaviour on a Huawei device.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

# Class-name prefixes searched for in the dex string tables. A hit means the name is referenced
# by some code in the package; it does not by itself mean the code runs.
DEX_NEEDLES = {
    "google_play_services": b"Lcom/google/android/gms/",
    "firebase": b"Lcom/google/firebase/",
    "play_billing": b"Lcom/android/billingclient/",
    "play_core": b"Lcom/google/android/play/core/",
    "huawei_hms": b"Lcom/huawei/hms/",
    "huawei_agconnect": b"Lcom/huawei/agconnect/",
    "okhttp": b"Lokhttp3/",
    "ktor": b"Lio/ktor/",
    "java_net_HttpURLConnection": b"Ljava/net/HttpURLConnection;",
    "java_net_Socket": b"Ljava/net/Socket;",
    "android_webkit_WebView": b"Landroid/webkit/WebView;",
}

# Dependency groups that would indicate GMS/Firebase/billing/HMS/network stacks.
DEP_PATTERNS = {
    "google_play_services": r"com\.google\.android\.gms:",
    "firebase": r"com\.google\.firebase:",
    "play_billing": r"com\.android\.billingclient:",
    "play_core": r"com\.google\.android\.play:",
    "huawei": r"com\.huawei\.",
    "okhttp": r"com\.squareup\.okhttp3:",
    "ktor": r"io\.ktor:",
    "retrofit": r"com\.squareup\.retrofit2:",
}

CONFIG_FILES = ("google-services.json", "agconnect-services.json")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""


def parse_badging(text: str) -> dict:
    if not text:
        return {}
    out: dict = {}
    m = re.search(r"package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", text)
    if m:
        out["package"], out["versionCode"], out["versionName"] = m.groups()
    for key, pattern in (("minSdkVersion", r"^(?:minSdkVersion|sdkVersion):'([^']*)'"), ("targetSdkVersion", r"^targetSdkVersion:'([^']*)'")):
        m = re.search(pattern, text, re.M)
        out[key] = m.group(1) if m else None
    out["debuggable"] = bool(re.search(r"^application-debuggable", text, re.M))
    m = re.search(r"^densities: (.*)$", text, re.M)
    out["densities"] = re.findall(r"'([^']*)'", m.group(1)) if m else []
    out["uses_permission"] = re.findall(r"^uses-permission: name='([^']*)'", text, re.M)
    out["application_label_locales"] = len(re.findall(r"^application-label-", text, re.M))
    m = re.search(r"^locales: (.*)$", text, re.M)
    out["locales"] = re.findall(r"'([^']*)'", m.group(1)) if m else []
    m = re.search(r"^application: label='([^']*)' icon='([^']*)'", text, re.M)
    if m:
        out["application_label"], out["application_icon"] = m.groups()
    m = re.search(r"^native-code: (.*)$", text, re.M)
    out["native_code"] = re.findall(r"'([^']*)'", m.group(1)) if m else []
    out["uses_feature"] = re.findall(r"^uses-feature(?:-not-required)?: name='([^']*)'", text, re.M)
    return out


def parse_xmltree(text: str) -> list[dict]:
    """aapt2 'dump xmltree' -> flat list of elements {tag, depth, attrs}."""
    elements: list[dict] = []
    for line in text.splitlines():
        m = re.match(r"^(\s*)E: (\S+)", line)
        if m:
            elements.append({"tag": m.group(2), "depth": len(m.group(1)), "attrs": {}})
            continue
        m = re.match(
            r"^\s*A: (?:http://schemas\.android\.com/apk/res/android:)?([\w:.-]+)(?:\(0x[0-9a-f]+\))?=(.*)$",
            line,
        )
        if m and elements:
            name, raw = m.groups()
            val = re.match(r'^"([^"]*)"', raw)
            raw = re.sub(r"^\(type 0x[0-9a-f]+\)", "", raw)  # aapt2 may print "(type 0x12)0xffffffff"
            elements[-1]["attrs"][name] = val.group(1) if val else raw.split(" ")[0]
    return elements


def manifest_facts(elements: list[dict]) -> dict:
    app = next((e for e in elements if e["tag"] == "application"), {"attrs": {}})
    a = app["attrs"]

    def names(tag: str) -> list[str]:
        return [e["attrs"].get("name", "?") for e in elements if e["tag"] == tag]

    exported = [
        f'{e["tag"]}:{e["attrs"].get("name", "?")}'
        for e in elements
        if e["tag"] in ("activity", "activity-alias", "service", "receiver", "provider")
        and str(e["attrs"].get("exported", "")).lower() in ("true", "0xffffffff", "-1")
    ]
    queries: list[str] = []
    in_queries_depth = None
    for e in elements:
        if in_queries_depth is not None and e["depth"] <= in_queries_depth:
            in_queries_depth = None
        if e["tag"] == "queries":
            in_queries_depth = e["depth"]
        elif in_queries_depth is not None and e["tag"] in ("action", "package", "provider", "data", "category"):
            queries.append(f'{e["tag"]}:{e["attrs"].get("name", e["attrs"].get("mimeType", ""))}')
    return {
        "allowBackup": a.get("allowBackup", "(unset: platform default true)"),
        "fullBackupContent": a.get("fullBackupContent"),
        "dataExtractionRules": a.get("dataExtractionRules"),
        "debuggable": a.get("debuggable", "(unset)"),
        "usesCleartextTraffic": a.get("usesCleartextTraffic", "(unset)"),
        "networkSecurityConfig": a.get("networkSecurityConfig"),
        "localeConfig": a.get("localeConfig"),
        "extractNativeLibs": a.get("extractNativeLibs"),
        "uses_permission": names("uses-permission"),
        "permission_declared": names("permission"),
        "activities": names("activity"),
        "services": names("service"),
        "receivers": names("receiver"),
        "providers": names("provider"),
        "meta_data": names("meta-data"),
        "uses_library": names("uses-library"),
        "uses_feature": names("uses-feature"),
        "exported_components": exported,
        "queries_entries": queries,
    }


def zip_facts(path: Path) -> dict:
    if not path.is_file():
        return {"present": False}
    with zipfile.ZipFile(path) as z:
        infos = z.infolist()
        names = [i.filename for i in infos]
        abis = sorted({n.split("/")[-2] for n in names if re.match(r"^(base/)?lib/[^/]+/[^/]+\.so$", n)})
        sos = sorted({n.split("/")[-1] for n in names if n.endswith(".so")})
        prefixes: dict[str, int] = {}
        for i in infos:
            parts = i.filename.split("/")
            top = "/".join(parts[:2]) if parts[0] in ("base", "assets") and len(parts) > 2 else parts[0]
            prefixes[top] = prefixes.get(top, 0) + i.compress_size
        dex_hits = {k: False for k in DEX_NEEDLES}
        for n in names:
            if re.match(r"^(base/dex/)?classes\d*\.dex$", n):
                blob = z.read(n)
                for k, needle in DEX_NEEDLES.items():
                    dex_hits[k] = dex_hits[k] or (needle in blob)
        configs = [n for n in names if n.split("/")[-1] in CONFIG_FILES]
        largest = sorted(prefixes.items(), key=lambda kv: -kv[1])[:8]
    return {
        "present": True,
        "bytes": path.stat().st_size,
        "entries": len(names),
        "abis": abis,
        "native_libraries": sos,
        "dex_class_name_hits": dex_hits,
        "service_config_files": configs,
        "largest_compressed_prefixes": [{"prefix": p, "compressed_bytes": b} for p, b in largest],
    }


def deps_facts(text: str) -> dict:
    hits = {k: sorted(set(re.findall(rf"({p}[\w.:-]+)", text))) for k, p in DEP_PATTERNS.items()}
    coords = sorted(set(re.findall(r"[+\\]--- ([\w.-]+:[\w.-]+)", text)))
    return {"report_present": bool(text), "pattern_hits": hits, "distinct_modules": len(coords), "modules": coords}


def apksigner_facts(text: str) -> dict:
    # Only the subject DN is kept; certificate digests are deliberately not recorded here.
    return {
        "log_present": bool(text),
        "verifies": bool(re.search(r"^Verifies$", text, re.M)),
        "does_not_verify": "DOES NOT VERIFY" in text,
        "signer_dns": sorted(set(re.findall(r"certificate DN: (.*)$", text, re.M))),
        "schemes": dict(re.findall(r"^Verified using (v[\d.]+ scheme[^:]*): (\w+)", text, re.M)),
        "errors": re.findall(r"^ERROR: (.*)$", text, re.M)[:5],
        "debug_certificate": "CN=Android Debug" in text,
    }


def test_facts(results_dir: Path) -> dict:
    tot = {"results_dir_present": results_dir.is_dir(), "suites": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for f in sorted(results_dir.glob("*.xml")):
        try:
            r = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        tot["suites"] += 1
        for k in ("tests", "failures", "errors", "skipped"):
            tot[k] += int(r.get(k, "0"))
    return tot


def lint_facts(xml_path: Path) -> dict:
    if not xml_path.is_file():
        return {"present": False}
    root = ET.parse(xml_path).getroot()
    issues: dict[str, dict] = {}
    for i in root.iter("issue"):
        d = issues.setdefault(i.get("id", "?"), {"severity": i.get("severity"), "count": 0, "files": set()})
        d["count"] += 1
        loc = i.find("location")
        if loc is not None and loc.get("file"):
            d["files"].add(re.sub(r"^.*?/(mobile/|composeApp/)", r"\1", loc.get("file")))
    by_sev: dict[str, int] = {}
    for d in issues.values():
        by_sev[d["severity"]] = by_sev.get(d["severity"], 0) + d["count"]
    return {
        "present": True,
        "by_severity": by_sev,
        "issues": {k: {"severity": v["severity"], "count": v["count"], "files": sorted(v["files"])} for k, v in sorted(issues.items())},
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--wt", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--curated", required=True, type=Path)
    ap.add_argument("--sha", required=True)
    a = ap.parse_args()
    m = a.wt / "mobile"
    outputs = m / "composeApp/build/outputs"
    result = {
        "commit": a.sha,
        "artifacts": {
            "debug_apk": zip_facts(outputs / "apk/debug/composeApp-debug.apk"),
            "release_apk_unsigned": zip_facts(outputs / "apk/release/composeApp-release-unsigned.apk"),
            "release_aab": zip_facts(outputs / "bundle/release/composeApp-release.aab"),
        },
        "badging": {
            "debug": parse_badging(read(a.out / "40-badging-debug.log")),
            "release": parse_badging(read(a.out / "40-badging-release.log")),
        },
        "manifest": {
            "debug": manifest_facts(parse_xmltree(read(a.out / "41-manifest-debug.log"))),
            "release": manifest_facts(parse_xmltree(read(a.out / "41-manifest-release.log"))),
        },
        "apksigner": {
            "debug": apksigner_facts(read(a.out / "42-apksigner-debug.log")),
            "release": apksigner_facts(read(a.out / "42-apksigner-release.log")),
        },
        "dependencies_releaseRuntimeClasspath": deps_facts(read(a.out / "34-deps-release.log")),
        "tests": {
            "shared_jvmTest": test_facts(m / "shared/build/test-results/jvmTest"),
            "composeApp_desktopTest": test_facts(m / "composeApp/build/test-results/desktopTest"),
        },
        "lintRelease": lint_facts(m / "composeApp/build/reports/lint-results-release.xml"),
        "bundletool_get_size": read(a.out / "52-bundletool-size.log").strip() or None,
    }
    a.curated.mkdir(parents=True, exist_ok=True)
    (a.curated / "gallery1-inspection.json").write_text(
        json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    rb, rm, rz = result["badging"]["release"], result["manifest"]["release"], result["artifacts"]["release_apk_unsigned"]
    deps = result["dependencies_releaseRuntimeClasspath"]
    lines = [
        f"commit {a.sha}",
        f"package {rb.get('package')} versionCode {rb.get('versionCode')} versionName {rb.get('versionName')}",
        f"minSdk {rb.get('minSdkVersion')} targetSdk {rb.get('targetSdkVersion')} application-debuggable {rb.get('debuggable')}",
        f"label {rb.get('application_label')!r} icon {rb.get('application_icon')!r} label-locales {rb.get('application_label_locales')}",
        f"uses-permission {rm['uses_permission']}",
        f"allowBackup {rm['allowBackup']} | debuggable {rm['debuggable']} | cleartext {rm['usesCleartextTraffic']} | localeConfig {rm['localeConfig']}",
        f"providers {rm['providers']}",
        f"meta-data {rm['meta_data']}",
        f"queries {rm['queries_entries']}",
        f"exported {rm['exported_components']}",
        f"uses-feature {rm['uses_feature']} uses-library {rm['uses_library']}",
        f"ABIs {rz.get('abis')} native libs {rz.get('native_libraries')}",
        f"dex class-name hits {[k for k, v in (rz.get('dex_class_name_hits') or {}).items() if v]}",
        f"dependency pattern hits {[k for k, v in deps['pattern_hits'].items() if v]} ({deps['distinct_modules']} modules)",
        f"service config files {rz.get('service_config_files')}",
        f"bytes debug {result['artifacts']['debug_apk'].get('bytes')} release-unsigned {rz.get('bytes')} aab {result['artifacts']['release_aab'].get('bytes')}",
        f"apksigner debug: verifies={result['apksigner']['debug']['verifies']} debug-cert={result['apksigner']['debug']['debug_certificate']}",
        f"apksigner release: verifies={result['apksigner']['release']['verifies']} errors={result['apksigner']['release']['errors']}",
        f"tests shared {result['tests']['shared_jvmTest']}",
        f"tests desktop {result['tests']['composeApp_desktopTest']}",
        f"lint {result['lintRelease'].get('by_severity')} ids {list((result['lintRelease'].get('issues') or {}).keys())}",
    ]
    (a.curated / "gallery1-inspection.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
