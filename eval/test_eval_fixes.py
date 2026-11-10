# -*- coding: utf-8 -*-
"""评测修正验收：审查意见第 9、10、11、12 条。

对应关系：
  9  手写集不再按答案设发送者，run_hardset.py 要分别报告"全部老师/全部学生"两种设定
  10 留出集按句子去重切分，report.json 顶层要有 split 字段说明重复率
  11 用独立场景验证时间评分确实采用更正后的答案，不把历史总分当作评分器正确性的证明
  12 消融"去掉回复潮"要真的关掉回复潮，burst_promoted == 0

另外守住两条底线：
  5  线上模型（engine/model.json、engine/model.tsv、android/app/assets/model.tsv）运行前后哈希不变
  6  旧的 eval/test_time_cases.py 仍然通过

做法：本脚本只用 subprocess + sys.executable 跑现有评测脚本，读它们生成的文件做断言，
不改任何业务/评测代码。评测代码还没修好时，第 9～12 条会失败，这是预期的。

用法（项目根目录下）：
    python -I -X utf8 eval/test_eval_fixes.py
有失败时退出码 1。
"""
from __future__ import annotations

import ast
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

# 脚本在 eval/ 下，-I 模式不会把脚本目录放进 sys.path，显式补项目根目录。
ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

HARDSET_SCRIPT = "eval/run_hardset.py"
HARDSET_REPORT = ROOT / "eval" / "hardset_report.json"
REPORT = ROOT / "eval" / "report.json"

MAIN = "本方案（三级流水线）"
ABL_BURST = "消融：去掉回复潮"

# 线上模型只许读不许写。评测要用的模型应由修复方另存到 eval/ 下。
PROTECTED = (
    "engine/model.json",
    "engine/model.tsv",
    "android/app/assets/model.tsv",
)

OK = 0
BAD = 0


# ---------------------------------------------------------------- 输出小工具

def rel(path: Path) -> str:
    try:
        return path.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def check(title: str, ok: bool, detail: str = "") -> bool:
    global OK, BAD
    if ok:
        OK += 1
        print(f"  ✓ {title}" + (f"  （{detail}）" if detail else ""))
    else:
        BAD += 1
        print(f"  ✗ {title}" + (f"  （{detail}）" if detail else ""))
    return ok


def note(text: str) -> None:
    print(f"  · 提示：{text}")


def is_num(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def is_ratio(v) -> bool:
    return is_num(v) and 0.0 <= float(v) <= 1.0


def load_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"  ! {rel(path)} 读不出来：{exc}")
        return None


def sha256(path: Path) -> str | None:
    if not path.is_file():
        return None
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------- 跑子进程

def run_script(rel_path: str, timeout: int = 1800) -> tuple[bool, str]:
    """用当前解释器跑项目里的脚本，返回 (是否退出码 0, 合并输出)。"""
    script = ROOT / rel_path
    if not script.is_file():
        print(f"\n$ {rel_path}\n  ! 脚本不存在")
        return False, ""

    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"
    cmd = [sys.executable, "-X", "utf8", str(script)]
    print(f"\n$ {rel_path}")
    try:
        p = subprocess.run(cmd, cwd=str(ROOT), env=env, capture_output=True,
                           text=True, encoding="utf-8", errors="replace",
                           timeout=timeout)
    except OSError as exc:                       # 解释器起不来之类
        print(f"  ! 启动失败：{exc}")
        return False, ""
    except subprocess.TimeoutExpired:
        print(f"  ! 超时（>{timeout}s）")
        return False, ""

    out = p.stdout or ""
    if p.stderr and p.stderr.strip():
        out += "\n[stderr]\n" + p.stderr

    if p.returncode == 0:
        tail = [ln for ln in (p.stdout or "").splitlines() if ln.strip()]
        print("  " + (tail[-1][:160] if tail else "(无输出)"))
    else:
        print(f"  ! 退出码 {p.returncode}，输出末尾：")
        for line in out.splitlines()[-25:]:
            print("    " + line[:200])
    return p.returncode == 0, out


# ---------------------------------------------------------------- 第 9 条：源码

