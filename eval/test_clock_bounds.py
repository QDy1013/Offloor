"""显式非法钟点保持待确认，避免归一化成其他时刻。"""
from datetime import datetime
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from engine.timeparse import parse_deadline


def main():
    start = datetime(2026, 11, 4, 10)
    for text in ("明天09:99考试", "明天25:00考试", "明天18点90分考试"):
        assert parse_deadline(text, start).value is None, text
    assert parse_deadline("明天上午9:00考试", start).value == datetime(2026, 11, 5, 9)
    for text in ("2月30日18点前交报告", "13月10日18点前交报告", "本月31日18点前交报告"):
        assert parse_deadline(text,start).value is None,text
    assert parse_deadline("两小时内交报告",start).value == datetime(2026,11,4,12)
    print("✓ 非法钟点和正常钟点全部通过")


if __name__ == "__main__":
    main()
