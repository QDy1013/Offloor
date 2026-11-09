"""Prepare a public, content-addressed update feed from already signed APK files.

Example: python android/prepare_updates.py --sdk <SDK> --out <feed-dir> --apk <apk> [--apk <apk> ...]
Publish the resulting latest.json and apks/ files on the repository's updates branch.
Never copy signing keys or credentials into that branch.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

ROOT_URL = "https://raw.githubusercontent.com/QDy1013/Offloor/updates/"
NAMES = {"com.nopalou": "offloor.apk", "com.nopalou.preview": "offloor-preview.apk", "com.nopalou.timetabletest": "offloor-timetable-test.apk"}


def inspect(apk, sdk):
    tools = sdk / "build-tools/34.0.0"
    aapt = tools / ("aapt2.exe" if (tools / "aapt2.exe").exists() else "aapt2")
    signer = tools / ("apksigner.bat" if (tools / "apksigner.bat").exists() else "apksigner")
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True, encoding="utf-8", errors="replace")
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not m or m[1] not in NAMES:
        raise ValueError("APK package does not have an Offloor update channel")
    cert = subprocess.check_output([str(signer), "verify", "--print-certs", str(apk)], text=True, encoding="utf-8", errors="replace")
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})", cert)
    if len(certificates) != 1:
        raise ValueError("A single verified APK signer is required")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    return m[1], {"versionCode": int(m[2]), "versionName": m[3], "size": apk.stat().st_size, "sha256": digest,
                  "certificateSha256": certificates[0].lower(), "url": ROOT_URL + "apks/" + digest + "/" + NAMES[m[1]]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--apk", type=Path, action="append", required=True)
    parser.add_argument("--existing", type=Path, help="Existing public catalog to preserve other channels")
    parser.add_argument("--notes", default="新增应用内下载更新，保留校园信息与统一登录功能。")
    args = parser.parse_args()
    feed = json.loads(args.existing.read_text(encoding="utf-8")) if args.existing else {"schema": 1, "packages": {}}
    if feed.get("schema") != 1 or not isinstance(feed.get("packages"), dict):
        parser.error("Existing catalog schema is not supported")
    for path in args.apk:
        apk = path.resolve()
        package, metadata = inspect(apk, args.sdk.resolve())
        previous = feed["packages"].get(package)
        if previous and (metadata["versionCode"] < previous["versionCode"] or metadata["certificateSha256"] != previous["certificateSha256"]):
            raise ValueError("Refusing an older version or an incompatible signing key")
        if not 0 < metadata["size"] <= 200 * 1024 * 1024:
            raise ValueError("APK size is outside the update limit")
        metadata["notes"] = args.notes
        destination = args.out / "apks" / metadata["sha256"] / NAMES[package]
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(apk, destination)
        assert hashlib.sha256(destination.read_bytes()).hexdigest() == metadata["sha256"]
        feed["packages"][package] = metadata
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "latest.json").write_text(json.dumps(feed, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"channels": list(feed["packages"]), "catalog": str(args.out / "latest.json")}))


if __name__ == "__main__":
    main()