def _segment(src: str, node: ast.AST) -> str:
    try:
        return ast.get_source_segment(src, node) or ""
    except Exception:                            # 源码被改坏时别让验收脚本自己崩
        return ""


def _is_sender_key(node: ast.AST | None) -> bool:
    return isinstance(node, ast.Constant) and node.value == "sender"


def _sender_expressions(tree: ast.AST) -> tuple[list[ast.AST], bool]:
    """找出所有"给 sender 赋值"的表达式，并顺着变量别名往上追几层。

    覆盖：dict 字面量 {"sender": X}、函数调用 sender=X、sender = X、
    msg["sender"] = X。返回 (表达式列表, 是否找到过)。
    """
    assigns: dict[str, list[ast.AST]] = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign):
            for t in node.targets:
                if isinstance(t, ast.Name):
                    assigns.setdefault(t.id, []).append(node.value)
        elif isinstance(node, ast.AnnAssign):
            if isinstance(node.target, ast.Name) and node.value is not None:
                assigns.setdefault(node.target.id, []).append(node.value)

    roots: list[ast.AST] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Dict):
            for k, v in zip(node.keys, node.values):
                if _is_sender_key(k) and v is not None:
                    roots.append(v)
        elif isinstance(node, ast.Call):
            for kw in node.keywords:
                if kw.arg == "sender":
                    roots.append(kw.value)
        elif isinstance(node, ast.Assign):
            for t in node.targets:
                if isinstance(t, ast.Name) and t.id == "sender":
                    roots.append(node.value)
                elif isinstance(t, ast.Subscript) and _is_sender_key(t.slice):
                    roots.append(node.value)
        elif isinstance(node, ast.AnnAssign):
            t = node.target
            if node.value is not None and (
                (isinstance(t, ast.Name) and t.id == "sender")
                or (isinstance(t, ast.Subscript) and _is_sender_key(t.slice))
            ):
                roots.append(node.value)

    if not roots:
        return [], False

    exprs: list[ast.AST] = []
    seen: set[int] = set()
    queue = list(roots)
    for _ in range(4):                           # 顺着 x = y 这样的别名追 4 层
        nxt: list[ast.AST] = []
        for node in queue:
            if id(node) in seen:
                continue
            seen.add(id(node))
            exprs.append(node)
            if isinstance(node, ast.Name):
                nxt.extend(assigns.get(node.id, []))
        if not nxt:
            break
        queue = nxt
    return exprs, True


def _mentions_label(node: ast.AST, src: str) -> bool:
    seg = _segment(src, node)
    return "label" in seg or "答案" in seg


def check_hardset_source() -> None:
    print("\n[9a] run_hardset.py 不再按答案设置发送者")
    path = ROOT / HARDSET_SCRIPT
    if not path.is_file():
        check("找到 eval/run_hardset.py", False, "文件不存在")
        return
    try:
        src = path.read_text(encoding="utf-8")
        tree = ast.parse(src)
    except (OSError, SyntaxError) as exc:
        check("能读并解析 eval/run_hardset.py", False, str(exc))
        return

    exprs, found = _sender_expressions(tree)
    if not found:
        # 结构太绕（例如包在别的函数里）就退化成逐行看：sender 所在行不许出现 label
        lines = [ln.strip() for ln in src.splitlines()
                 if '"sender"' in ln or "'sender'" in ln]
        leaks = [ln for ln in lines if "label" in ln]
        check("设置 sender 的表达式不引用 label", not leaks,
              ("命中：" + leaks[0][:100]) if leaks
              else "没找到结构化赋值，退化为逐行检查，未发现 label")
        return

    leaks: list[str] = []
    for node in exprs:
        if _mentions_label(node, src):
            leaks.append(_segment(src, node).replace("\n", " ").strip()[:100])
    check("设置 sender 的表达式不引用 label", not leaks,
          ("命中：" + " / ".join(leaks)) if leaks else f"检查了 {len(exprs)} 个表达式")


