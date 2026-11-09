# -*- coding: utf-8 -*-
"""「offloor」合成群聊数据生成器

对应方案书 8.1「合成训练数据」：
  群和角色 / 剧本内容 / 刻意加入难例 / 标签 / 规模目标

产物：JSONL，每条消息一行。字段见 README。
"""
from __future__ import annotations

import argparse
import json
import random
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from pathlib import Path

WEEKDAYS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]

# ---------------------------------------------------------------- 世界设定

ME = {
    "name": "李承宇",
    "aliases": ["承宇", "小李", "李同学"],
    "sid": "20230137",
    "klass": "计科 2301",
    "group_no": "第3组",
    "electives": ["数据库系统", "计算机网络"],
}

COURSES = [
    ("数据库系统", "王老师", "三教 302", 2),      # 周二
    ("计算机网络", "刘老师", "实验楼 B203", 3),    # 周三
    ("操作系统", "赵老师", "三教 405", 1),         # 周一
    ("数据结构", "孙老师", "三教 301", 4),         # 周四
    ("软件工程", "周老师", "三教 208", 5),         # 周五
    ("概率论",     "吴老师", "二教 110", 3),       # 周三
]

CHANNELS = ["学习通", "雨课堂", "课程网站", "QQ 群文件", "邮箱", "教务系统", "课代表处"]
PLACES = ["三教 302", "实验楼 B203", "图书馆报告厅", "体育馆", "线上腾讯会议", "二教 110"]

SURNAMES = "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜"

ACTION_TASKS = [
    ("交实验报告", "学习通"),
    ("提交课程设计初稿", "课程网站"),
    ("完成课后习题第 3 章", "学习通"),
    ("交小组展示 PPT", "QQ 群文件"),
    ("交读书报告", "邮箱"),
    ("填报选课意向表", "教务系统"),
    ("提交项目中期汇报", "课程网站"),
]

NOTICE_TASKS = [
    ("下次课改到 {place}", None),
    ("本周实验课停上一次", None),
    ("期末考试安排已发布", None),
    ("调课通知：{weekday} 的课挪到 {place}", None),
]

DISCUSS = [
    "第3组下周展示是不是要提前准备",
    "王老师那个 PPT 模板谁还有",
    "我看了下第三章的题有点难",
    "数据库那个大作业你们打算做什么题目",
    "@{me} 你上次说的那个方案还能用吗",
    "咱们小组要不周三晚上对一下",
    "这门课给分怎么样啊",
    "有人知道实验报告要写多少字吗",
]

CHATTER = [
    "哈哈哈", "笑死", "yyds", "收到", "1", "+1", "好的", "嗯嗯", "？",
    "有人去食堂吗", "我先去趟快递", "好家伙", "绝了", "蹲一个", "在的",
    "刚睡醒", "今晚有人打球吗", "这也太真实了", "救命",
]

EMOJI = ["😂", "😅", "🤣", "👍", "🙏", "😭", "[表情]", "[图片]", "[文件]"]

ACKS = ["收到", "好的", "1", "+1", "收到收到", "好嘞", "OK", "ok", "明白"]

# ---------------------------------------------------------------- 时间表达

def _at(d: date, hh: int, mm: int = 0) -> datetime:
    return datetime.combine(d, time(hh, mm))


