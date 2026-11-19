# -*- coding: utf-8 -*-
"""接龙检测（方案书 7.2）

微信接龙有固定形状：`#接龙` + 标题 + 编号名单。这让"我接没接"变成可判断的事实，
而不是靠语义猜。名单里出现我的姓名/别名/学号 → 已接；否则提醒。
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from .timeparse import parse_jielong_deadline
from datetime import datetime

# 点号后紧跟一两位数字时按月日处理，例如“3.23 22:00 前报名”。
RE_NUM_LINE = re.compile(
    r"^[ \t]*(\d{1,3})[ \t]*(?:\.(?!\d{1,2}(?!\d))|[、)])[ \t]*(\S[^\r\n]*)$",
    re.M,
)
RE_TITLE = re.compile(r"[（(]([^）)]*)[）)]")
ME_NAMES = ("李承宇", "承宇", "小李", "李同学")
ME_SID = "20230137"


@dataclass
class Jielong:
    is_jielong: bool
    title: str | None = None
    roster: list[str] | None = None
    joined: bool | None = None
    deadline: datetime | None = None
    deadline_note: str = ""
    note: str = ""


_HEADER_TASK = ("作业", "任务", "清单", "注意事项", "要求", "步骤", "题目", "习题")
_ITEM_TASK = (
    "完成", "提交", "预习", "复习", "阅读", "下载", "安装", "整理", "准备",
    "撰写", "打印", "背诵", "上传", "作业", "报告", "习题", "实验", "试题",
    "题目", "论文", "代码", "笔记", "章节", "步骤", "要求", "注意", "截止",
)


def is_jielong(text: str) -> bool:
    stripped = text.strip()
    if stripped.startswith("#接龙"):
        return True
    if "#接龙" in stripped[:20]:
        return True
    matches = list(RE_NUM_LINE.finditer(stripped))
    if "接龙" in stripped and matches:
        return True
    if len(matches) >= 3:
        idx = [int(m.group(1)) for m in matches]
        if idx == list(range(1, len(idx) + 1)):   # 连续编号
            header = stripped[:matches[0].start()].strip()
            if header and any(w in header for w in _HEADER_TASK):
                return False
            for m in matches:
                body = m.group(2).strip()
                if len(body) > 24 or any(w in body for w in _ITEM_TASK):
                    return False
                first = re.split(r"[\s（(：:]", body)[0]
                if not first or len(first) > 12:
                    return False
            return True
    return False


def parse(text: str, send: datetime) -> Jielong:
    if not is_jielong(text):
        return Jielong(False)

    lines = [l for l in text.splitlines() if l.strip()]
    title = None
    for line in lines:
        if line.strip().startswith("#接龙"):
            # "#接龙 周末聚餐，周五18点前报名"：标题写在同一行时，取标签后面第一个子句
            rest = re.split(r"[，,。；;（(]", line.strip()[len("#接龙"):].strip(" ：:"))[0].strip()
            if rest:
                title = rest
                break
            continue
        m = RE_TITLE.search(line)
        title = line[:m.start()].strip() if m else line.strip()
        break
    if not title:
        title = "未命名接龙"

    roster: list[str] = []
    for num, body in RE_NUM_LINE.findall(text):
        name = re.split(r"[\s（(：:]", body.strip())[0]
        if name:
            roster.append(name)

    joined = any(n in roster for n in ME_NAMES) or any(ME_SID in r for r in roster)
    tp = parse_jielong_deadline(text, send)
    return Jielong(
        is_jielong=True,
        title=title,
        roster=roster,
        joined=joined,
        deadline=tp.value,
        deadline_note=tp.note,
        note=f"{len(roster)} 人已接" + ("，含你" if joined else "，你没有"),
    )


def reminder_card(j: Jielong, sender: str) -> dict | None:
    """你没接，才生成提醒卡片。"""
    if not j.is_jielong or j.joined:
        return None
    return {
        "kind": "jielong",
        "title": j.title,
        "summary": f"{sender} 发起的《{j.title}》已有 {len(j.roster or [])} 人参加，你还没接。",
        "deadline": j.deadline.isoformat(timespec="seconds") if j.deadline else None,
        "needs_confirm": j.deadline is None,
    }
