# -*- coding: utf-8 -*-
"""回归验证 eval/strict_ood.jsonl 与多基线评测基准的一致性（自动纳入 run_checks.py）。"""
from __future__ import annotations

from datetime import datetime
import json
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "eval"))

from engine.pipeline import Model  # noqa: E402
from run_benchmark import eval_keyword_ood, eval_python_ood, run_java_ood, score_ood  # noqa: E402


def main() -> None:
    ood_path = ROOT / "eval" / "strict_ood.jsonl"
    raw_lines = [l for l in ood_path.read_text(encoding="utf-8").splitlines() if l.strip()]
    assert len(raw_lines) >= 100, f"strict_ood 应至少包含 100 条样本，实际 {len(raw_lines)}"

    seen_ids: set[str] = set()
    seen_texts: set[str] = set()
    rows: list[dict] = []
    for line in raw_lines:
        r = json.loads(line)
        assert r["id"] not in seen_ids, f"重复 id: {r['id']}"
        seen_ids.add(r["id"])
        norm = r["text"].strip()
        assert norm not in seen_texts, f"重复文本: {norm}"
        seen_texts.add(norm)
        assert r["label"] in ("action", "notice", "discussion", "chatter")
        r["dt"] = datetime.fromisoformat(r["ts"])
        if r.get("deadline"):
            datetime.fromisoformat(r["deadline"])
        rows.append(r)

    rows.sort(key=lambda r: (r["dt"], r["id"]))
    model = Model(ROOT / "engine" / "model.json")

    kw = eval_keyword_ood(rows)
    py = eval_python_ood(rows, model, use_rules=True)
    assert py["f1"] > kw["f1"], f"三级流水线 F1 ({py['f1']}) 应明显高于关键词基线 ({kw['f1']})"
    assert py["vague_restraint_acc"] == 1.0, "模糊时间克制应保持 100%"

    java, javac = shutil.which("java"), shutil.which("javac")
    if java and javac:
        snap, esc = run_java_ood(rows, java, javac)
        for c in snap.cards:
            c.needs_confirm = (c.deadline is None)
        jv = score_ood(rows, snap.active_cards(), snap.jielong_ids, escalations=esc)
        assert jv["f1"] >= 0.90, f"Java 引擎严格分布外 F1 异常偏低: {jv['f1']}"
        assert jv["precision"] >= 0.90, f"Java 引擎精确率偏低: {jv['precision']}"
        assert jv["vague_restraint_acc"] == 1.0, "Java 引擎不得为模糊时间编造具体日期"
        assert jv["local_only_pct"] >= 0.75, f"端侧拦截率低于预期: {jv['local_only_pct']}"

    print(f"PASS strict_ood ({len(rows)} items): py_f1={py['f1']}, kw_f1={kw['f1']}")


if __name__ == "__main__":
    main()
