"""端侧小模型推理库冒烟测试（电脑上运行，不需要手机）。

1. 用 android/build_native.py --host 编出本机版 liboffloor_llm（与 APK 里的 arm64 版同一份源码）；
2. 用 eval/make_tiny_gguf.py 生成随机权重的微型 Qwen2 模型；
3. 运行 android/test/LocalLlmSmoke.java：加载、语法约束输出、取消、释放、错误路径。
需要 git、cmake、C/C++ 编译器、JDK 11+、numpy；首次运行会拉取固定版本的 llama.cpp。
"""
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "android"))
import build_native  # noqa: E402


def main():
    out = ROOT / "android/build"
    lib = build_native.build_host(out)
    llama = build_native.fetch_llama(out)
    with TemporaryDirectory(prefix="local-llm-") as tmp:
        tmp = Path(tmp)
        model = tmp / "tiny.gguf"
        subprocess.run([sys.executable, str(ROOT / "eval/make_tiny_gguf.py"), str(llama), str(model)], check=True)
        classes = tmp / "classes"
        app = ROOT / "android/app/src/com/nopalou"
        subprocess.run(["javac", "-encoding", "UTF-8", "--release", "11", "-d", str(classes),
                        str(app / "LocalLlm.java"), str(app / "LocalLlmCore.java"),
                        str(ROOT / "android/test/LocalLlmSmoke.java")], check=True)
        result = subprocess.run(["java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                                 f"-Djava.library.path={lib.parent}", "-cp", str(classes),
                                 "com.nopalou.LocalLlmSmoke", str(model)], timeout=600)
        sys.exit(result.returncode)


if __name__ == "__main__":
    main()
