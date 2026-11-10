"""从源码构建两个签名 APK，需要 Android SDK 34、Build Tools 34.0.0 和 JDK 17+。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET
import subprocess
from tempfile import TemporaryDirectory
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parent


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def provenance(src):
    def git(*args):
        result = subprocess.run(["git", *args], cwd=ROOT.parent, capture_output=True, text=True)
        return result.stdout.strip() if result.returncode == 0 else None
    digest = hashlib.sha256()
    for file in sorted(p for p in src.rglob("*") if p.is_file()):
        name=file.relative_to(src).as_posix()
        if name == "assets/build-info.json": continue
        data=file.read_bytes()
        digest.update(name.encode("utf-8")+b"\0"+hashlib.sha256(data).digest())
    model=src/"assets/model.tsv"
    return {"schema":1,"gitCommit":git("rev-parse","HEAD"),"gitTree":git("rev-parse","HEAD^{tree}"),
            "dirty":bool(git("status","--porcelain","--untracked-files=all")),"sourceSha256":digest.hexdigest(),
            "modelSha256":hashlib.sha256(model.read_bytes()).hexdigest() if model.exists() else None,
            "package":ET.parse(src/"AndroidManifest.xml").getroot().get("package")}


def build(name: str, src: Path, stage: Path, sdk: Path, keystore: Path) -> Path:
    tools = sdk / "build-tools" / "34.0.0"
    android_jar = sdk / "platforms" / "android-34" / "android.jar"
    win = os.name == "nt"
    aapt = tools / ("aapt2.exe" if win else "aapt2")
    d8 = tools / ("d8.bat" if win else "d8")
    align = tools / ("zipalign.exe" if win else "zipalign")
    signer = tools / ("apksigner.bat" if win else "apksigner")
    work = stage / name
    classes = work / "classes"
    dex = work / "dex"
    classes.mkdir(parents=True)
    dex.mkdir()
    resources = work / "res.zip"
    base = work / "base.apk"
    aligned = work / "aligned.apk"
    apk = stage / f"{name}.apk"

    print(f"构建 {name}", flush=True)
    run(aapt, "compile", "--dir", src / "res", "-o", resources)
    assets = ("-A", src / "assets") if (src / "assets").is_dir() else ()
    run(aapt, "link", "-o", base, "-I", android_jar, "--manifest", src / "AndroidManifest.xml",
        "-R", resources, *assets, "--auto-add-overlay")
    # 给 JDK 21 生成的隐式参数保留名字，兼容 Build Tools 34 的 D8。
    run("javac", "-encoding", "UTF-8", "-parameters", "--release", "11", "-classpath", android_jar,
        "-d", classes, *sorted((src / "src").rglob("*.java")))
    # A single archive avoids cmd.exe's argument limit on Windows as the app grows.
    class_archive = work / "classes.jar"
    with ZipFile(class_archive, "w", ZIP_DEFLATED) as archive:
        for file in sorted(classes.rglob("*.class")):
            archive.write(file, file.relative_to(classes).as_posix())
    run(d8, "--lib", android_jar, "--min-api", "24", "--output", dex, class_archive)
    with ZipFile(base, "a", ZIP_DEFLATED) as archive:
        archive.write(dex / "classes.dex", "classes.dex")
        archive.writestr("assets/build-info.json",json.dumps(provenance(src),ensure_ascii=False,sort_keys=True)+"\n")
    run(align, "-f", "4", base, aligned)
    run(signer, "sign", "--ks", keystore, "--ks-key-alias", "androiddebugkey",
        "--ks-pass", "pass:android", "--key-pass", "pass:android",
        "--v1-signing-enabled", "true", "--v2-signing-enabled", "true", "--out", apk, aligned)
    run(signer, "verify", apk)
    return apk


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sdk_env = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("SDK")
    parser.add_argument("--sdk", type=Path, default=Path(sdk_env) if sdk_env else None)
    parser.add_argument("--out", type=Path, default=ROOT / "build")
    parser.add_argument("--keystore", type=Path, help="现有 Android 调试密钥库，供覆盖安装时保持签名")
    parser.add_argument("--preview", action="store_true", help="构建可与原版并存的独立测试版，不读取原版本机数据")
    parser.add_argument("--timetable-test", action="store_true", help="构建直接进入课表的转发测试包，不含消息后台服务或授权引导")
    args = parser.parse_args()
    if not args.sdk or not (args.sdk / "platforms" / "android-34" / "android.jar").is_file():
        parser.error("需要 Android SDK Platform 34。请设置 ANDROID_HOME 或传入 --sdk。")
    if not (args.sdk / "build-tools" / "34.0.0").is_dir():
        parser.error("需要 Android SDK Build Tools 34.0.0。")

    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    keystore = args.keystore.resolve() if args.keystore else out / "debug.keystore"
    if args.keystore and not keystore.is_file():
        parser.error("--keystore 指向的调试密钥库不存在。")
    if not keystore.exists():
        run("keytool", "-genkeypair", "-keystore", keystore, "-storepass", "android",
            "-keypass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA",
            "-keysize", "2048", "-validity", "10000", "-dname", "CN=Android Debug,O=Android,C=US")

    with TemporaryDirectory(prefix="compile-", dir=out) as stage:
        if args.preview or args.timetable_test:
            src = Path(stage) / "preview-src"
            shutil.copytree(ROOT / "app", src)
            manifest = src / "AndroidManifest.xml"
            text = manifest.read_text(encoding="utf-8")
            # Keep compiled class names; only the installed application ID changes.
            application_id = "com.nopalou.timetabletest" if args.timetable_test else "com.nopalou.preview"
            text = text.replace('package="com.nopalou"', f'package="{application_id}"')
            text = text.replace('android:name=".', 'android:name="com.nopalou.')
            manifest.write_text(text, encoding="utf-8")
            name = "nopalou-preview"
            if args.timetable_test:
                ns = "{http://schemas.android.com/apk/res/android}"
                ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
                tree = ET.parse(manifest)
                app = tree.getroot().find("application")
                # Keep the repository's application name and icon, including resource references.
                app_label = app.get(ns + "label", "@string/app_name")
                launcher = None
                for activity in app.findall("activity"):
                    for intent in list(activity.findall("intent-filter")):
                        if any(a.get(ns + "name") == "android.intent.action.MAIN" for a in intent.findall("action")):
                            launcher = intent
                            activity.remove(intent)
                    activity.set(ns + "exported", "false")
                target = next(a for a in app.findall("activity") if a.get(ns + "name") == "com.nopalou.NwpuImportActivity")
                target.set(ns + "exported", "true")
                target.set(ns + "label", app_label)
                if launcher is not None:
                    target.append(launcher)
                for tag in ("service", "receiver"):
                    for element in list(app.findall(tag)):
                        app.remove(element)
                for permission in list(tree.getroot().findall("uses-permission")):
                    if permission.get(ns + "name") not in {"android.permission.INTERNET", "android.permission.REQUEST_INSTALL_PACKAGES"}:
                        tree.getroot().remove(permission)
                tree.write(manifest, encoding="utf-8", xml_declaration=True)
                name = "nopalou-timetable-test"
            apks = [build(name, src, Path(stage), args.sdk.resolve(), keystore)]
        else:
            apks = [build(name, ROOT / directory, Path(stage), args.sdk.resolve(), keystore)
                    for name, directory in (("nopalou", "app"), ("replay", "replay"))]
        for apk in apks:
            apk.replace(out / apk.name)
            print(out / apk.name)


if __name__ == "__main__":
    main()
