# -*- coding: utf-8 -*-
"""分布外测试集评测（方案书 8.2「真实测试集」的代理）

这份集合是手写的，不是生成器产物，句式、错别字、口语和真实群聊接近。
样本量小（60 条），所以只适合看方向、看错误类型，不适合当验收线。

发送者不再按标准答案指定：整集分别以「全部发送者=王老师」和
「全部发送者=陈嘉禾」各跑一遍，两种设定的召回/精确都写进
eval/hardset_report.json，用来观察模型有多依赖发送者身份。
"""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from engine.pipeline import Model, Pipeline  # noqa: E402
from engine.jielong import parse as jlparse  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]

# 两种固定设定：不参考 label，只是换一个人名跑整集
SETTINGS = (("all_teacher", "王老师"), ("all_student", "陈嘉禾"))


def run_once(cases: list[dict], model: Model, sender: str) -> dict:
    """整集都用同一个发送者跑一遍，返回各项指标和错误清单。"""
    label_ok = 0
    todo_tp = todo_fp = todo_fn = 0
    time_ok = time_tot = 0
    confirm_ok = confirm_tot = 0
    jl_ok = jl_tot = 0
    evaluated = 0
    errors: list[str] = []

    for i, c in enumerate(cases, 1):
        if c.get("needs_context"):
            continue
        evaluated += 1
        p = Pipeline(model)
        msg = {
            "msg_id": f"h{i:03d}", "group_id": "g_hard", "group_name": "计科 2301 班群",
            "sender": sender,
            "ts": c["ts"], "text": c["text"], "course": None,
        }
        p.process(msg)
        cards = p.active_cards()
        todo = [x for x in cards if x.kind == "todo"]
        jl_cards = [x for x in cards if x.kind == "jielong"]
        card = todo[0] if todo else (jl_cards[0] if jl_cards else None)

        # 四分类：用分类器自身的结果核对（pipeline 内部的判定已体现在卡片上）
        want = c["label"]
        if want == "action":
            if c.get("jielong"):
                # 接龙：已接的不该出卡，未接的必须出卡
                jl_card = any(x.kind == "jielong" for x in cards)
                if jl_card != (not c.get("joined", False)):
                    errors.append(f"[接] {c['text'][:24]} → joined={c.get('joined')} 但卡片={jl_card}")
                else:
                    label_ok += 1
            elif card:
                label_ok += 1
            else:
                errors.append(f"[漏] {c['text'][:30]} → 期望待办，实际无卡片")
        elif want in ("notice", "discussion", "chatter"):
            if card:
                errors.append(f"[误] {c['text'][:30]} → 期望{want}，实际出了待办卡")
            else:
                label_ok += 1

        if want == "action" and not c.get("jielong"):
            if card:
                todo_tp += 1
            else:
                todo_fn += 1
        elif want != "action" and card:
            todo_fp += 1

        if c.get("deadline"):
            time_tot += 1
            if card and card.deadline == datetime.fromisoformat(c["deadline"]):
                time_ok += 1
            else:
                got = card.deadline.strftime("%m-%d %H:%M") if card and card.deadline else "无"
                errors.append(f"[时] {c['text'][:26]} → 期望 {datetime.fromisoformat(c['deadline']).strftime('%m-%d %H:%M')}，实际 {got}")

        if c.get("needs_confirm"):
            confirm_tot += 1
            if card is None or card.needs_confirm:
                confirm_ok += 1
            else:
                errors.append(f"[确] {c['text'][:26]} → 应标待确认，实际给了明确时间")

        if c.get("jielong"):
            jl_tot += 1
            j = jlparse(c["text"], datetime.fromisoformat(c["ts"]))
            if j.is_jielong and j.joined == c["joined"]:
                jl_ok += 1
            else:
                errors.append(f"[接] {c['text'][:24]} → 期望 joined={c['joined']}，实际 {j.joined}")

    return {
        "evaluated": evaluated,
        "label_ok": label_ok,
        "todo_tp": todo_tp,
        "todo_fp": todo_fp,
        "todo_fn": todo_fn,
        "time_ok": time_ok,
        "time_tot": time_tot,
        "confirm_ok": confirm_ok,
        "confirm_tot": confirm_tot,
        "jl_ok": jl_ok,
        "jl_tot": jl_tot,
        "errors": errors,
    }