def build_deadline(send: datetime, rng: random.Random) -> dict:
    """构造一条消息里的截止时间表达 + 它的黄金标准绝对时间。

    resolvable=False 表示人类也说不准（"尽快"），正确系统的行为是标「待确认」，
    而不是猜一个时间。
    """
    sd = send.date()
    kind = rng.choices(
        ["tomorrow_evening", "tonight", "weekday", "abs_md", "dot_md",
         "next_week", "weekend", "month_end", "hour_only", "vague"],
        weights=[16, 8, 20, 12, 8, 10, 6, 4, 6, 10],
    )[0]

    if kind == "tomorrow_evening":
        hh = rng.choice([18, 20, 22])
        expr = f"明晚 {hh - 12} 点前"
        gold = _at(sd + timedelta(days=1), hh)
    elif kind == "tonight":
        hh = 22
        expr = "今晚 22 点前"
        gold = _at(sd, hh)
        if gold <= send:
            expr = "明晚 10 点前"
            gold = _at(sd + timedelta(days=1), hh)
    elif kind == "weekday":
        target_wd = rng.randint(0, 6)
        cur = sd.weekday()
        hh = rng.choice([12, 18, 20])
        if target_wd > cur:                     # 本自然周内还没到
            delta = target_wd - cur
            head = rng.choice([WEEKDAYS[target_wd],
                               f"本周{WEEKDAYS[target_wd][1:]}",
                               f"这周{WEEKDAYS[target_wd][1:]}"])
        elif target_wd == cur:                  # 就是今天
            delta = 0
            head = "今天"
        else:                                   # 本自然周已过 → 只能指下周
            delta = 7 - cur + target_wd
            head = f"下{WEEKDAYS[target_wd]}"
        gold = _at(sd + timedelta(days=delta), hh)
        if gold <= send:
            delta += 7
            gold = _at(sd + timedelta(days=delta), hh)
            head = f"下{WEEKDAYS[target_wd]}"
        clock = rng.choice([f"{hh} 点前", f"{hh}:00 前",
                            f"下午 {hh - 12} 点前" if hh >= 12 else f"{hh} 点前"])
        expr = f"{head}{clock}"
    elif kind == "abs_md":
        d = sd + timedelta(days=rng.randint(1, 20))
        hh, mm = rng.choice([(18, 0), (12, 0), (23, 59)])
        gold = _at(d, hh, mm)
        expr = f"{d.month} 月 {d.day} 日 {hh:02d}:{mm:02d} 前"
    elif kind == "dot_md":
        d = sd + timedelta(days=rng.randint(1, 20))
        hh = rng.choice([12, 18, 22])
        gold = _at(d, hh)
        expr = f"{d.month}.{d.day} {hh}:00 前"
    elif kind == "next_week":
        target_wd = rng.randint(0, 4)
        monday = sd + timedelta(days=(7 - sd.weekday()))
        gold = _at(monday + timedelta(days=target_wd), 8)
        expr = rng.choice([f"下{WEEKDAYS[target_wd]}上课前", f"下周{WEEKDAYS[target_wd]}上课前"])
    elif kind == "weekend":
        delta = (6 - sd.weekday()) % 7
        gold = _at(sd + timedelta(days=delta), 23, 59)
        if gold <= send:
            gold += timedelta(days=7)
        expr = rng.choice(["周末前", "这周末前"])
    elif kind == "month_end":
        nxt = (sd.replace(day=28) + timedelta(days=4)).replace(day=1)
        gold = _at(nxt - timedelta(days=1), 23, 59)
        expr = rng.choice(["月底前", "这个月底前"])
    elif kind == "hour_only":
        hh = rng.choice([12, 18, 21])
        gold = _at(sd, hh)
        if gold <= send:
            gold = _at(sd + timedelta(days=1), hh)
            expr = f"明天 {hh} 点前"
        else:
            expr = rng.choice([f"{hh} 点前", f"今天 {hh} 点前"])
    else:  # vague
        expr = rng.choice(["尽快", "这两天", "有空的时候", "近期"])
        return {"expr": expr, "gold": None, "resolvable": False}

    if gold <= send:
        gold = send + timedelta(hours=2)
    return {"expr": expr, "gold": gold, "resolvable": True}


def make_deadline_from_gold(gold: datetime, send: datetime, rng: random.Random) -> str:
    """给定绝对时间，反推一个自然表达（用于变更/撤销类难例，保证前后一致）。"""
    d = gold.date()
    hh, mm = gold.hour, gold.minute
    delta_days = (d - send.date()).days
    if delta_days == 1:
        return f"明晚 {hh - 12 if hh >= 12 else hh} 点" if mm == 0 else f"明天 {hh:02d}:{mm:02d}"
    if delta_days == 0:
        return f"今晚 {hh} 点" if mm == 0 else f"今天 {hh:02d}:{mm:02d}"
    if 2 <= delta_days <= 6:
        return f"周{WEEKDAYS[d.weekday()][1]}" + (f" {hh} 点" if mm == 0 else f" {hh:02d}:{mm:02d}")
    return f"{d.month} 月 {d.day} 日 {hh:02d}:{mm:02d}"


# ---------------------------------------------------------------- 数据模型

@dataclass
class Group:
    gid: str
    name: str
    gtype: str          # class / grade / course / club / project
    members: list[str]
    teacher: str | None
    monitor: str | None
    course: str | None = None


