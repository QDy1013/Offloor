# -*- coding: utf-8 -*-
"""严格无泄漏与多基线对比评测脚本。

针对原合成留出集存在 82.3% 原句重复的问题，补充两套无泄漏基准：
1. 模板级隔离合成留出集：先对训练集所有文本做结构骨架归一化（替换日期/时刻/人名/课程/地点/渠道），
   只保留在训练集中未出现过句式模板的留出消息进行评分。
2. 100 条人工构造真实分布外测试集（eval/strict_ood.jsonl）：覆盖中文数字时刻、跨年日期、
   编号作业清单、区间数字陷阱、口语提问/第一人称负例、模糊时间克制与接龙未接判别，
   并用真实消息发送者 + 全老师/全学生极端压力测试对照 Python 流水线、APK Java 引擎、
   关键词基线和纯第 2 级模型，同时统计端侧拦截率与第 3 级大模型升级比例。

可选：传入 --llm-url / --llm-model / --llm-key 可对进入第 3 级的升级样本做在线复核对比。
"""
from __future__ import annotations

import argparse
from base64 import b64decode, b64encode
from datetime import datetime
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "eval"))

from engine.extract import extract  # noqa: E402
from engine.features import is_noise  # noqa: E402
from engine.jielong import parse as parse_jl  # noqa: E402
from engine.pipeline import Model, Pipeline  # noqa: E402
from run_eval import load as load_synth, score as score_synth  # noqa: E402
from run_java_eval import EngineSnapshot  # noqa: E402

RE_NUM = re.compile(r"\d+")
RE_WS = re.compile(r"\s+")
RE_ENTITIES = re.compile(
    r"(?:学习通|雨课堂|课程网站|QQ 群文件|QQ群文件|邮箱|教务系统|课代表处|"
    r"数据库系统|计算机网络|操作系统|数据结构|软件工程|概率论|"
    r"李承宇|承宇|小李|李同学|王老师|刘老师|赵老师|孙老师|周老师|吴老师|陈嘉禾|林晓|周若曦|"
    r"三教|二教|实验楼|图书馆报告厅|腾讯会议|周一|周二|周三|周四|周五|周六|周日)"
)


def template_signature(text: str) -> str:
    s = RE_ENTITIES.sub("<E>", text.strip())
    s = RE_NUM.sub("<N>", s)
    return RE_WS.sub("", s)


def run_java_ood(rows: list[dict], java: str, javac: str) -> tuple[EngineSnapshot, int]:
    app = ROOT / "android" / "app"
    fields = ("id", "group_id", "group_name", "sender", "text")
    messages = "".join(
        row["dt"].isoformat() + "\t" +
        "\t".join(b64encode((row.get(field) or "").encode("utf-8")).decode("ascii") for field in fields) +
        "\n"
        for row in rows
    )
    with TemporaryDirectory(prefix="offloor-bench-", dir=Path.cwd()) as classes:
        src = app / "src/com/nopalou"
        subprocess.run(
            [javac, "-encoding", "UTF-8", "-d", classes,
             *[str(src / f"{name}.java") for name in ("Engine", "StateFormat", "TaskChanges", "ChangeDiff")],
             str(ROOT / "eval" / "EngineEval.java")],
            check=True,
        )
        result = subprocess.run(
            [java, "-Duser.timezone=Asia/Shanghai", "-cp", classes, "com.nopalou.EngineEval",
             str(app / "assets" / "model.tsv"), "--escalations"],
            input=messages, stdout=subprocess.PIPE, encoding="ascii", check=True,
        )
    lines = result.stdout.splitlines()
    counts = lines[0]
    jielong_sources = lines[1]
    esc_line = lines[2]
    escalations = int(esc_line.split("\t")[1]) if esc_line.startswith("ESCALATIONS\t") else 0
    rest = "\n".join([counts, jielong_sources, *lines[3:]])
    return EngineSnapshot(rest, len(rows), False), escalations


