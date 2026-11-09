"""运行校园页面的合成浏览器回归。先在 eval/browser 执行 npm ci。"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--browser", type=Path, help="可选，已有 Chromium 或 Edge 的绝对路径")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    module = ROOT / "eval/browser/node_modules/playwright-core"
    if not module.is_dir():
        parser.error("请先运行 npm --prefix eval/browser ci --ignore-scripts")
    env = dict(os.environ, PLAYWRIGHT_MODULE=str(module))
    if args.browser:
        env["CAMPUS_BROWSER"] = str(args.browser.resolve())
    results = []
    for file in sorted((ROOT / "eval").glob("test_*.cjs")):
        try:
            result = subprocess.run(["node", str(file)], cwd=ROOT, env=env, capture_output=True,
                                    encoding="utf-8", errors="replace", timeout=120)
            entry = {"name": file.stem, "exit": result.returncode, "output": result.stdout + result.stderr}
        except (OSError, subprocess.TimeoutExpired) as error:
            entry = {"name": file.stem, "exit": 1, "output": str(error)}
        results.append(entry)
        print(("PASS " if entry["exit"] == 0 else "FAIL ") + file.stem, flush=True)
        if entry["exit"]: print(entry["output"][-2500:], flush=True)
    if args.out:
        args.out.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return int(any(entry["exit"] for entry in results))


if __name__ == "__main__":
    sys.exit(main())
