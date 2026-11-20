# -*- coding: utf-8 -*-
"""同集四路对照与 GBNF 消融评测脚本（直接驱动 APK 原版 Java 引擎与 AiClient）。

在同一份冻结的 100 条无发送者泄漏分布外基准（eval/strict_ood.jsonl）上，
严格使用同一评分口径（score_ood）对比：
  1. 仅关键词规则（Python）
  2. 仅 Stage 2 线性模型（无规则，Python）
  3. 纯大模型全量直抽：端侧 Qwen2.5-1.5B-Instruct (Q4_K_M) + GBNF（100 条全送）
  4. 纯大模型全量直抽：参考大模型 Qwen3-8B (Q4_K_M) + GBNF（100 条全送）
  5. 本系统纯本机（1~2 级）：APK Java 引擎 Engine.java（0 次大模型调用）
  6. 本系统完整端侧闭环：APK Java 引擎 + 端侧 Qwen2.5-1.5B (GBNF) 复核（仅升级项）
  7. 本系统端云/强模型协同：APK Java 引擎 + Qwen3-8B (GBNF) 复核（仅升级项）

并额外在真实升级批次（每批 3 条）上执行 GBNF 语法约束消融（无 GBNF vs LocalLlmCore.judgeGrammar）。
"""
from __future__ import annotations

import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
from tempfile import TemporaryDirectory
import time
from types import SimpleNamespace
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "android/app/src/com/nopalou"
SOURCES = (
    "Engine", "ChangeDiff", "StateFormat", "TaskChanges",
    "StudyRoomPlan", "StudyPlanner", "TodayPlan", "TodayView", "SectionTimes",
)

sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "eval"))
from engine.pipeline import Model  # noqa: E402
from run_benchmark import eval_keyword_ood, eval_python_ood, score_ood  # noqa: E402


def load_ood(path: Path) -> list[dict]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        r = json.loads(line)
        r["dt"] = datetime.fromisoformat(r["ts"])
        rows.append(r)
    rows.sort(key=lambda r: (r["dt"], r["id"]))
    return rows


def compile_java_bridge(classes_dir: Path, android_jar: Path, json_jar: Path) -> str:
    cp = os.pathsep.join((str(classes_dir), str(json_jar.resolve()), str(android_jar.resolve())))
    adapters = sorted(
        {"AiQueue", "AiConfig", "AiSecrets", "AiBudget", "AccountFiles", "UpgradeDataBackup", "LocalModelStore"}
        | {
            f.stem
            for f in APP.glob("*.java")
            if f.stem not in SOURCES
            and not re.search(r"^import android\.", f.read_text(encoding="utf-8"), re.M)
        }
    )
    stub = classes_dir / "EngineHolder.java"
    stub.write_text(
        "package com.nopalou; final class EngineHolder { "
        "static void diag(android.content.Context c, String a, String b) {} }",
        encoding="utf-8",
    )
    cmd = [
        "javac", "-encoding", "UTF-8", "--release", "11", "-cp", cp, "-d", str(classes_dir),
        str(stub),
        *[str(APP / f"{s}.java") for s in (*SOURCES, *adapters)],
        str(ROOT / "eval/JavaAiEval.java"),
    ]
    subprocess.run(cmd, check=True)
    return cp