def score_ood(rows: list[dict], active_cards: list, jielong_trigger_ids: set[str],
              escalations: int | None = None, noise_dropped: int | None = None) -> dict:
    # 非接龙动作卡（含 todo 与 exam）
    todo_gold = {r["id"] for r in rows if r["label"] == "action" and not r["jielong"]}
    got_cards = [c for c in active_cards if c.kind in ("todo", "exam")]
    got_ids = {c.source_msg_id for c in got_cards}
    tp = len(got_ids & todo_gold)
    fp = len(got_ids - todo_gold)
    fn = len(todo_gold - got_ids)
    rec = tp / max(1, tp + fn)
    prec = tp / max(1, tp + fp)
    f1 = 2 * rec * prec / max(1e-9, rec + prec)

    by_id = {r["id"]: r for r in rows}
    time_ok = time_tot = 0
    vague_ok = vague_tot = 0
    for r in rows:
        if r["label"] != "action" or r["jielong"]:
            continue
        card = next((c for c in got_cards if c.source_msg_id == r["id"]), None)
        if r.get("deadline"):
            time_tot += 1
            if card and card.deadline == datetime.fromisoformat(r["deadline"]):
                time_ok += 1
        elif r.get("needs_confirm"):
            vague_tot += 1
            # 模糊时间不造假时间（不出卡或出卡但 deadline 为空 / 标 needsConfirm）
            if card is None or card.deadline is None or getattr(card, "needs_confirm", False):
                vague_ok += 1

    jl_should = {r["id"] for r in rows if r["jielong"] and not r["joined"]}
    jl_should_not = {r["id"] for r in rows if r["jielong"] and r["joined"]}
    jl_tp = len(jielong_trigger_ids & jl_should)
    jl_fp = len(jielong_trigger_ids & jl_should_not) + len(jielong_trigger_ids - jl_should - jl_should_not)
    jl_rec = jl_tp / max(1, len(jl_should))
    jl_prec = jl_tp / max(1, jl_tp + jl_fp)

    out = {
        "n_messages": len(rows),
        "todo_tp": tp, "todo_fp": fp, "todo_fn": fn,
        "recall": round(rec, 4),
        "precision": round(prec, 4),
        "f1": round(f1, 4),
        "time_acc": round(time_ok / max(1, time_tot), 4),
        "time_hits": f"{time_ok}/{time_tot}",
        "vague_restraint_acc": round(vague_ok / max(1, vague_tot), 4),
        "vague_hits": f"{vague_ok}/{vague_tot}",
        "jielong_recall": round(jl_rec, 4),
        "jielong_precision": round(jl_prec, 4),
    }
    if escalations is not None:
        out["escalated_to_llm"] = escalations
        out["local_only_pct"] = round(1.0 - escalations / max(1, len(rows)), 4)
    if noise_dropped is not None:
        out["noise_dropped"] = noise_dropped
    return out


def eval_python_ood(rows: list[dict], model: Model, use_rules: bool = True,
                    sender_override: str | None = None) -> dict:
    p = Pipeline(model, use_rules=use_rules)
    jl_hits = set()
    est_escalations = 0
    for r in rows:
        item = {
            "msg_id": r["id"],
            "group_id": r["group_id"],
            "group_name": r["group_name"],
            "sender": sender_override or r["sender"],
            "ts": r["ts"],
            "text": r["text"],
        }
        cards = p.process(item)
        if any(c.kind == "jielong" for c in cards):
            jl_hits.add(r["id"])
        if not is_noise(r["text"]):
            # 统计第 3 级抽取与低把握兜底消息（用于观察端侧过滤比例）
            for c in cards:
                if c.kind in ("todo", "exam") and (c.needs_confirm or c.confidence < 0.65):
                    est_escalations += 1
    # Python Pipeline 仅统计 card.deadline；补出 needs_confirm 语义字段兼容 score_ood
    for c in p.cards:
        c.needs_confirm = bool(c.needs_confirm)
    return score_ood(rows, p.active_cards(), jl_hits, escalations=est_escalations, noise_dropped=p.n_noise)


def eval_keyword_ood(rows: list[dict]) -> dict:
    from types import SimpleNamespace
    cards = []
    jl_hits = set()
    dropped = 0
    for r in rows:
        if is_noise(r["text"]):
            dropped += 1
            continue
        if not any(v in r["text"] for v in ("交", "提交", "报名", "接龙", "签到", "填", "考试", "测验", "答辩")):
            continue
        if "#接龙" in r["text"]:
            jl_hits.add(r["id"])
            continue
        ext = extract(r["text"], r["dt"])
        cards.append(SimpleNamespace(
            kind="todo", source_msg_id=r["id"],
            deadline=ext.deadline, needs_confirm=ext.needs_confirm,
        ))
    return score_ood(rows, cards, jl_hits, escalations=0, noise_dropped=dropped)


def eval_synth_template_disjoint(model: Model) -> dict:
    rows = load_synth(ROOT / "data" / "dataset.jsonl")
    cut = int(len(rows) * 0.7)
    train_rows, hold_rows = rows[:cut], rows[cut:]
    train_sigs = {template_signature(r["text"]) for r in train_rows if not is_noise(r["text"])}
    disjoint_hold = [r for r in hold_rows
                     if not is_noise(r["text"]) and template_signature(r["text"]) not in train_sigs]
    p = Pipeline(model)
    for r in disjoint_hold:
        p.process(r)
    by_label = {}
    for lab in ("action", "notice", "discussion", "chatter"):
        by_label[lab] = sum(1 for r in disjoint_hold if r["label"] == lab)
    return {
        "hold_non_noise_total": sum(1 for r in hold_rows if not is_noise(r["text"])),
        "template_disjoint_count": len(disjoint_hold),
        "by_label": by_label,
        "python_pipeline": score_synth(disjoint_hold, p) if disjoint_hold else {},
        "note": "合成数据模板有限，按结构骨架剔除后保留未见模板句子；真实泛化以 strict_ood.jsonl 为准。",
    }


