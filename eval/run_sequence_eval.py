"""Compare continuous notification processing with isolated per-message extraction."""
from __future__ import annotations

import argparse
import base64
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
DATASET = ROOT / "eval/sequence_cases.json"
MODEL = ROOT / "android/app/assets/model.tsv"
ZONE = timezone(timedelta(hours=8))
USER_ACTIONS = {"resolve", "ignore"}
LABELS = {"stateful": "连续处理（当前方案）", "isolated": "逐条抽取（对照）"}
SOURCES = ("Engine", "ChangeDiff", "StateFormat", "TaskChanges", "StudyRoomPlan",
           "StudyPlanner", "TodayPlan", "TodayView", "SectionTimes")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def to_wire(text):
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


def from_wire(text):
    return base64.b64decode(text).decode("utf-8")


def day(base, offset):
    return (datetime.fromisoformat(base).date() + timedelta(days=int(offset))).isoformat()


def stamp(date, clock):
    value = datetime.fromisoformat(clock if "T" in clock else f"{date}T{clock}")
    if value.tzinfo is None:
        value = value.replace(tzinfo=ZONE)
    return int(value.timestamp() * 1000)


def expected_tasks(base, check):
    result = {}
    for key, spec in check["tasks"].items():
        spec = {"deadline": spec} if isinstance(spec, str) else spec
        value = spec["deadline"]
        if "T" in value:
            deadline = stamp(base, value)
        elif " " in value:
            offset, clock = value.split()
            deadline = stamp(day(base, offset), clock)
        else:
            deadline = datetime.fromisoformat(value).date().isoformat()
        review = sorted(spec.get("review", []))
        result[key] = (deadline, spec.get("status", "active"), bool(review), review)
    return result


def protocol(data):
    base = data["date"]
    lines = []
    for case in data["cases"]:
        lines.append(("CASE", case["id"]))
        for offset, name, start, end in case.get("courses", []):
            lines.append(("COURSE", day(base, offset), to_wire(name), start, end))
        lines.append(("START",))
        checks = {check["after"]: check for check in case["checks"]}
        # Also sample the state before a protected event to separate a stale value from a new wrong change.
        for i, event in enumerate(case["messages"], 1):
            event_id, clock, content, *metadata = event
            at = str(stamp(base, clock))
            if event_id in USER_ACTIONS:
                lines.append(("USER", event_id, at, content, metadata[0]))
            else:
                group = metadata[0] if metadata and metadata[0] else "默认课程群"
                sender = metadata[1] if len(metadata) > 1 else "任课老师"
                lines.append(("MSG", event_id, at, to_wire(group), to_wire(sender), to_wire(content)))
            if i in checks or checks.get(i + 1, {}).get("protect"):
                observe_at = str(stamp(base, checks.get(i, {}).get("at", clock)))
                lines.append(("CHECK", str(i), observe_at))
    return "\n".join("\t".join(line) for line in lines) + "\n"


def run(classes, mode, input_text):
    result = subprocess.run(
        ["java", "-Duser.timezone=Asia/Shanghai", "-cp", classes, "com.nopalou.SequenceReplay", str(MODEL), mode],
        input=input_text, capture_output=True, encoding="ascii", check=True,
    )
    cases = {}
    for line in result.stdout.splitlines():
        tag, *fields = line.split("\t")
        if tag == "CASE":
            case = cases[fields[0]] = {"actions": [], "states": {}}
        elif tag == "ACTION":
            case["actions"].append(fields[0] == "true")
        elif tag == "CHECK":
            point = case["states"][int(fields[0])] = {"tasks": [], "clashes": [], "plan": None}
        elif tag == "CARD":
            source, deadline, status, needs_review, review, title = fields
            point["tasks"].append({"source": source, "deadline": int(deadline), "status": status,
                                   "needs_review": needs_review == "true", "review": review.split(",") if review else [],
                                   "title": from_wire(title)})
        elif tag == "CLASH":
            point["clashes"].append(fields[0] + "|" + from_wire(fields[1]))
        elif tag == "PLAN":
            target, date, start, end, minutes = fields
            point["plan"] = {"target": target, "date": date, "start": start, "end": end, "minutes": int(minutes)}
        else:
            raise ValueError(f"Unknown replay output {tag}")
    return cases