def run_java_bridge(cp: str, *args: str) -> None:
    subprocess.run(
        ["java", "-Dfile.encoding=UTF-8", "-Duser.timezone=Asia/Shanghai", "-cp", cp, "com.nopalou.JavaAiEval", *args],
        check=True,
    )


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class LlamaServer:
    def __init__(self, binary: Path, model_path: Path, ngl: int = 99, ctx: int = 4096, disable_thinking: bool = False):
        self.binary = binary
        self.model_path = model_path
        self.ngl = ngl
        self.ctx = ctx
        self.disable_thinking = disable_thinking
        self.port = free_port()
        self.proc: subprocess.Popen | None = None
        self.url = f"http://127.0.0.1:{self.port}"
        self.load_seconds = 0.0

    def __enter__(self) -> "LlamaServer":
        cmd = [
            str(self.binary),
            "-m", str(self.model_path),
            "--host", "127.0.0.1",
            "--port", str(self.port),
            "-c", str(self.ctx),
            "-ngl", str(self.ngl),
            "-fa", "on",
            "-t", str(min(16, max(4, (os.cpu_count() or 8) // 2))),
            "--parallel", "1",
        ]
        if self.disable_thinking:
            cmd.extend(["--reasoning", "off", "--reasoning-budget", "0"])
        t0 = time.perf_counter()
        self.proc = subprocess.Popen(
            cmd,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        deadline = time.perf_counter() + 120
        while time.perf_counter() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError(f"llama-server 提前退出（退出码 {self.proc.returncode}）：{' '.join(cmd)}")
            try:
                with urllib.request.urlopen(f"{self.url}/health", timeout=2) as resp:
                    if resp.status == 200:
                        body = json.loads(resp.read().decode("utf-8"))
                        if body.get("status") == "ok":
                            self.load_seconds = round(time.perf_counter() - t0, 3)
                            return self
            except Exception:
                pass
            time.sleep(0.3)
        raise TimeoutError(f"等待 llama-server 就绪超时：{self.model_path}")

    def __exit__(self, exc_type, exc, tb) -> None:
        if self.proc is not None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()


def strip_think(text: str) -> str:
    v = text or ""
    while True:
        a = v.find("<think>")
        if a < 0:
            break
        b = v.find("</think>", a)
        v = v[:a] if b < 0 else v[:a] + v[b + len("</think>"):]
    return v.strip()


def strip_fence(text: str) -> str:
    v = (text or "").strip()
    a, b = v.find("{"), v.rfind("}")
    return v[a : b + 1] if a >= 0 and b > a else v


def call_chat(base_url: str, system: str, user: str, grammar: str | None,
              max_tokens: int = 1500, no_think: bool = False,
              api_key: str = "", model_name: str = "local") -> dict:
    user_msg = user + "\n/no_think" if no_think else user
    payload: dict = {
        "model": model_name,
        "temperature": 0.0,
        "max_tokens": max_tokens,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user_msg},
        ],
    }
    if grammar:
        payload["grammar"] = grammar
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    req = urllib.request.Request(
        base_url.rstrip("/") + "/v1/chat/completions",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers=headers,
    )
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=180) as resp:
        raw_body = json.loads(resp.read().decode("utf-8"))
    elapsed = time.perf_counter() - t0
    msg = raw_body["choices"][0]["message"]
    content = msg.get("content") or ""
    usage = raw_body.get("usage") or {}
    return {
        "content": content,
        "elapsed_s": round(elapsed, 4),
        "prompt_tokens": int(usage.get("prompt_tokens", 0) or 0),
        "completion_tokens": int(usage.get("completion_tokens", 0) or 0),
        "total_tokens": int(usage.get("total_tokens", 0) or 0),
        "finish_reason": raw_body["choices"][0].get("finish_reason", ""),
    }


def run_batches(base_url: str, batches: list[dict], use_grammar: bool, no_think: bool,
                label: str, cache_path: Path | None = None,
                api_key: str = "", model_name: str = "local") -> dict:
    if cache_path and cache_path.is_file():
        print(f"  [缓存复用] {label} <- {cache_path.name}", flush=True)
        return json.loads(cache_path.read_text(encoding="utf-8"))
    outputs = []
    batch_stats = []
    total_elapsed = 0.0
    total_prompt = 0
    total_comp = 0
    for idx, b in enumerate(batches, 1):
        r = call_chat(
            base_url,
            b["system"],
            b["user"],
            b["grammar"] if use_grammar else None,
            max_tokens=1500,
            no_think=no_think,
            api_key=api_key,
            model_name=model_name,
        )
        outputs.append(r["content"])
        total_elapsed += r["elapsed_s"]
        total_prompt += r["prompt_tokens"]
        total_comp += r["completion_tokens"]
        batch_stats.append({
            "batch_index": b["batch_index"],
            "size": b["size"],
            "source_ids": b["source_ids"],
            "elapsed_s": r["elapsed_s"],
            "prompt_tokens": r["prompt_tokens"],
            "completion_tokens": r["completion_tokens"],
            "finish_reason": r["finish_reason"],
        })
        if idx == 1 or idx % 5 == 0 or idx == len(batches):
            eta = (total_elapsed / idx) * (len(batches) - idx)
            print(f"    [进度] {label}: {idx}/{len(batches)} 批 | 累计 {total_elapsed:.1f}s | 预计剩余 ~{eta:.0f}s", flush=True)
    result = {
        "label": label,
        "use_grammar": use_grammar,
        "batches": len(batches),
        "messages": sum(b["size"] for b in batches),
        "total_elapsed_s": round(total_elapsed, 3),
        "avg_batch_s": round(total_elapsed / max(1, len(batches)), 3),
        "prompt_tokens": total_prompt,
        "completion_tokens": total_comp,
        "total_tokens": total_prompt + total_comp,
        "batch_outputs": outputs,
        "batch_stats": batch_stats,
    }
    if cache_path:
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        cache_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"  [完成] {label}: {len(batches)} 批 / {result['messages']} 条 | "
        f"{result['total_elapsed_s']}s | tokens={result['prompt_tokens']}+{result['completion_tokens']}",
        flush=True,
    )
    return result