def check_hardset_report(run_ok: bool) -> None:
    print("\n[9b] run_hardset.py 报告两种发送者设定的召回/精确")
    if not run_ok:
        check("eval/run_hardset.py 运行成功", False, "见上面的输出，报告不可信")
        return

    data = load_json(HARDSET_REPORT)
    if not isinstance(data, dict):
        check("存在 eval/hardset_report.json 且能解析", False, "缺失或不是 JSON 对象")
        return
    check("存在 eval/hardset_report.json 且能解析", True, rel(HARDSET_REPORT))

    for mode in ("all_teacher", "all_student"):
        block = data.get(mode)
        if not isinstance(block, dict):
            check(f'含 "{mode}" 设定', False, "缺失或不是对象")
            continue
        bad = [k for k in ("recall", "precision") if not is_ratio(block.get(k))]
        check(f'"{mode}" 的 recall / precision 都在 0～1', not bad,
              ("不合格：" + "、".join(bad)) if bad
              else f"recall={block.get('recall')} precision={block.get('precision')}")


# ---------------------------------------------------------------- 第 10～12 条：report.json

def _report_ready(eval_ok: bool):
    if not eval_ok:
        print("  ! run_eval.py 没跑成功，下面的 report.json 结果不作数")
        return None
    rep = load_json(REPORT)
    if not isinstance(rep, dict):
        print("  ! eval/report.json 读不出来")
        return None
    return rep


def _block(rep, name: str) -> dict:
    v = (rep or {}).get(name)
    return v if isinstance(v, dict) else {}


def check_time_acc(rep) -> None:
    print("\n[11] 时间准确率按更正后的答案比")
    main = _block(rep, MAIN)
    acc = main.get("time_acc")
    check(f'"{MAIN}" 如实报告时间准确率', is_ratio(acc), f"实际 {acc}")
    from datetime import datetime
    from types import SimpleNamespace
    from eval.run_eval import corrected_deadlines, score

    old = "2026-11-13T18:00:00"
    changed = "2026-11-16T22:00:00"
    rows = [
        {"msg_id": "original", "group_id": "fixture", "label": "action", "todo": {"deadline": old}},
        {"msg_id": "correction", "group_id": "fixture", "label": "action", "hard": "supersede",
         "supersedes": "original", "todo": {"deadline": changed}},
    ]
    card = SimpleNamespace(kind="todo", source_msg_id="original", group_id="fixture",
                           deadline=datetime.fromisoformat(changed))
    engine = SimpleNamespace(cards=[card], active_cards=lambda: [card], n_noise=0, n_level3=0, n_promoted=0)
    check("已经按更正更新时间的卡得分 1", score(rows, engine)["time_acc"] == 1)
    card.deadline = datetime.fromisoformat(old)
    check("仍保留旧时间的卡得分 0", score(rows, engine)["time_acc"] == 0)
    rows.append({"msg_id": "again", "supersedes": "correction", "todo": {"deadline": old}})
    check("连续更正回溯到原始事项", corrected_deadlines(rows, {"original": changed})["original"] == old)


def check_burst_ablation(rep) -> None:
    print("\n[12] 消融「去掉回复潮」真的关掉了回复潮")
    abl = _block(rep, ABL_BURST)
    bp = abl.get("burst_promoted")
    check(f'"{ABL_BURST}" 的 burst_promoted == 0',
          is_num(bp) and float(bp) == 0.0, f"实际 {bp}")

    # 这条只提示：消融后的第三级调用次数不该比本方案多
    l3_abl, l3_main = abl.get("level3_calls"), _block(rep, MAIN).get("level3_calls")
    if is_num(l3_abl) and is_num(l3_main):
        if l3_abl <= l3_main:
            note(f"level3_calls 消融 {l3_abl} ≤ 本方案 {l3_main}，没有误关别的特征")
        else:
            note(f"level3_calls 消融 {l3_abl} > 本方案 {l3_main}："
                 f"去掉回复潮反而多调了第三级，留意开关是否又接错了（不计失败）")
    else:
        note("消融或本方案缺 level3_calls，跳过这项提示")


