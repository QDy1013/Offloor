# -*- coding: utf-8 -*-
"""中文时间归一化（方案书 6.4「时间归一化」）

核心原则：规则优先，解析不了就返回 None（产品上标「待确认」），绝不猜。
所有结果都相对于「消息发出的时刻」计算，因为"明晚 8 点"离开发送时刻就没有意义。
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta

CN_DIGIT = {"零": 0, "〇": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4,
            "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}

WEEK_MAP = {"一": 0, "二": 1, "三": 2, "四": 3, "五": 4, "六": 5, "日": 6, "天": 6, "七": 6}

REL_DAYS = [
    ("大后天", 3), ("后天", 2), ("明天", 1), ("明日", 1), ("明晚", 1), ("明早", 1),
    ("今天", 0), ("今日", 0), ("今晚", 0), ("今早", 0), ("当天", 0),
]

# 时段词 → 是否需要 +12 小时
PM_WORDS = ("下午", "傍晚", "晚上", "晚间", "夜里", "晚")
AM_WORDS = ("上午", "早上", "早晨", "凌晨", "半夜", "一大早", "明早", "今早", "早")
NOON_WORDS = ("中午", "正午")

VAGUE = ("尽快", "近期", "这两天", "最近", "过几天", "有空", "随时", "抓紧", "尽早",
         "改天", "之后", "等通知", "另行通知")

RE_CLOCK = re.compile(r"(\d{1,2}|[零〇一二两三四五六七八九十]{1,3})\s*[:：]\s*(\d{2})")
# 钟点后可跟"半"（=30 分）或"X分"，分钟必须紧贴钟点，避免把后面的数字当分钟
RE_HOUR = re.compile(
    r"(\d{1,2}|[零〇一二两三四五六七八九十]{1,3})\s*[点时]"
    r"(?:\s*(半)|\s*(\d{1,2}|[零〇一二两三四五六七八九十]{1,2})\s*分)?"
)
# 月日认 "1月10日" / "11 月 20 日" / "12/31" / "2026-1-10" / "9.14" 这类写法；
# "9.14"（月.日）默认算日期，只有紧挨在"习题/练习/第/例"之后，或紧跟着
# 页/题/章/节/小节时才是编号（"习题 3.1"、"第 3.2 节"）；"2-3页" 也不算日期
RE_ABS_CN = re.compile(r"(?:(\d{4})\s*年\s*)?(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]")
RE_ABS_NUM = re.compile(
    r"(?:(\d{4})\s*[/\-]\s*)?(?<!\d)(\d{1,2})(?:\s*[/\-]\s*|\.)(\d{1,2})(?!\s*[页题章节张个分秒周])"
)
RE_WEEK = re.compile(r"(下下|下|本|这|上)?\s*(?:周|星期|礼拜)\s*(?:周|星期|礼拜)?\s*([一二三四五六日天七])")
RE_WEEKEND = re.compile(r"(这|本|下)?\s*周末")
RE_MONTH_END = re.compile(r"(这|本|下)?\s*个?\s*月底")
# "中午前/中午之前"后面没有具体几点时，按当天 12:00 处理（"上午前"等不动）
RE_NOON_BEFORE = re.compile(r"中午\s*(?:之前|前)")
RE_DAY_ONLY = re.compile(r"(这个月|本月|当月)?\s*(\d{1,2})\s*[日号]")

DEFAULT_CLASS_HOUR = 8  # "上课前"且没给具体时刻时，按早八算


def _cn2int(s: str) -> int | None:
    if s.isdigit():
        return int(s)
    if s in CN_DIGIT:
        return CN_DIGIT[s]
    if len(s) == 2 and s[0] == "十":
        return 10 + CN_DIGIT.get(s[1], 0)
    if len(s) == 2 and s[1] == "十":
        return CN_DIGIT.get(s[0], 0) * 10
    if len(s) == 3 and s[1] == "十":
        return CN_DIGIT.get(s[0], 0) * 10 + CN_DIGIT.get(s[2], 0)
    return None


@dataclass
class TimeParse:
    value: datetime | None
    has_time: bool          # 是否解析出了具体时刻（否则只有日期）
    confidence: str         # high / medium / low / none
    raw: str = ""
    note: str = ""
    expired: bool = False

    @property
    def ok(self) -> bool:
        return self.value is not None and self.confidence in ("high", "medium")


def _period_adjust(hh: int, period: str | None) -> int:
    if period is None:
        return hh
    if period in PM_WORDS:
        return hh + 12 if hh < 12 else hh
    if period in NOON_WORDS:
        return 12 if hh < 11 else hh
    return hh


def _find_period(text: str, pos: int | None = None) -> str | None:
    """取钟点前面最近的时段词（pos 为钟点在文中的起点；None 时看全文）。

    只看钟点之前，避免"明早 8 点交，晚了不收"被后面的"晚了"带偏。
    """
    end = len(text) if pos is None else max(0, pos)
    best, hit = -1, None
    for group in (PM_WORDS, AM_WORDS, NOON_WORDS):
        for w in group:
            i = text.rfind(w, 0, end)
            if i < 0 or i + len(w) > end:
                continue
            # 起点更靠后胜出；起点相同时取更长的词（"早上" 优于 "早"）
            if i > best or (i == best and hit is not None and len(w) > len(hit)):
                best, hit = i, w
    return hit


def _resolve_weekday(send: datetime, wd: int, mode: str | None) -> tuple[date, str]:
    """把「周X」解析成具体日期。返回 (date, note)

    mode: 下下 / 下 / 本 / 这 / None
    """
    today = send.date()
    cur = today.weekday()
    if mode == "下下":
        monday = today - timedelta(days=cur) + timedelta(days=14)
        return monday + timedelta(days=wd), "下下周"
    if mode == "下":
        monday = today - timedelta(days=cur) + timedelta(days=7)
        return monday + timedelta(days=wd), "下周"
    if mode in ("本", "这"):
        monday = today - timedelta(days=cur)
        return monday + timedelta(days=wd), "本周"
    # 无前缀：取最近的将来那一次；若就是今天则今天
    if wd >= cur:
        return today + timedelta(days=wd - cur), "本周"
    return today + timedelta(days=7 - cur + wd), "下周"


def parse_deadline(text: str, send: datetime) -> TimeParse:
    """从一条消息里解析截止时间。"""
    if not text:
        return TimeParse(None, False, "none")

    t = text.replace("：", ":")
    # 只在冒号两侧是数字时保留，避免把"通知："误当时间
    for bad in ("尽快", "通知", "备注", "提示"):
        t = t.replace(bad + ":", bad + " ")

    # 1) 明确不可解析
    for w in VAGUE:
        if w in t:
            return TimeParse(None, False, "none", raw=w, note="模糊表达，标待确认")

    # 2) 抓时刻：先定位钟点，再只取钟点之前的时段词（避免"晚了不收"抢走"明早"）
    hh = mm = None
    has_time = False
    rollover = False          # "24点/24:00" = 次日 0 点，日期要 +1 天
    colon = re.search(r"(\d{1,2})\s*:\s*(\d{2})", t)
    mh = RE_HOUR.search(t)
    clock = colon or mh
    period = _find_period(t, clock.start()) if clock else None

    if colon:
        hh, mm = int(colon.group(1)), int(colon.group(2))
        if hh == 24 and mm == 0:
            hh, mm, rollover = 0, 0, True
        elif hh > 23 or mm > 59:
            return TimeParse(None, False, "none", note="非法时刻")
        else:
            hh = _period_adjust(hh, period)
        has_time = True
    elif mh:
        v = _cn2int(mh.group(1))
        if v is None or not 0 <= v <= 24:
            return TimeParse(None, False, "none", note="非法时刻")
        if mh.group(2):
            mins = 30
        elif mh.group(3):
            mins = _cn2int(mh.group(3))
        else:
            mins = 0
        if mins is None or not 0 <= mins <= 59:
            return TimeParse(None, False, "none", note="非法时刻")
        if v == 24:
            if mins != 0:
                return TimeParse(None, False, "none", note="非法时刻")
            hh, mm, rollover = 0, 0, True
        else:
            hh, mm = v, mins
            hh = _period_adjust(hh, period)
        has_time = True

    # 2.5) "中午前/中午之前"没有具体钟点 → 当天 12:00
    if not has_time and RE_NOON_BEFORE.search(t):
        hh, mm, has_time = 12, 0, True

    # 3) 抓日期：绝对日期 > 相对日 > 星期 > 模糊锚点
    base: date | None = None
    note = ""
    date_conf = None

    # 绝对日期：中文写法与数字写法都试，取文中靠前的那个
    abs_matches = [x for x in (RE_ABS_CN.search(t), RE_ABS_NUM.search(t)) if x]
    abs_matches.sort(key=lambda x: x.start())
    for m in abs_matches:
        mo, dd = int(m.group(2)), int(m.group(3))
        # 紧跟在"第/页/题/章/节"后面的数字是编号，不是日期
        if t[max(0, m.start() - 1):m.start()] in ("第", "页", "题", "章", "节"):
            continue
        # "M.D" 点号写法：只有紧挨着"习题/练习/第/例"（中间可有空格）或紧跟着
        # "页/题/章/节/小节"时才算编号，否则按"月.日"处理
        if "." in t[m.end(2):m.start(3)]:
            if t[:m.start()].rstrip().endswith(("习题", "练习", "第", "例")):
                continue
            if t[m.end():].lstrip().startswith(("小节", "页", "题", "章", "节")):
                continue
        year = int(m.group(1)) if m.group(1) else send.year
        try:
            base = date(year, mo, dd)
            if base < send.date() - timedelta(days=180) and not m.group(1):
                base = date(year + 1, mo, dd)   # 只写月日且已过去很久 → 次年
        except ValueError:
            return TimeParse(None, False, "none", note="非法日期")
        date_conf = "high"
        note = "绝对日期"
        break

    if base is None:
        m = RE_DAY_ONLY.search(t)
        if m:
            dd = int(m.group(2))
            if 1 <= dd <= 31:
                try:
                    base = date(send.year, send.month, dd)
                    if base < send.date() and not m.group(1):
                        month = send.month + 1
                        year = send.year + (1 if month > 12 else 0)
                        base = date(year, (month - 1) % 12 + 1, dd)
                    date_conf = "high"
                    note = "本月几号"
                except ValueError:
                    return TimeParse(None, False, "none", note="非法日期")

    if base is None:
        for w, delta in REL_DAYS:
            if w in t:
                base = send.date() + timedelta(days=delta)
                date_conf = "high"
                note = w
                break

    if base is None:
        m = RE_WEEK.search(t)
        if m:
            wd = WEEK_MAP.get(m.group(2))
            if wd is not None:
                base, note = _resolve_weekday(send, wd, m.group(1))
                date_conf = "high"

    if base is None:
        m = RE_WEEKEND.search(t)
        if m:
            prefix = m.group(1)
            cur = send.date().weekday()
            if prefix == "下":
                base = send.date() + timedelta(days=(6 - cur) + 7)
            else:
                base = send.date() + timedelta(days=(6 - cur))
            date_conf = "medium"
            note = "周末（按周日 23:59 处理）"

    if base is None:
        m = RE_MONTH_END.search(t)
        if m:
            d = send.date()
            nxt = (d.replace(day=28) + timedelta(days=4)).replace(day=1)
            base = nxt - timedelta(days=1)
            date_conf = "medium"
            note = "月底"

    # "24点"是当天结束/次日 0 点：日期整体后移一天
    if base is not None and rollover:
        base += timedelta(days=1)

    # 4) "上课前"且无时刻 → 早八
    if base is not None and not has_time and "上课前" in t:
        hh, mm, has_time = DEFAULT_CLASS_HOUR, 0, True
        note += " · 上课前按早八"

    if base is None and not has_time:
        durations = list(re.finditer(r"(?<![\d零一二两三四五六七八九十])(\d{1,3}|[一二两三四五六七八九十]{1,3}|半)\s*(小时|分钟)\s*(?:之内|内|后截止|后提交|后交)", t))
        if len(durations) == 1:
            match = durations[0]
            amount = .5 if match[1] == "半" else _cn2int(match[1])
            if amount is not None and 0 < amount <= 168:
                duration = timedelta(hours=amount) if match[2] == "小时" else timedelta(minutes=amount)
                return TimeParse(send + duration, True, "high", note="按发送时刻和明确时限计算")
        return TimeParse(None, False, "none", note="无时间表达")

    if base is None:
        if rollover:
            # "24点"只有时刻：直接按次日 0 点，不再做"已过就顺延"的判断
            return TimeParse(datetime.combine(send.date() + timedelta(days=1), time(0, 0)),
                             True, "medium", note="24点按次日0点")
        # 只有时刻没有日期：今天该时刻未过就是今天，否则明天（置信度 medium）
        cand = datetime.combine(send.date(), time(hh, mm))
        if cand <= send:
            cand += timedelta(days=1)
            note = "只有时刻，按次日"
        else:
            note = "只有时刻，按当天"
        return TimeParse(cand, True, "medium", note=note)

    if not has_time:
        cand = datetime.combine(base, time(23, 59))
        expired = cand < send
        if "下课前" in t or "放学前" in t:
            return TimeParse(cand, False, "low", note="下课或放学前，具体时刻待确认", expired=expired)
        return TimeParse(cand, False, "medium" if date_conf != "high" else "high",
                         note=(note + " · 无具体时刻，按当日 23:59").strip(" ·"),
                         expired=expired)

    cand = datetime.combine(base, time(hh, mm))
    expired = cand < send
    conf = "high" if date_conf == "high" else "medium"
    if expired:
        conf = "low"
        note += " · 已过期"
    return TimeParse(cand, True, conf, note=note, expired=expired)


def parse_jielong_deadline(text: str, send: datetime) -> TimeParse:
    """接龙里可能同时出现「截止」和「回复时间」两个时间，优先取「截止」所在片段。"""
    if "截止" in text:
        i = text.index("截止")
        seg = text[max(0, i - 30): i + 14]
        r = parse_deadline(seg, send)
        if r.value is not None:
            r.note = (r.note + " · 取接龙截止").strip(" ·")
            return r
    return parse_deadline(text, send)