def compare_point(base, check, point, previous):
    expected = expected_tasks(base, check)
    observed = defaultdict(list)
    for task in point["tasks"]:
        observed[task["source"]].append(task)
    mismatched = []
    missing_active = []
    stale_cancel = []
    correction_count = 0
    for source, (deadline, status, needs_review, review) in expected.items():
        matches = observed[source]
        task = matches[0] if matches else None
        deadline_matches = False
        if task is not None:
            actual_deadline = task["deadline"]
            if isinstance(deadline, str):
                observed_date = datetime.fromtimestamp(actual_deadline / 1000, ZONE).date().isoformat()
                deadline_matches = actual_deadline > 0 and observed_date == deadline
            else:
                deadline_matches = actual_deadline == deadline
        state_matches = task is not None and deadline_matches and task["status"] == status
        if not state_matches:
            correction_count += 1
        if status == "active" and (task is None or task["status"] != "active"):
            missing_active.append(source)
        if status == "cancelled" and task is not None and task["status"] == "active":
            stale_cancel.append(source)
        if not state_matches or task["review"] != review or (
                not isinstance(deadline, str) and task["needs_review"] != needs_review):
            mismatched.append(source)
    extra = [source for source, tasks in observed.items()
             for _ in range(max(0, len(tasks) - int(source in expected)))]
    expected_review = sorted({event for spec in expected.values() for event in spec[3]})
    surfaced_review = sorted({event for task in point["tasks"] for event in task["review"]})
    before = {t["source"]: (t["deadline"], t["status"]) for t in previous["tasks"]}
    after = {t["source"]: (t["deadline"], t["status"]) for t in point["tasks"]}
    wrong_change = [source for source in check.get("protect", [])
                    if source in before and after.get(source) != before[source]]
    clash_ok = None if "clashes" not in check else sorted(check["clashes"]) == sorted(point["clashes"])
    plan = check.get("plan")
    if plan is not None:
        plan = {k: v for k, v in plan.items() if k != "day"} | {"date": day(base, plan["day"])}
    plan_ok = point["plan"] == plan if "plan" in check else None
    return {
        "passed": not (mismatched or extra or wrong_change or clash_ok is False or plan_ok is False),
        "mismatched_tasks": mismatched, "extra_cards": extra, "missing_active": missing_active,
        "stale_cancellations": stale_cancel, "wrong_changes": wrong_change,
        "correction_count": correction_count + len(extra),
        "expected_review": expected_review, "surfaced_review": surfaced_review,
        "clash_ok": clash_ok, "plan_ok": plan_ok,
        "expected": check, "actual": point,
    }


def summarize(data, replay):
    totals, families = Counter(), defaultdict(Counter)
    results = []
    for case in data["cases"]:
        observed = replay[case["id"]]
        states = observed["states"]
        checks = [compare_point(data["date"], spec, states[spec["after"]],
                                states.get(spec["after"] - 1, {"tasks": []})) for spec in case["checks"]]
        actions = observed["actions"]
        passed = all(c["passed"] for c in checks) and all(actions)
        result = Counter(scenarios=1, scenarios_passed=int(passed), checkpoints=len(checks),
                         checkpoints_passed=sum(c["passed"] for c in checks),
                         final_corrections=checks[-1]["correction_count"], user_actions=len(actions),
                         actions_applied=sum(actions))
        for spec, check in zip(case["checks"], checks):
            result.update({
                "missing_active": len(check["missing_active"]),
                "extra_cards": len(check["extra_cards"]),
                "stale_cancellations": len(check["stale_cancellations"]),
                "protected_targets": len(spec.get("protect", [])),
                "wrong_changes": len(check["wrong_changes"]),
                "expected_review": len(check["expected_review"]),
                "review_detected": len(set(check["expected_review"]) & set(check["surfaced_review"])),
                "false_review": len(set(check["surfaced_review"]) - set(check["expected_review"])),
                "clash_checks": int(check["clash_ok"] is not None),
                "clash_passed": int(check["clash_ok"] is True),
                "plan_checks": int(check["plan_ok"] is not None),
                "plan_passed": int(check["plan_ok"] is True),
            })
        totals.update(result)
        families[case["family"]].update(result)
        results.append({"id": case["id"], "family": case["family"], "passed": passed,
                        **{k: case[k] for k in ("title", "source_mode", "sources") if k in case},
                        "user_actions_applied": actions, "checks": checks})
    return {"summary": dict(totals), "families": {k: dict(v) for k, v in families.items()}, "cases": results}