def check_split(rep) -> None:
    print("\n[10] 留出集重复率如实报告 + 没见过的句子单独评测")
    split = (rep or {}).get("split")
    if not isinstance(split, dict):
        check('report.json 顶层有 "split" 对象', False, f"实际 {type(split).__name__}")
        check('split 里报告训练集重复率', False, "无 split，无法评估")
        check('split 里说明切分方法', False, "无 split")
        return

    check('report.json 顶层有 "split" 对象', True)

    # 主控修订：按句子剔除后训练集里"讨论""闲聊"一条不剩（留出集这两类 100% 见过），无法重训。
    # 改为如实报告各类重复率，并另给"只看训练集没见过的句子"的待办召回/精确。
    ratio = split.get("test_texts_seen_in_train_ratio")
    check('split["test_texts_seen_in_train_ratio"] 如实给出（0～1）', is_ratio(ratio), f"实际 {ratio}")
    by_label = split.get("seen_ratio_by_label")
    check('split["seen_ratio_by_label"] 给出 action/notice/discussion/chatter 四类重复率',
          isinstance(by_label, dict) and all(is_ratio(by_label.get(k)) for k in ("action", "notice", "discussion", "chatter")),
          f"实际 {by_label}")
    unseen = (rep or {}).get("本方案（只看没见过的句子）")
    check('report.json 有"本方案（只看没见过的句子）"，含 recall、precision、gold_todo',
          isinstance(unseen, dict) and is_ratio(unseen.get("recall")) and is_ratio(unseen.get("precision"))
          and isinstance(unseen.get("gold_todo"), int) and unseen.get("gold_todo") > 0, f"实际 {unseen}")

    method = split.get("method")
    check('split["method"] 说明了切分方式',
          isinstance(method, str) and len(method.strip()) >= 2,
          f"实际 {method!r}")


# ---------------------------------------------------------------- 第 5、6 条：底线

def _looks_like_model(path: Path) -> bool:
    try:
        d = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    return isinstance(d, dict) and "weights" in d and "labels" in d


def check_hashes(before: dict[str, str | None], after: dict[str, str | None]) -> None:
    print("\n[5] 线上模型没有被评测改动")
    for p in PROTECTED:
        b, a = before.get(p), after.get(p)
        if b is None or a is None:
            check(f"{p} 存在", False, "运行前或运行后文件缺失")
            continue
        check(f"{p} 哈希不变", b == a,
              "运行前后一致" if b == a else f"{b[:12]}… → {a[:12]}…")

    eval_dir = ROOT / "eval"
    models = sorted(p.name for p in eval_dir.glob("*.json")
                    if p.name not in ("report.json", "hardset_report.json")
                    and _looks_like_model(p))
    if models:
        note("eval/ 下另存的模型：" + "、".join(models))
    else:
        note("eval/ 下没看到另存的模型文件（评测若仍直接用 engine/model.json，"
             "请确认它只是读、不会被重训覆盖）")


def check_time_cases(run_ok: bool, output: str) -> None:
    print("\n[6] 之前的检查仍然通过")
    check("eval/test_time_cases.py 退出码 0", run_ok)
    m = re.search(r"通过\s*(\d+)\s*项，失败\s*(\d+)\s*项", output)
    if m:
        check("test_time_cases 汇总里失败 0 项", int(m.group(2)) == 0, m.group(0))
    else:
        check("能读到 test_time_cases 的汇总行", False,
              "输出里没有“通过 X 项，失败 Y 项”")


# ---------------------------------------------------------------- 入口

def main() -> None:
    print("== 评测修正验收（审查意见第 9、10、11、12 条）==")
    before = {p: sha256(ROOT / p) for p in PROTECTED}
    print("已记录线上模型哈希：" + "、".join(PROTECTED))

    print("\n-- 运行现有评测脚本（subprocess + sys.executable）--")
    hardset_ok, _ = run_script(HARDSET_SCRIPT, timeout=900)
    eval_ok, _ = run_script("eval/run_eval.py", timeout=1800)
    time_ok, time_out = run_script("eval/test_time_cases.py", timeout=300)

    after = {p: sha256(ROOT / p) for p in PROTECTED}

    check_hardset_source()
    check_hardset_report(hardset_ok)

    rep = _report_ready(eval_ok)
    check_time_acc(rep)
    check_burst_ablation(rep)
    check_split(rep)

    check_hashes(before, after)
    check_time_cases(time_ok, time_out)

    print(f"\n通过 {OK} 项，失败 {BAD} 项")
    if BAD:
        sys.exit(1)


if __name__ == "__main__":
    main()
