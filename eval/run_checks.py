"""离线核心回归；Java 统一编译一次，每组用例在独立进程运行。需要 Python 与 JDK。"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "android/app/src/com/nopalou"
SOURCES = ("Engine", "NotifFilter", "DemoScenario", "ReminderPlan", "TodayView", "ClashPlan",
           "NotifParser", "DiagLog", "HealthCheck", "ChangeDiff", "SectionTimes", "TodayPlan", "StateFormat", "StateStore", "TaskChanges", "ChatTime", "SecretCodec", "AiBudgetCore", "TaskTrace", "ShowcaseScenario")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, help="保存包含各用例输出的 JSON 报告")
    parser.add_argument("--android-jar", type=Path, help="可选，编译 Android 适配层所用 SDK android.jar")
    parser.add_argument("--json-jar", type=Path, help="可选，JVM 运行 JSON 适配检查所用 org.json 实现")
    args = parser.parse_args()
    if bool(args.android_jar) != bool(args.json_jar):
        parser.error("--android-jar 和 --json-jar 必须一起提供")
    results = []

    def run(name, cmd):
        try:
            result = subprocess.run(cmd, cwd=ROOT, capture_output=True, encoding="utf-8", errors="replace",
                                    timeout=120)
            entry = {"name": name, "exit": result.returncode, "output": result.stdout + result.stderr}
        except (OSError, subprocess.TimeoutExpired) as error:
            entry = {"name": name, "exit": 1, "output": str(error)}
        results.append(entry)
        print(("PASS " if entry["exit"] == 0 else "FAIL ") + name, flush=True)
        if entry["exit"] != 0:
            print(entry["output"], flush=True)
        return entry["exit"] == 0

    tests = sorted(p for p in (ROOT / "android/test").glob("*Test.java")
                   if re.fullmatch(r"EngineTest|Step\d+Test", p.stem))
    with TemporaryDirectory(prefix=".core-checks-", dir=ROOT) as classes:
        compiled = run("Java compile", ["javac", "-encoding", "UTF-8", "--release", "11", "-d", classes,
                                      *[str(APP / (name + ".java")) for name in SOURCES], *map(str, tests)])
        if compiled:
            for test in tests:
                extra = [str(ROOT / "android")]
                if test.stem == "EngineTest":
                    extra = [str(ROOT / "android/app/assets"), str(ROOT / "android/replay/assets/messages.txt")]
                run("Java " + test.stem, ["java", "-Dfile.encoding=UTF-8", "-Duser.timezone=Asia/Shanghai",
                                         "-cp", classes, "com.nopalou." + test.stem, *extra])
        if compiled and args.android_jar:
            classpath = os.pathsep.join((classes, str(args.json_jar.resolve()), str(args.android_jar.resolve())))
            adapters = sorted({"AiQueue", "AiConfig", "AiSecrets", "AiBudget", "AccountFiles"} | {
                file.stem for file in APP.glob("*.java")
                if file.stem not in SOURCES and not re.search(r"^import android\.", file.read_text(encoding="utf-8"), re.M)
            })
            # AiQueue 的存储诊断依赖宿主；仅在 JVM 侧提供无 Android 运行需求的日志替身。
            stub = Path(classes) / "EngineHolder.java"
            stub.write_text("package com.nopalou; final class EngineHolder { static void diag(android.content.Context c, String a, String b) {} }", encoding="utf-8")
            adapter_tests = sorted(p for p in (ROOT / "android/test").glob("*Test.java") if p not in tests)
            if run("AI adapter compile", ["javac", "-encoding", "UTF-8", "--release", "11", "-cp", classpath,
                                          "-d", classes, str(stub), *[str(APP / (s + ".java")) for s in adapters],
                                          *map(str, adapter_tests)]):
                for test in adapter_tests:
                    extra = []
                    if test.stem == "AiResponseTest": extra = [str(ROOT / "android")]
                    if test.stem == "CampusScopeTest": extra = [str(ROOT / "android/app/assets/campus-bus.json")]
                    if test.stem == "TimetableTest":
                        fixture = Path(classes) / "timetable.json"
                        if not run("Timetable fixture", ["node", str(ROOT / "eval/test_nwpu_import.cjs"), str(fixture)]): continue
                        extra = [str(fixture)]
                    run("Java " + test.stem, ["java", "-Dfile.encoding=UTF-8", "-Duser.timezone=Asia/Shanghai",
                                              "-cp", classpath, "com.nopalou." + test.stem, *extra])
    for test in sorted((ROOT / "eval").glob("test_*.py")):
        run("Python " + test.stem, [sys.executable, "-I", "-X", "utf8", str(test)])
    if args.out:
        args.out.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    failed = sum(entry["exit"] != 0 for entry in results)
    print(f"{len(results)} 组检查，{failed} 组失败", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