def optional_llm_probe(rows: list[dict], url: str, model_name: str, api_key: str) -> dict:
    """对前 20 条样本做可选的在线 OpenAI 兼容接口对照（不提供密钥时不运行）。"""
    sample = rows[:20]
    payload = {
        "model": model_name,
        "temperature": 0,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": "判断大学生群消息是否需要本人行动，输出 JSON {\"items\":[{\"id\":\"...\",\"todo\":true/false}]}"},
            {"role": "user", "content": json.dumps([
                {"id": r["id"], "group": r["group_name"], "sender": r["sender"], "text": r["text"]}
                for r in sample
            ], ensure_ascii=False)},
        ],
    }
    req = urllib.request.Request(
        url.rstrip("/") + "/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
    )
    with urllib.request.urlopen(req, timeout=45) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    content = body["choices"][0]["message"]["content"]
    items = json.loads(content).get("items", [])
    pred = {x["id"]: bool(x.get("todo")) for x in items if "id" in x}
    gold = {r["id"] for r in sample if r["label"] == "action" and not r["jielong"]}
    got = {rid for rid, v in pred.items() if v}
    tp = len(got & gold)
    rec = tp / max(1, len(gold))
    prec = tp / max(1, len(got))
    return {"sample_size": len(sample), "recall": round(rec, 4), "precision": round(prec, 4)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ood", type=Path, default=ROOT / "eval" / "strict_ood.jsonl")
    parser.add_argument("--out", type=Path, default=ROOT / "eval" / "benchmark_report.json")
    parser.add_argument("--llm-url", default=os.environ.get("OFFLOOR_LLM_URL", ""))
    parser.add_argument("--llm-model", default=os.environ.get("OFFLOOR_LLM_MODEL", ""))
    parser.add_argument("--llm-key", default=os.environ.get("OFFLOOR_LLM_KEY", ""))
    args = parser.parse_args()

    ood_rows = []
    for line in args.ood.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        r["dt"] = datetime.fromisoformat(r["ts"])
        ood_rows.append(r)
    ood_rows.sort(key=lambda r: (r["dt"], r["id"]))

    model = Model(ROOT / "engine" / "model.json")
    report = {
        "ood_dataset": {
            "path": str(args.ood.relative_to(ROOT)),
            "total": len(ood_rows),
            "action_todo_exam": sum(1 for r in ood_rows if r["label"] == "action" and not r["jielong"]),
            "jielong": sum(1 for r in ood_rows if r["jielong"]),
            "negatives": sum(1 for r in ood_rows if r["label"] != "action"),
        },
        "ood_baselines": {
            "keyword_only": eval_keyword_ood(ood_rows),
            "stage2_lr_no_rules": eval_python_ood(ood_rows, model, use_rules=False),
            "python_3stage_mixed_sender": eval_python_ood(ood_rows, model, use_rules=True),
            "python_3stage_all_teacher": eval_python_ood(ood_rows, model, use_rules=True, sender_override="王老师"),
            "python_3stage_all_student": eval_python_ood(ood_rows, model, use_rules=True, sender_override="林晓"),
        },
    }

    java, javac = shutil.which("java"), shutil.which("javac")
    if java and javac:
        java_snap, java_esc = run_java_ood(ood_rows, java, javac)
        # Java snapshot 只输出 deadline!=0 的时间；补齐 needs_confirm 语义
        for c in java_snap.cards:
            c.needs_confirm = (c.deadline is None)
        report["ood_baselines"]["apk_java_engine_mixed_sender"] = score_ood(
            ood_rows, java_snap.active_cards(), java_snap.jielong_ids, escalations=java_esc,
        )

    if (ROOT / "data" / "dataset.jsonl").is_file():
        report["synthetic_template_disjoint"] = eval_synth_template_disjoint(model)

    if args.llm_url and args.llm_model and args.llm_key:
        report["optional_live_llm_probe"] = optional_llm_probe(ood_rows, args.llm_url, args.llm_model, args.llm_key)

    cols = ["recall", "precision", "f1", "time_acc", "vague_restraint_acc", "jielong_recall", "local_only_pct"]
    print(f"严格真实分布外评测 ({len(ood_rows)} 条)\n")
    head = f"{'方案':<30}" + "".join(f"{c:>14}" for c in cols)
    print(head)
    print("-" * len(head))
    for name, m in report["ood_baselines"].items():
        row = f"{name:<30}"
        for c in cols:
            v = m.get(c)
            row += f"{(f'{v:.1%}' if isinstance(v, (int, float)) else '—'):>14}"
        print(row)

    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\n完整报告已写入 {args.out}")


if __name__ == "__main__":
    main()