def parse_iso_or_clock(s: str) -> datetime | None:
    v = (s or "").strip().replace("/", "-")
    if not v:
        return None
    if re.fullmatch(r"\d{4}-\d{2}-\d{2}", v):
        v += " 23:59"
    for fmt in ("%Y-%m-%d %H:%M", "%Y-%m-%dT%H:%M", "%Y-%m-%dT%H:%M:%S"):
        try:
            return datetime.strptime(v, fmt)
        except ValueError:
            pass
    return None


def cards_from_java_json(cards_json: list[dict]) -> list[SimpleNamespace]:
    out = []
    for c in cards_json:
        if c.get("done") or c.get("cancelled"):
            continue
        dl = datetime.fromisoformat(c["deadline"]) if c.get("deadline") else None
        out.append(SimpleNamespace(
            kind=c["kind"],
            source_msg_id=c["source_id"],
            deadline=dl,
            needs_confirm=bool(c.get("needs_confirm", False)),
            title=c.get("title", ""),
        ))
    return out


def score_direct_llm(rows: list[dict], direct_batches: list[dict], run_res: dict) -> dict:
    """对纯大模型全量直抽结果按与 score_ood 完全一致的口径评分。

    说明：
    - 非接龙样本：todo=true 记为出卡；若输出合法时间记为 deadline；
      若 deadline 为空或 time_sure=false 则记 needs_confirm=True。
    - 接龙样本（10 条：5 未接 / 5 已接）：为对纯大模型保持最大公平，
      凡接龙样本被模型判为 todo=true 均计入 jielong_trigger_ids，
      检查模型能否区分「你还没接（应触发）」与「你已接过（不应触发）」。
    """
    by_id = {r["id"]: r for r in rows}
    cards = []
    jielong_hits: set[str] = set()
    json_valid_batches = 0
    valid_items = 0
    missing_items = 0
    false_positives = []
    false_negatives = []
    time_errors = []
    vague_hallucinations = []

    pred_by_sid: dict[str, dict] = {}
    for b, raw in zip(direct_batches, run_res["batch_outputs"]):
        cleaned = strip_fence(strip_think(raw))
        try:
            obj = json.loads(cleaned)
            items = obj.get("items")
            if not isinstance(items, list):
                raise ValueError("items not list")
            json_valid_batches += 1
        except Exception:
            missing_items += b["size"]
            continue
        id_map = {str(k + 1): sid for k, sid in enumerate(b["source_ids"])}
        seen = set()
        for item in items:
            if not isinstance(item, dict):
                continue
            iid = str(item.get("id", ""))
            if iid not in id_map or iid in seen:
                continue
            if not isinstance(item.get("todo"), bool):
                continue
            seen.add(iid)
            valid_items += 1
            pred_by_sid[id_map[iid]] = item
        missing_items += max(0, b["size"] - len(seen))

    for r in rows:
        sid = r["id"]
        item = pred_by_sid.get(sid)
        if not item:
            if r["label"] == "action" and not r["jielong"]:
                false_negatives.append({"id": sid, "reason": "missing_item", "text": r["text"]})
            continue
        is_todo = bool(item.get("todo"))
        if r["jielong"]:
            if is_todo:
                jielong_hits.add(sid)
            continue
        if is_todo:
            dl = parse_iso_or_clock(str(item.get("deadline") or ""))
            time_sure = bool(item.get("time_sure", False))
            needs_confirm = (dl is None) or (not time_sure)
            kind = "exam" if item.get("kind") == "exam" else "todo"
            cards.append(SimpleNamespace(
                kind=kind,
                source_msg_id=sid,
                deadline=dl,
                needs_confirm=needs_confirm,
                title=str(item.get("title") or ""),
            ))
            if r["label"] != "action":
                false_positives.append({
                    "id": sid, "category": r.get("category"), "sender": r["sender"],
                    "text": r["text"], "note": item.get("note", ""),
                })
            elif r.get("deadline"):
                gold_dl = datetime.fromisoformat(r["deadline"])
                if dl != gold_dl:
                    time_errors.append({
                        "id": sid, "expected": r["deadline"],
                        "got": dl.isoformat() if dl else "",
                        "text": r["text"],
                    })
            elif r.get("needs_confirm") and dl is not None and time_sure:
                vague_hallucinations.append({
                    "id": sid, "got_deadline": dl.isoformat(), "text": r["text"],
                })
        else:
            if r["label"] == "action":
                false_negatives.append({
                    "id": sid, "category": r.get("category"), "sender": r["sender"],
                    "text": r["text"], "note": item.get("note", ""),
                })

    metrics = score_ood(rows, cards, jielong_hits, escalations=len(rows))
    metrics.update({
        "llm_batches": run_res["batches"],
        "llm_messages": run_res["messages"],
        "json_valid_batches": f"{json_valid_batches}/{len(direct_batches)}",
        "valid_items": f"{valid_items}/{len(rows)}",
        "missing_items": missing_items,
        "desktop_elapsed_s": run_res["total_elapsed_s"],
        "prompt_tokens": run_res["prompt_tokens"],
        "completion_tokens": run_res["completion_tokens"],
        "total_tokens": run_res["total_tokens"],
        "error_samples": {
            "false_positives": false_positives,
            "false_negatives": false_negatives,
            "time_errors": time_errors,
            "vague_hallucinations": vague_hallucinations,
        },
    })
    return metrics