def show(sender: str, r: dict) -> None:
    """打印一种设定下的明细。"""
    ev = r["evaluated"]
    tp, fp, fn = r["todo_tp"], r["todo_fp"], r["todo_fn"]
    rec = tp / max(1, tp + fn)
    prec = tp / max(1, tp + fp)
    print(f"全部发送者={sender}  待办召回 {tp}/{tp + fn} = {rec:.1%}"
          f"  待办精确 {tp}/{tp + fp} = {prec:.1%}")
    print(f"  四分类判断正确   {r['label_ok']}/{ev} = {r['label_ok'] / max(1, ev):.1%}")
    print(f"  时间完全正确     {r['time_ok']}/{r['time_tot']} = {r['time_ok'] / max(1, r['time_tot']):.1%}")
    print(f"  待确认判断正确   {r['confirm_ok']}/{r['confirm_tot']} = {r['confirm_ok'] / max(1, r['confirm_tot']):.1%}")
    print(f"  接龙未接判断     {r['jl_ok']}/{r['jl_tot']} = {r['jl_ok'] / max(1, r['jl_tot']):.1%}")
    print(f"  错误清单（{len(r['errors'])} 条）:")
    for e in r["errors"]:
        print("    -", e)
    print()


def main() -> None:
    cases = [json.loads(l) for l in (ROOT / "eval" / "hardset.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    model = Model(ROOT / "engine" / "model.json")

    n = len(cases)
    evaluated = sum(1 for c in cases if not c.get("needs_context"))
    print(f"分布外手写集 {n} 条（其中 {evaluated} 条参与单条判定，"
          f"{n - evaluated} 条需上下文、单独测）")
    print("发送者整集固定，不看答案：\n")

    out: dict[str, dict] = {}
    for mode, sender in SETTINGS:
        r = run_once(cases, model, sender)
        show(sender, r)
        tp, fp, fn = r["todo_tp"], r["todo_fp"], r["todo_fn"]
        out[mode] = {
            "sender": sender,
            "recall": round(tp / max(1, tp + fn), 4),
            "precision": round(tp / max(1, tp + fp), 4),
            "todo_tp": tp,
            "todo_fp": fp,
            "todo_fn": fn,
        }

    # 变更/撤销要放在消息流里才成立：先发通知，再发改期，看卡片有没有被更新
    p2 = Pipeline(model)
    base_ts = "2026-10-14T10:00:00"
    p2.process({"msg_id": "r1", "group_id": "g_rev", "group_name": "数据库系统 2026秋",
                "sender": "王老师", "ts": base_ts, "text": "周五 18 点前交实验报告，交到学习通。"})
    p2.process({"msg_id": "r2", "group_id": "g_rev", "group_name": "数据库系统 2026秋",
                "sender": "王老师", "ts": "2026-10-14T11:00:00",
                "text": "更正一下，作业截止改到下周一 23:59。"})
    upd = [c for c in p2.active_cards() if c.updated]
    rev_ok = len(upd) == 1 and upd[0].deadline == datetime.fromisoformat("2026-10-19T23:59:00")
    out["revision"] = {"passed": rev_ok}
    print(f"  变更/撤销（消息流）  {'✓' if rev_ok else '✗'}  "
          f"{'卡片被更新为 10-19 23:59' if rev_ok else '未按预期更新'}")

    dest = ROOT / "eval" / "hardset_report.json"
    dest.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n两种设定的召回/精确已写入 {dest}")
    if not rev_ok:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
