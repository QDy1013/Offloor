"""对外行为：模糊更正不猜卡片；唯一同源作业允许泛称。与 Step45Test 对齐。"""
from datetime import datetime, timedelta
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from engine.pipeline import Model, Pipeline

MODEL = Model(ROOT / "engine/model.json")
NOW = datetime(2026, 11, 9, 10)
sequence = 0


def send(p, text, sender="王老师", group="daily-group", hours=0):
    global sequence
    sequence += 1
    p.process({"msg_id": str(sequence), "group_id": group, "group_name": "大学物理课程群",
               "sender": sender, "text": text, "ts": NOW + timedelta(hours=hours)})


p = Pipeline(MODEL)
send(p, "周五18点前交数据库实验报告到学习通")
send(p, "周六18点前交物理实验报告到学习通")
assert len(p.active_cards()) == 2
before = [c.deadline for c in p.active_cards()]
send(p, "实验报告延期到下周一22点", hours=1)
assert [c.deadline for c in p.active_cards()] == before, "相似报告不能猜着改期"
send(p, "数据库实验报告延期到下周一22点", hours=2)
assert p.active_cards()[0].updated and p.active_cards()[1].deadline == before[1]

p = Pipeline(MODEL)
send(p, "周五18点前交实验报告，交到学习通。")
send(p, "更正一下，作业截止改到下周一23:59。", hours=1)
assert p.active_cards()[0].deadline == datetime(2026, 11, 16, 23, 59)

p = Pipeline(MODEL)
send(p, "周五18点前交实验报告到学习通")
send(p, "周六18点前交读书报告到邮箱")
send(p, "更正一下，作业截止改到下周一23:59。", hours=1)
assert len(p.active_cards()) == 2 and all(not c.updated for c in p.active_cards())

p = Pipeline(MODEL)
send(p, "周五18点前交实验报告到学习通")
send(p, "更正一下，作业截止改到下周一23:59。", sender="张老师", hours=1)
assert not p.active_cards()[0].updated, "泛称不能更改另一位老师的作业"
send(p, "更正一下，作业截止改到下周一23:59。", group="other-group", hours=1)
assert not p.active_cards()[0].updated, "更正不能串群"
print("PASS 7 组真实使用前的更正边界")