def score_hybrid(rows: list[dict], apply_out: dict, run_res: dict) -> dict:
    cards = cards_from_java_json(apply_out["final_cards"])
    jl_hits = set(apply_out["jielong_hits"])
    metrics = score_ood(rows, cards, jl_hits, escalations=apply_out["total_escalations"])
    by_id = {r["id"]: r for r in rows}
    got_ids = {c.source_msg_id for c in cards if c.kind in ("todo", "exam")}
    gold_ids = {r["id"] for r in rows if r["label"] == "action" and not r["jielong"]}
    fps = [{"id": sid, "category": by_id[sid].get("category"), "text": by_id[sid]["text"]}
           for sid in sorted(got_ids - gold_ids)]
    fns = [{"id": sid, "category": by_id[sid].get("category"), "text": by_id[sid]["text"]}
           for sid in sorted(gold_ids - got_ids)]
    time_errors = []
    for r in rows:
        if r["label"] != "action" or r["jielong"] or not r.get("deadline"):
            continue
        card = next((c for c in cards if c.source_msg_id == r["id"]), None)
        if not card or card.deadline != datetime.fromisoformat(r["deadline"]):
            time_errors.append({
                "id": r["id"], "expected": r["deadline"],
                "got": card.deadline.isoformat() if (card and card.deadline) else "",
                "text": r["text"],
            })
    metrics.update({
        "llm_batches": run_res["batches"],
        "llm_messages": run_res["messages"],
        "json_valid_batches": f"{apply_out['json_valid_batches']}/{apply_out['total_batches']}",
        "parsed_verdicts": f"{apply_out['parsed_verdicts']}/{apply_out['total_escalations']}",
        "applied_verdicts": apply_out["applied_verdicts"],
        "parse_errors": apply_out["parse_errors"],
        "desktop_elapsed_s": run_res["total_elapsed_s"],
        "prompt_tokens": run_res["prompt_tokens"],
        "completion_tokens": run_res["completion_tokens"],
        "total_tokens": run_res["total_tokens"],
        "trace": apply_out["trace"],
        "error_samples": {
            "false_positives": fps,
            "false_negatives": fns,
            "time_errors": time_errors,
        },
    })
    return metrics


