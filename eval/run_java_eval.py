"""用 APK 的 Java Engine 在同一合成留出集上复用 Python 评分口径。"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from base64 import b64decode, b64encode
from datetime import datetime
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_eval import ROOT, Model, Pipeline, load, score, split_report, unseen_todo_score


class EngineSnapshot:
    """给现有评分器提供 Java 最终保留的卡片和计数。"""

    def __init__(self, output: str, expected_count: int, timing: bool = False):
        counts, jielong_sources, *records = output.splitlines()
        n_in, self.n_noise, self.n_level3, self.n_promoted = map(int, counts.split("\t"))
        if n_in != expected_count:
            raise RuntimeError(f"Java 处理了 {n_in} 条消息，预期 {expected_count} 条")
        self.jielong_ids = {b64decode(value).decode("utf-8")
                            for value in jielong_sources.split(",") if value}
        self.timing = None
        if timing:
            version, samples, non_noise, total, p50, p95, p99, non_noise_p95 = records.pop(0).split("\t")
            if int(samples) != n_in or int(non_noise) != n_in - self.n_noise:
                raise RuntimeError("Java 耗时样本数与处理计数不一致")
            self.timing = {
                "scope": "桌面 JVM 单次顺序回放，只计 Engine.process，不含界面与持久化",
                "java_version": version,
                "samples": int(samples),
                "non_noise_samples": int(non_noise),
                "total_ms": int(total) / 1_000_000,
                "p50_ms": int(p50) / 1_000_000,
                "p95_ms": int(p95) / 1_000_000,
                "p99_ms": int(p99) / 1_000_000,
                "non_noise_p95_ms": int(non_noise_p95) / 1_000_000,
            }
        self.cards = []
        for record in records:
            kind, source_id, group_id, text, deadline = record.split("\t")
            self.cards.append(SimpleNamespace(
                kind=kind,
                source_msg_id=b64decode(source_id).decode("utf-8"),
                group_id=b64decode(group_id).decode("utf-8"),
                source_text=b64decode(text).decode("utf-8"),
                deadline=datetime.fromisoformat(deadline) if deadline else None,
            ))

    def active_cards(self):
        return self.cards


def run_java(rows: list[dict], java: str, javac: str, timing: bool = False) -> EngineSnapshot:
    app = ROOT / "android" / "app"
    fields = ("msg_id", "group_id", "group_name", "sender", "text")
    messages = "".join(
        row["dt"].isoformat() + "\t" +
        "\t".join(b64encode((row.get(field) or "").encode("utf-8")).decode("ascii") for field in fields) +
        "\n"
        for row in rows
    )
    with TemporaryDirectory(prefix="nopalou-java-eval-", dir=Path.cwd()) as classes:
        subprocess.run(
            [javac, "-encoding", "UTF-8", "-d", classes,
             str(app / "src" / "com" / "nopalou" / "Engine.java"),
             str(app / "src" / "com" / "nopalou" / "ChangeDiff.java"), str(ROOT / "eval" / "EngineEval.java")],
            check=True,
        )
        result = subprocess.run(
            [java, "-Duser.timezone=Asia/Shanghai", "-cp", classes, "com.nopalou.EngineEval",
             str(app / "assets" / "model.tsv"), *(["--timing"] if timing else [])],
            input=messages, stdout=subprocess.PIPE, encoding="ascii", check=True,
        )
    return EngineSnapshot(result.stdout, len(rows), timing)


def score_jielong(rows: list[dict], reminder_ids: set[str]) -> dict:
    """按每条消息当时是否触发提醒计分，包含非接龙消息误报。"""
    gold = {row["msg_id"] for row in rows if row.get("jielong") and not row["jielong"]["joined"]}
    hits = len(gold & reminder_ids)
    return {
        "expected": len(gold),
        "hits": hits,
        "missed": len(gold - reminder_ids),
        "false_positives": len(reminder_ids - gold),
        "recall": round(hits / max(1, len(gold)), 4),
        "precision": round(hits / max(1, len(reminder_ids)), 4),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, help="另存对照 JSON 报告的路径")
    parser.add_argument("--timing", action="store_true", help="附加桌面 JVM 中 Engine.process 的耗时统计")
    args = parser.parse_args()
    java, javac = shutil.which("java"), shutil.which("javac")
    if not java or not javac:
        parser.error("需要在 PATH 中提供 JDK 的 java 和 javac")

    rows = load(ROOT / "data" / "dataset.jsonl")
    cut = int(len(rows) * 0.7)
    train_rows, hold = rows[:cut], rows[cut:]
    split, train_texts = split_report(train_rows, hold)
    java_snapshot = run_java(hold, java, javac, args.timing)
    python_pipeline = Pipeline(Model(ROOT / "engine" / "model.json"))
    python_jielong_ids = set()
    for row in hold:
        python_jielong_ids.update(card.source_msg_id for card in python_pipeline.process(row)
                                  if card.kind == "jielong")

    report = {"split": split}
    for name, engine, reminder_ids in (
        ("Java", java_snapshot, java_snapshot.jielong_ids),
        ("Python", python_pipeline, python_jielong_ids),
    ):
        report[name] = {
            # 原评分器按最终卡片统计接龙，并按群匹配更正。两端保留的历史卡片不同。
            "holdout": {key: value for key, value in score(hold, engine).items()
                        if not key.startswith(("jielong_", "revision_"))},
            "unseen_todos": unseen_todo_score(hold, engine, train_texts),
            "jielong_per_message": score_jielong(hold, reminder_ids),
        }

    if java_snapshot.timing is not None:
        report["java_process_timing"] = java_snapshot.timing
    content = json.dumps(report, ensure_ascii=False, indent=2)
    if args.out:
        args.out.write_text(content + "\n", encoding="utf-8")
    else:
        print(content)


if __name__ == "__main__":
    main()
