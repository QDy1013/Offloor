"""编译端侧小模型推理库 liboffloor_llm.so（llama.cpp + JNI 封装）。

安卓：需要 Android NDK（r26 及以上），产物放进 APK 的 lib/arm64-v8a/。
本机：--host 用系统编译器编出同一份 JNI 库，供 eval/test_local_llm_native.py 在电脑上冒烟测试。
llama.cpp 固定在下面的版本和提交，第一次运行时 git 拉取到构建目录并核对提交号。
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
LLAMA_TAG = "v0.9.0"
LLAMA_COMMIT = "cd959bae99ff4bed22bef2604f45241c09bbb8c8"
LLAMA_REPO = "https://github.com/ggml-org/llama.cpp"
ABI = "arm64-v8a"
API_LEVEL = "24"
LIB = "liboffloor_llm.so"


def run(*args, cwd=None):
    subprocess.run([str(a) for a in args], check=True, cwd=cwd)


def fetch_llama(cache: Path) -> Path:
    src = cache / f"llama.cpp-{LLAMA_TAG}"
    if not (src / "CMakeLists.txt").is_file():
        if src.exists():
            shutil.rmtree(src)
        cache.mkdir(parents=True, exist_ok=True)
        print(f"拉取 llama.cpp {LLAMA_TAG}", flush=True)
        run("git", "clone", "--depth", "1", "--branch", LLAMA_TAG, LLAMA_REPO, src)
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=src, capture_output=True, text=True).stdout.strip()
    if head != LLAMA_COMMIT:
        raise SystemExit(f"llama.cpp 提交号不符：{head}，应为 {LLAMA_COMMIT}。请删除 {src} 后重试。")
    return src


def find_ndk(sdk: Path | None) -> Path | None:
    for key in ("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK"):
        value = os.environ.get(key)
        if value and (Path(value) / "build/cmake/android.toolchain.cmake").is_file():
            return Path(value)
    if sdk and (sdk / "ndk").is_dir():
        versions = sorted((p for p in (sdk / "ndk").iterdir() if (p / "build/cmake/android.toolchain.cmake").is_file()),
                          key=lambda p: [int(x) if x.isdigit() else 0 for x in p.name.split(".")])
        if versions:
            return versions[-1]
    return None


def configure_and_build(build_dir: Path, llama: Path, extra: list[str]) -> Path:
    jobs = str(max(1, os.cpu_count() or 1))
    generator = ["-G", "Ninja"] if shutil.which("ninja") else []
    run("cmake", "-S", ROOT / "native", "-B", build_dir, *generator, "-DCMAKE_BUILD_TYPE=Release",
        f"-DLLAMA_DIR={llama}", *extra)
    run("cmake", "--build", build_dir, "--target", "offloor_llm", "-j", jobs)
    lib = build_dir / LIB
    if not lib.is_file():
        raise SystemExit(f"没有生成 {lib}")
    return lib


def build_android(ndk: Path, out: Path) -> Path:
    llama = fetch_llama(out)
    lib = configure_and_build(out / f"native-{ABI}", llama, [
        f"-DCMAKE_TOOLCHAIN_FILE={ndk / 'build/cmake/android.toolchain.cmake'}",
        f"-DANDROID_ABI={ABI}", f"-DANDROID_PLATFORM=android-{API_LEVEL}", "-DANDROID_STL=c++_static",
    ])
    target = out / "native" / ABI / LIB
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(lib, target)
    return target


def build_host(out: Path) -> Path:
    llama = fetch_llama(out)
    return configure_and_build(out / "native-host", llama, [])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ["ANDROID_HOME"]) if os.environ.get("ANDROID_HOME") else None)
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--out", type=Path, default=ROOT / "build")
    parser.add_argument("--host", action="store_true", help="编译电脑本机版，用于冒烟测试")
    args = parser.parse_args()
    out = args.out.resolve()
    if args.host:
        print(build_host(out))
        return
    ndk = args.ndk or find_ndk(args.sdk)
    if not ndk:
        sys.exit("找不到 Android NDK：请安装 ndk;27.2.12479018，设置 ANDROID_NDK_HOME，或传入 --ndk。")
    print(build_android(ndk.resolve(), out))


if __name__ == "__main__":
    main()