def summarize_ablation(apply_out: dict, run_res: dict) -> dict:
    return {
        "use_grammar": run_res["use_grammar"],
        "batches": apply_out["total_batches"],
        "escalations": apply_out["total_escalations"],
        "json_valid_batches": apply_out["json_valid_batches"],
        "json_valid_rate": round(apply_out["json_valid_batches"] / max(1, apply_out["total_batches"]), 4),
        "raw_items_returned": apply_out["raw_items_returned"],
        "parsed_verdicts": apply_out["parsed_verdicts"],
        "valid_verdict_rate": round(apply_out["parsed_verdicts"] / max(1, apply_out["total_escalations"]), 4),
        "applied_verdicts": apply_out["applied_verdicts"],
        "parse_errors": apply_out["parse_errors"],
        "desktop_elapsed_s": run_res["total_elapsed_s"],
        "prompt_tokens": run_res["prompt_tokens"],
        "completion_tokens": run_res["completion_tokens"],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ood", type=Path, default=ROOT / "eval/strict_ood.jsonl")
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--json-jar", type=Path, required=True)
    parser.add_argument("--llama-server", type=Path, required=True)
    parser.add_argument("--small-gguf", type=Path, required=True, help="端侧 1.5B GGUF（如 qwen2.5-1.5b-instruct-q4_k_m.gguf）")
    parser.add_argument("--small-name", default="Qwen2.5-1.5B-Instruct (Q4_K_M)")
    parser.add_argument("--large-gguf", type=Path, help="可选较大参考模型 GGUF（如 qwen3:8b）")
    parser.add_argument("--large-name", default="Qwen3-8B (Q4_K_M)")
    parser.add_argument("--large-no-think", action="store_true", default=True)
    parser.add_argument("--ngl", type=int, default=99)
    parser.add_argument("--large-ngl", type=int, default=24)
    parser.add_argument("--large-ctx", type=int, default=2560)
    parser.add_argument("--cache-dir", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=ROOT / "eval/llm_comparison_report.json")
    args = parser.parse_args()

    rows = load_ood(args.ood)
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    model_py = Model(ROOT / "engine/model.json")

    with TemporaryDirectory(prefix="offloor-ai-eval-") as td:
        td_path = Path(td)
        print("1. 编译 APK 原版 Java 评测桥接器（Engine + AiClient + LocalLlmCore）...", flush=True)
        cp = compile_java_bridge(td_path, args.android_jar, args.json_jar)

        exported_path = args.cache_dir / "java_exported.json"
        run_java_bridge(cp, "export", str(ROOT / "android/app/assets/model.tsv"), str(args.ood), str(exported_path))
        exported = json.loads(exported_path.read_text(encoding="utf-8"))
        print(
            f"   纯本机 APK Java 引擎：输入 {exported['n_in']} 条，过滤噪声 {exported['n_noise']} 条，"
            f"触发第 3 级复核 {exported['n_escalations']} 条（{len(exported['hybrid_batches'])} 批）。",
            flush=True,
        )

        local_cards = cards_from_java_json(exported["local_cards"])
        local_metrics = score_ood(
            rows, local_cards, set(exported["jielong_hits"]),
            escalations=exported["n_escalations"], noise_dropped=exported["n_noise"],
        )
        local_metrics["llm_batches"] = 0
        local_metrics["llm_messages"] = 0
        local_metrics["total_tokens"] = 0
        local_metrics["desktop_elapsed_s"] = 0.0

        report: dict = {
            "dataset": {
                "path": args.ood.relative_to(ROOT).as_posix(),
                "total": len(rows),
                "action_todo_exam": sum(1 for r in rows if r["label"] == "action" and not r["jielong"]),
                "jielong": sum(1 for r in rows if r["jielong"]),
                "negatives": sum(1 for r in rows if r["label"] != "action"),
                "java_escalations": exported["n_escalations"],
                "batch_size": exported["batch_size"],
            },
            "escalations": exported["escalations"],
            "baselines": {
                "keyword_only_python": eval_keyword_ood(rows),
                "stage2_lr_only_python": eval_python_ood(rows, model_py, use_rules=False),
                "apk_java_local_only": local_metrics,
            },
            "gbnf_ablation": {},
            "models": {},
        }

        def apply_and_score_hybrid(resp_path: Path, tag: str, run_res: dict) -> tuple[dict, dict]:
            apply_out_path = args.cache_dir / f"apply_{tag}.json"
            run_java_bridge(
                cp, "apply",
                str(ROOT / "android/app/assets/model.tsv"),
                str(args.ood),
                str(resp_path),
                str(apply_out_path),
            )
            apply_out = json.loads(apply_out_path.read_text(encoding="utf-8"))
            return apply_out, score_hybrid(rows, apply_out, run_res)

        # 2. 端侧 1.5B 模型评测（含 GBNF 消融 + 完整端侧闭环 + 100 条直抽）
        print(f"\n2. 启动 llama-server 加载端侧模型：{args.small_name} ...", flush=True)
        with LlamaServer(args.llama_server, args.small_gguf, ngl=args.ngl, disable_thinking=False) as srv:
            print(f"   就绪（模型加载 {srv.load_seconds}s）", flush=True)
            report["models"]["small"] = {
                "name": args.small_name,
                "size_mb": round(args.small_gguf.stat().st_size / (1024 * 1024), 1),
                "server_load_s": srv.load_seconds,
            }
            p_h_gbnf = args.cache_dir / "small_hybrid_gbnf.json"
            r_h_gbnf = run_batches(srv.url, exported["hybrid_batches"], True, False, "1.5B hybrid + GBNF", p_h_gbnf)
            a_h_gbnf, m_h_gbnf = apply_and_score_hybrid(p_h_gbnf, "small_hybrid_gbnf", r_h_gbnf)
            report["baselines"]["apk_java_plus_1.5b_gbnf"] = m_h_gbnf

            p_h_nogbnf = args.cache_dir / "small_hybrid_nogbnf.json"
            r_h_nogbnf = run_batches(srv.url, exported["hybrid_batches"], False, False, "1.5B hybrid NO GBNF", p_h_nogbnf)
            a_h_nogbnf, m_h_nogbnf = apply_and_score_hybrid(p_h_nogbnf, "small_hybrid_nogbnf", r_h_nogbnf)
            report["gbnf_ablation"]["small_1.5b_without_gbnf"] = summarize_ablation(a_h_nogbnf, r_h_nogbnf)
            report["gbnf_ablation"]["small_1.5b_with_gbnf"] = summarize_ablation(a_h_gbnf, r_h_gbnf)
            report["baselines"]["apk_java_plus_1.5b_no_gbnf"] = m_h_nogbnf

            p_d_gbnf = args.cache_dir / "small_direct_gbnf.json"
            r_d_gbnf = run_batches(srv.url, exported["direct_batches"], True, False, "1.5B direct 100 + GBNF", p_d_gbnf)
            report["baselines"]["direct_1.5b_gbnf"] = score_direct_llm(rows, exported["direct_batches"], r_d_gbnf)

        # 3. 可选参考大模型（如 Qwen3-8B）评测
        if args.large_gguf and args.large_gguf.is_file():
            print(f"\n3. 启动 llama-server 加载参考大模型：{args.large_name} (ngl={args.large_ngl}, ctx={args.large_ctx}) ...", flush=True)
            with LlamaServer(args.llama_server, args.large_gguf, ngl=args.large_ngl, ctx=args.large_ctx,
                             disable_thinking=args.large_no_think) as srv:
                print(f"   就绪（模型加载 {srv.load_seconds}s）", flush=True)
                report["models"]["large"] = {
                    "name": args.large_name,
                    "size_mb": round(args.large_gguf.stat().st_size / (1024 * 1024), 1),
                    "server_load_s": srv.load_seconds,
                    "no_think": args.large_no_think,
                }
                p_lh_gbnf = args.cache_dir / "large_hybrid_gbnf.json"
                r_lh_gbnf = run_batches(
                    srv.url, exported["hybrid_batches"], True, args.large_no_think, "8B hybrid + GBNF", p_lh_gbnf
                )
                a_lh_gbnf, m_lh_gbnf = apply_and_score_hybrid(p_lh_gbnf, "large_hybrid_gbnf", r_lh_gbnf)
                report["baselines"]["apk_java_plus_8b_gbnf"] = m_lh_gbnf

                p_lh_nogbnf = args.cache_dir / "large_hybrid_nogbnf.json"
                r_lh_nogbnf = run_batches(
                    srv.url, exported["hybrid_batches"], False, args.large_no_think, "8B hybrid NO GBNF", p_lh_nogbnf
                )
                a_lh_nogbnf, _ = apply_and_score_hybrid(p_lh_nogbnf, "large_hybrid_nogbnf", r_lh_nogbnf)
                report["gbnf_ablation"]["large_8b_without_gbnf"] = summarize_ablation(a_lh_nogbnf, r_lh_nogbnf)
                report["gbnf_ablation"]["large_8b_with_gbnf"] = summarize_ablation(a_lh_gbnf, r_lh_gbnf)

                p_ld_gbnf = args.cache_dir / "large_direct_gbnf.json"
                r_ld_gbnf = run_batches(
                    srv.url, exported["direct_batches"], True, args.large_no_think, "8B direct 100 + GBNF", p_ld_gbnf
                )
                report["baselines"]["direct_8b_gbnf"] = score_direct_llm(rows, exported["direct_batches"], r_ld_gbnf)

    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # 打印简洁汇总表
    print("\n=== 同集对照总表（100 条 strict_ood.jsonl）===")
    header = f"{'方案':<28} {'召回':>7} {'精确':>7} {'F1':>7} {'时间准确':>8} {'含糊克制':>8} {'接龙召回':>8} {'LLM调用':>8} {'总Token':>9}"
    print(header)
    print("-" * len(header))
    for name, m in report["baselines"].items():
        llm_n = m.get("llm_messages", m.get("escalated_to_llm", 0))
        if name == "apk_java_local_only":
            llm_n = 0
        tok = m.get("total_tokens", 0)
        print(
            f"{name:<28} {m['recall']:>6.1%} {m['precision']:>6.1%} {m['f1']:>6.1%} "
            f"{m['time_acc']:>7.1%} {m['vague_restraint_acc']:>7.1%} {m['jielong_recall']:>7.1%} "
            f"{llm_n:>7d}条 {tok:>8d}"
        )
    print("\n=== GBNF 语法约束消融（真实 19 条升级消息 / 7 批）===")
    for k, a in report["gbnf_ablation"].items():
        print(
            f"  {k:<26} JSON合法={a['json_valid_batches']}/{a['batches']} ({a['json_valid_rate']:.1%}) | "
            f"有效条目={a['parsed_verdicts']}/{a['escalations']} ({a['valid_verdict_rate']:.1%}) | "
            f"回写={a['applied_verdicts']} | {a['desktop_elapsed_s']}s"
        )
    print(f"\n完整 JSON 报告已写入：{args.out}")


if __name__ == "__main__":
    main()