def markdown(report):
    summary = report["results"]["stateful"]["summary"]
    public = report["data_kind"] == "public_notice_paraphrases"
    caption = "公开通知改写回放" if public else "连续通知对照评测"
    sample = "公开来源回放" if public else "构造会话"
    rows = [f"# outfloor {caption}", "",
            f"{summary['scenarios']} 个{sample}，{summary['checkpoints']} 个检查点。输入和预期在首次运行前冻结。"
            "两个方案用同一 Java 引擎、"
            "模型、课程资料和规划器。对照组每条通知都从空上下文抽取，再将出卡集合交给规划器。"
            "它不持有待关联通知，无法执行关联或忽略动作。此比较度量持续维护事项的贡献。", ""]
    if report.get("provenance"):
        rows += [report["provenance"], ""]
    if report.get("first_result"):
        initial = report["first_result"]["summaries"]
        rows += ["本表是根据首测发现修复后，在相同样本上进行的回归。首测数据和失败记录另行保留。", "",
                 "| 首测指标 | 当时的连续处理 | 当时的逐条抽取 |", "|---|---:|---:|"]
        for label, key, denom in (("整场景通过", "scenarios_passed", "scenarios"),
                                   ("检查点通过", "checkpoints_passed", "checkpoints"),
                                   ("末态需修正的事项数", "final_corrections", None)):
            cells = [f"{v[key]}/{v[denom]}" if denom else str(v[key]) for v in initial.values()]
            rows.append(f"| {label} | {' | '.join(cells)} |")
        if public:
            rows += ["", "复测包含两项修复：识别包含明确日期的投稿要求，及处理同时列明旧日期和新日期的延期。"
                     "延期需在同一群里匹配事项和旧截止，新截止取变更词后的日期。", ""]
        else:
            rows += ["", "首测发现，含具体时间的「改到」会让部分通知另建事项，原卡仍保留旧截止。"
                     "另外，只有「按原通知交」的提醒有时也会出卡。修复覆盖这两类问题，并为地点变更、问句以及保留旧时间的措辞加了回归。", ""]
    rows += ["| 指标 | 当前方案 | 逐条抽取 |", "|---|---:|---:|"]
    metrics = [
        ("整场景通过", "scenarios_passed", "scenarios"),
        ("检查点通过", "checkpoints_passed", "checkpoints"),
        ("错改被保护的事项", "wrong_changes", "protected_targets"),
        ("应保留但不活跃的事项（按检查点计）", "missing_active", None),
        ("多余事项（按检查点计）", "extra_cards", None),
        ("取消后仍活跃的事项（按检查点计）", "stale_cancellations", None),
        ("应核对的变更已提示", "review_detected", "expected_review"),
        ("本人关联或忽略成功", "actions_applied", "user_actions"),
        ("撞课结果正确", "clash_passed", "clash_checks"),
        ("空档与截止前可用时长正确", "plan_passed", "plan_checks"),
        ("末态需修正的事项数", "final_corrections", None),
    ]
    for label, numerator, denominator in metrics:
        values = [report["results"][key]["summary"] for key in LABELS]
        if denominator and all(v[denominator] == 0 for v in values):
            continue
        cells = [f"{v[numerator]}/{v[denominator]}" if denominator else str(v[numerator]) for v in values]
        rows.append(f"| {label} | {' | '.join(cells)} |")
    rows += ["", "末态修正数按补建、移除多余事项、修正截止或取消状态各计一项。"
             "一次操作能否处理多个事项未实测，核对提示和场景指定的本人操作单列。"
             "重复落在多个检查点的问题可重复计入检查点指标。", "",
             "| 场景类别 | 当前方案整场通过 | 逐条抽取整场通过 |", "|---|---:|---:|"]
    results = report["results"]
    for family, value in results["stateful"]["families"].items():
        baseline = results["isolated"]["families"][family]
        rows.append(f"| {family} | {value['scenarios_passed']}/{value['scenarios']} | "
                    f"{baseline['scenarios_passed']}/{baseline['scenarios']} |")
    rows += ["", "## 当前方案的失败检查", ""]
    failures = [case for case in results["stateful"]["cases"] if not case["passed"]]
    for case in failures:
        failed_steps = [str(c["expected"]["after"]) for c in case["checks"] if not c["passed"]]
        rows.append(f"- `{case['id']}`（{case['family']}），失败检查点 {', '.join(failed_steps) or '无'}。"
                    f"本人动作结果 {case['user_actions_applied']}。完整期望和实际状态见 JSON。")
    if not failures:
        rows.append("当前冻结样本上没有失败检查。")
    if public:
        rows += ["", "## 公开来源与当前方案结果", "",
                 "| 场景 | 原安排由来 | 逐步检查 | 来源 |", "|---|---|---|---|"]
        for case in results["stateful"]["cases"]:
            links = " · ".join(f"[{item['label']}]({item['url']})" for item in case["sources"])
            steps = " / ".join("通过" if step["passed"] else "失败" for step in case["checks"])
            rows.append(f"| {case['title']} | {case['source_mode']} | {steps} | {links} |")
        rows += ["", "原文事实来自官网，输入由本项目人工改写。这是有公开来源的小样本回放，覆盖有限。"
                 "原通知出卡失败时，后续无法据此判断已有事项能否正确变更。"
                 "真实 Android 通知采集、用户行为和群聊效果均未测。"]
    else:
        rows += ["", "样本由开发时构造，应用与评测作者相关，结果只适用于这组会话。"
                 "此次没有打通真实通知、账号、用户试用或云端模型对照。"]
    rows += ["",
             f"样本 SHA-256：`{report['dataset_sha256']}`",
             f"模型 SHA-256：`{report['model_sha256']}`",
             f"源码提交：`{report['source_commit']}`", "",
             "复现命令", "", "```sh",
             "python eval/run_sequence_eval.py "
             + (f'--dataset "{report["dataset_path"]}" ' if "dataset_path" in report else "")
             + "--out sequence.json --markdown sequence.md",
             "```", ""]
    return "\n".join(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--markdown", type=Path)
    parser.add_argument("--dataset", type=Path, default=DATASET)
    parser.add_argument("--first-result", type=Path, help="Include the first report for the same frozen dataset and model")
    parser.add_argument("--require-stateful-pass", action="store_true",
                        help="Exit with failure when any current-engine scenario fails, retaining the report")
    args = parser.parse_args()
    raw = args.dataset.read_bytes()
    data = json.loads(raw)
    input_text = protocol(data)
    app = ROOT / "android/app/src/com/nopalou"
    with tempfile.TemporaryDirectory(prefix=".sequence-", dir=ROOT) as temp:
        subprocess.run(["javac", "-encoding", "UTF-8", "--release", "11", "-d", temp,
                        *(str(app / (name + ".java")) for name in SOURCES),
                        str(ROOT / "eval/ReliabilityReplay.java"), str(ROOT / "eval/SequenceReplay.java")], check=True)
        results = {mode: summarize(data, run(temp, mode, input_text)) for mode in LABELS}
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    report = {"data_kind": data["data_kind"], "dataset_sha256": sha256(raw),
              "model_sha256": sha256(MODEL.read_bytes()), "source_commit": commit, "results": results}
    dataset_path = args.dataset.resolve()
    if dataset_path != DATASET:
        report["dataset_path"] = (dataset_path.relative_to(ROOT) if dataset_path.is_relative_to(ROOT)
                                  else dataset_path).as_posix()
    if "provenance" in data:
        report["provenance"] = data["provenance"]
    if args.first_result:
        initial = json.loads(args.first_result.read_text(encoding="utf-8"))
        if any(initial[key] != report[key] for key in ("dataset_sha256", "model_sha256")):
            raise ValueError("First report must use the same frozen dataset and model")
        report["first_result"] = {"source_commit": initial["source_commit"],
                                  "summaries": {mode: initial["results"][mode]["summary"] for mode in LABELS}}
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.markdown:
        args.markdown.write_text(markdown(report), encoding="utf-8")
    for mode, value in results.items():
        print(mode, json.dumps(value["summary"], ensure_ascii=False))
    if args.require_stateful_pass:
        cases = results["stateful"]["cases"]
        failed = [case["id"] for case in cases if not case["passed"]]
        if not cases or failed:
            print("Required scenario checks failed: " + (", ".join(failed) if failed else "empty dataset"))
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