def build_world(rng: random.Random) -> dict:
    groups: list[Group] = []

    classmates = ["陈嘉禾", "林晓", "周若曦", "郑浩然", "孙语桐", "何思远", "马佳怡"]
    groups.append(Group("g_class", "计科 2301 班群", "class", classmates + [ME["name"]], "辅导员 张老师", "陈嘉禾"))
    groups.append(Group("g_grade", "23 级年级群", "grade", classmates[:4] + [ME["name"]], "辅导员 张老师", None))

    for i, (cname, teacher, place, _wd) in enumerate(COURSES):
        members = [teacher, "课代表 林晓"] + classmates + [ME["name"]]
        groups.append(Group(f"g_course_{i}", f"{cname} 2026秋", "course", members, teacher, None, cname))

    groups.append(Group("g_club", "摄影协会", "club", ["社长 韩雨轩"] + classmates[:5] + [ME["name"]], None, None))
    groups.append(Group("g_proj", "挑战杯·小组群", "project", ["陈嘉禾", "周若曦", ME["name"], "郑浩然"], None, "陈嘉禾"))
    return {g.gid: g for g in groups}


# ---------------------------------------------------------------- 生成器

class Synth:
    def __init__(self, seed: int = 7):
        self.rng = random.Random(seed)
        self.groups = build_world(self.rng)
        self.n = 0
        self.rows: list[dict] = []
        self.notice_memory: dict[str, str] = {}   # group -> 上一条通知 id（供变更/撤销）

    def _mid(self) -> str:
        self.n += 1
        return f"m{self.n:06d}"

    def _emit(self, g: Group, sender: str, role: str, ts: datetime, text: str,
              label: str, todo: dict | None = None, noise: bool = False,
              jielong: dict | None = None, supersedes: str | None = None,
              revokes: str | None = None, hard: str | None = None) -> dict:
        row = {
            "msg_id": self._mid(),
            "group_id": g.gid,
            "group_name": g.name,
            "group_type": g.gtype,
            "course": g.course,
            "sender": sender,
            "sender_role": role,
            "ts": ts.isoformat(timespec="seconds"),
            "text": text,
            "label": label,
            "todo": todo,
            "noise": noise,
            "jielong": jielong,
            "supersedes": supersedes,
            "revokes": revokes,
            "hard": hard,
        }
        self.rows.append(row)
        return row

    # -- 主线：一个通知事件 + 回复潮 + 闲聊淹没
    def event_todo(self, g: Group, ts: datetime) -> datetime:
        rng = self.rng
        teacher = g.teacher or "辅导员 张老师"
        role = "teacher" if g.teacher else "counselor"
        task, channel = rng.choice(ACTION_TASKS)
        dl = build_deadline(ts, rng)

        if g.gtype == "course":
            subject = g.course
        elif g.gtype == "class":
            task, channel = rng.choice([("报名体测", "班级接龙"), ("填选课意向表", "教务系统"), ("交班费", "微信转账")])
            subject = None
        elif g.gtype == "project":
            task, channel = rng.choice([("交小组展示 PPT", "QQ 群文件"), ("提交项目中期汇报", "课程网站")])
            subject = None
        else:
            task, channel = rng.choice([("报名摄影比赛", "邮件"), ("交作品原图", "邮箱")])
            subject = None

        action = (f"交{subject}实验报告" if ("实验报告" in task and subject) else task)
        # 句式必须多样，否则模型只会学到"…前…交到…"这一种模板
        tpl = rng.randint(0, 4)
        if tpl == 0:
            text = f"{dl['expr']}{task}，交到{channel}。"
        elif tpl == 1:
            text = f"交到{channel}，{dl['expr']}。{task}，别忘了。"
        elif tpl == 2:
            text = f"【通知】{task}：{dl['expr']}，{channel}提交。"
        elif tpl == 3:
            text = f"{task}啊，{dl['expr']}，在{channel}里交。"
        else:
            text = f"{dl['expr']}{task}（{channel}）。"

        if not dl["resolvable"]:
            todo = {"action": action, "deadline": None, "channel": channel, "verified": False}
            hard = "unresolvable_time"
        else:
            todo = {"action": action, "deadline": dl["gold"].isoformat(timespec="seconds"),
                    "channel": channel, "verified": True}
            hard = None

        row = self._emit(g, teacher, role, ts, text, "action", todo, hard=hard)
        self.notice_memory[g.gid] = row["msg_id"]

        # 回复潮
        cur = ts
        burst = self.rng.randint(0, 26)
        for _ in range(burst):
            cur += timedelta(seconds=self.rng.randint(5, 40))
            who = self.rng.choice([m for m in g.members if m != teacher])
            self._emit(g, who, "student", cur, self.rng.choice(ACKS), "chatter", noise=True)
        # 夹杂闲聊，制造"通知被淹没"
        for _ in range(self.rng.randint(0, 12)):
            cur += timedelta(seconds=self.rng.randint(20, 120))
            who = self.rng.choice(g.members)
            self._emit(g, who, "student", cur, self.rng.choice(CHATTER + EMOJI), "chatter", noise=True)
        return cur

    # -- 通知类（②）：有时间有地点，但不需要你做具体动作
    def event_notice(self, g: Group, ts: datetime) -> datetime:
        rng = self.rng
        who = g.teacher or g.monitor or "辅导员 张老师"
        tpl, _ = rng.choice(NOTICE_TASKS)
        place = rng.choice(PLACES)
        wd = rng.choice(WEEKDAYS)
        text = "通知：" + tpl.format(place=place, weekday=wd) + "，请相互转告。"
        self._emit(g, who, "teacher", ts, text, "notice",
                   {"action": None, "deadline": None, "channel": None, "verified": False})
        cur = ts
        for _ in range(rng.randint(0, 8)):
            cur += timedelta(seconds=rng.randint(5, 60))
            self._emit(g, rng.choice(g.members), "student", cur, rng.choice(ACKS), "chatter", noise=True)
        return cur

    # -- 接龙
    def event_jielong(self, g: Group, ts: datetime) -> datetime:
        rng = self.rng
        title = rng.choice(["体测报名", "秋游报名", "班级聚餐统计", "志愿者报名", "期末答疑预约"])
        dl = build_deadline(ts, rng)
        before_me = rng.random() < 0.5
        pool = [m for m in g.members
                if not m.startswith(("辅导员", "课代表", "社长")) and m != ME["name"]]
        roster = rng.sample(pool, k=min(len(pool), rng.randint(3, 8)))
        if before_me:
            roster.insert(rng.randint(0, len(roster)), ME["name"])
        lines = ["#接龙", f"{title}（{dl['expr']}截止）"]
        for i, m in enumerate(roster, 1):
            lines.append(f"{i}. {m}")
        text = "\n".join(lines)
        payload = {
            "title": title,
            "deadline": dl["gold"].isoformat(timespec="seconds") if dl["resolvable"] else None,
            "roster": roster,
            "joined": before_me,
        }
        self._emit(g, g.monitor or g.teacher or "陈嘉禾", "student", ts, text, "action",
                   {"action": f"参加{title}", "deadline": payload["deadline"],
                    "channel": "群接龙", "verified": dl["resolvable"]},
                   jielong=payload, hard="jielong")
        cur = ts
        for _ in range(rng.randint(0, 6)):
            cur += timedelta(seconds=rng.randint(10, 90))
            self._emit(g, rng.choice(g.members), "student", cur, rng.choice(ACKS), "chatter", noise=True)
        return cur

    # -- 变更 / 撤销（难例）
    def event_change(self, g: Group, ts: datetime) -> datetime:
        rng = self.rng
        prev = self.notice_memory.get(g.gid)
        if not prev:
            return ts
        new_gold = ts + timedelta(days=rng.randint(1, 8))
        new_gold = _at(new_gold.date(), rng.choice([18, 20, 23]), 0)
        expr = make_deadline_from_gold(new_gold, ts, rng)
        if rng.random() < 0.65:
            text = f"更正一下，作业截止{expr}，之前说的那个时间不作数。"
            self._emit(g, g.teacher or g.monitor, "teacher", ts, text, "action",
                       {"action": None, "deadline": new_gold.isoformat(timespec="seconds"),
                        "channel": None, "verified": True},
                       supersedes=prev, hard="supersede")
        else:
            text = rng.choice(["上条通知作废，明天按新课表上课。", "刚才那条发错了，忽略。", "取消，改天再说。"])
            self._emit(g, g.teacher or g.monitor, "teacher", ts, text, "notice",
                       {"action": None, "deadline": None, "channel": None, "verified": False},
                       revokes=prev, hard="revoke")
        return ts + timedelta(minutes=2)

    # -- 讨论（③）
    def event_discuss(self, g: Group, ts: datetime) -> datetime:
        rng = self.rng
        cur = ts
        for _ in range(rng.randint(1, 5)):
            who = rng.choice(g.members)
            t = rng.choice(DISCUSS).replace("{me}", ME["name"])
            self._emit(g, who, "student", cur, t, "discussion")
            cur += timedelta(seconds=rng.randint(15, 300))
        return cur

    # -- 易混家族：让"关键词 + 模板"学不会，必须真的理解
    def event_confusable(self, g: Group, ts: datetime) -> datetime:
        rng = self.rng
        who = rng.choice([m for m in g.members if not m.startswith(("辅导员", "课代表", "社长"))])
        kind = rng.choices(["casual_todo", "discuss_action", "chatter_time", "notice_time"],
                           weights=[30, 30, 25, 15])[0]

        if kind == "casual_todo":
            dl = build_deadline(ts, rng)
            task, channel = rng.choice(ACTION_TASKS)
            text = rng.choice([
                f"记得{dl['expr']}交{task}哈，别忘了",
                f"{task}别忘了，{dl['expr']}。",
                f"{dl['expr']}把{task}弄了哈",
            ])
            todo = {"action": task, "channel": channel,
                    "deadline": dl["gold"].isoformat(timespec="seconds") if dl["gold"] else None,
                    "verified": dl["resolvable"]}
            self._emit(g, g.teacher or g.monitor or who, "teacher", ts, text, "action", todo, hard="casual")
        elif kind == "discuss_action":
            text = rng.choice(["我明天把实验报告交了，你们呢", "你们作业交了吗", "明天一起交吧",
                               "我昨天就交了", "这个我下周再交", "交完啦，可以躺了"])
            self._emit(g, who, "student", ts, text, "discussion", hard="confusable_neg")
        elif kind == "chatter_time":
            text = rng.choice(["明天一起吃饭？", "有人明天去图书馆吗", f"@{ME['name']} 你人呢",
                               "明天再说吧", "晚上打球不", "周五没课要不要出去"])
            self._emit(g, who, "student", ts, text, "chatter", hard="confusable_neg")
        else:
            text = rng.choice(["明天停课，不用来", "周五那节课改到 B203",
                               "下周考试范围：第 1-5 章", "这周实验课取消", "期末考试安排已出，注意查看"])
            self._emit(g, g.teacher or who, "teacher", ts, text, "notice",
                       {"action": None, "deadline": None, "channel": None, "verified": False},
                       hard="confusable_neg")
        return ts + timedelta(minutes=rng.randint(1, 5))

    def run(self, days: int = 60, start: date = date(2026, 9, 7)) -> list[dict]:
        rng = self.rng
        for day in range(days):
            d = start + timedelta(days=day)
            for g in self.groups.values():
                # 周末只有社团/项目群活跃
                if d.weekday() >= 5 and g.gtype in ("class", "grade", "course"):
                    if rng.random() < 0.85:
                        continue
                # 当天活跃时段
                base = _at(d, rng.choice([8, 10, 12, 14, 19, 20, 21]))
                ts = base + timedelta(minutes=rng.randint(0, 50))
                for _ in range(rng.randint(1, 3)):
                    r = rng.random()
                    if r < 0.34:
                        ts = self.event_todo(g, ts)
                    elif r < 0.48:
                        ts = self.event_notice(g, ts)
                    elif r < 0.60:
                        ts = self.event_jielong(g, ts)
                    elif r < 0.68:
                        ts = self.event_change(g, ts)
                    elif r < 0.84:
                        ts = self.event_confusable(g, ts)
                    else:
                        ts = self.event_discuss(g, ts)
                    ts += timedelta(minutes=rng.randint(3, 40))
        self.rows.sort(key=lambda r: (r["ts"], r["msg_id"]))
        return self.rows


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(Path(__file__).with_name("dataset.jsonl")))
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--days", type=int, default=60)
    args = ap.parse_args()

    rows = Synth(seed=args.seed).run(days=args.days)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    from collections import Counter
    c = Counter(r["label"] for r in rows)
    hard = Counter(r["hard"] for r in rows if r["hard"])
    jl = sum(1 for r in rows if r["jielong"])
    print(f"写入 {out}  共 {len(rows)} 条")
    print("四分类分布:", dict(c))
    print("难例:", dict(hard))
    print("接龙:", jl, " 其中我未参加:", sum(1 for r in rows if r["jielong"] and not r["jielong"]["joined"]))
    print("群数:", len({r['group_id'] for r in rows}), " 时间跨度:",
          rows[0]["ts"], "→", rows[-1]["ts"])


if __name__ == "__main__":
    main()
