# -*- coding: utf-8 -*-
"""三级流水线编排（方案书 6 / 7）

一条消息进来依次经过：
  第 0 步  噪声直接丢弃（但仍计入回复潮统计）
  第 1 步  规则粗筛打 8 维特征
  第 2 步  小分类模型判四类
  第 3 步  只对①（需要我行动）做结构化抽取，②出信息卡
  旁路     接龙检测、变更/撤销、个人档案相关性
输出是卡片，每张卡片都带原文和上下文。
"""
from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path

from . import jielong as jl
from .extract import Extracted, clean_title, extract, find_action, human_deadline, is_delivery_only, RE_SPLIT
from .features import MANUAL_KEYS, Stats, is_noise, vectorize
from .timeparse import parse_deadline

# 兜底标题取第一个标点子句（不含冒号，冒号前往往是"作业："这类前缀）
RE_FALLBACK_SPLIT = re.compile(r"[，。；！？,;!?]")

REVISE_WORDS = ("更正", "改为", "改成", "调整为", "不作数", "说错了", "重新发一下")
# 自然说法：改期/延后/提前。这类词在"调课通知"里也常见（"下次课改到三教302"），
# 所以单独放一组：只有 _revision_target 找到了目标卡才按更正处理（见 process）。
NATURAL_REVISE_WORDS = ("延期到", "推迟到", "顺延到", "提前到",
                         "延期", "推迟", "顺延")
# "不用交/免交"这类否定说法也算撤销，但必须先指到某张卡才生效（见 process）
REVOKE_WORDS = ("作废", "取消", "忽略", "发错", "算了不", "撤销该", "撤回该",
                "不用交", "不用提交", "不需要交", "不需要提交",
                "无需交", "无需提交", "免交", "不用再交", "不用交了", "不交了")
# 提问词：出现在消息里说明是在问，不是在下达更正/撤销
REVISION_ASK_WORDS = ("吗", "？", "?", "呢")

# ---- 考试/日程卡（规则见 docs/考试卡规则.md，和 Java 版行为一致）
# 明确的考试词：命中就算"提到考试"
EXAM_WORDS = ("期中考试", "期末考试", "期中考", "期末考", "考试", "测验", "小测",
              "笔试", "机考", "答辩")
# "期中/期末"是弱词：消息里没有提交动词时才算考试
# （"期末大作业周五前交"仍是普通待办，不能出考试卡）
EXAM_WEAK_WORDS = ("期中", "期末")
EXAM_SUBMIT_WORDS = ("交", "提交", "上传")
# 准备类词：说的是"考前把资料看完/预习"之类，不是考试通知，不出考试卡，按普通待办走
EXAM_PREP_WORDS = ("看完", "看一下", "看看", "复习好", "预习", "背完")
# 改期词：和考试词同时出现时只改原卡时间，不新建卡
EXAM_RESCHED_WORDS = ("改到", "推迟到", "延期到", "提前到", "调整到", "挪到")
EXAM_NEW_TIME_RE = re.compile("|".join((*EXAM_RESCHED_WORDS, "改为", "改成", "调整为")))
# 提问词 / 第一人称开头：命中就不出卡、也不改卡
EXAM_ASK_WORDS = ("吗", "？", "?", "呢", "什么", "几点", "哪")
EXAM_ASK_PREFIX = ("我", "俺", "咱")
EXAM_REFERENCE_SPLIT = re.compile(r"[，,。；;\n！!？?：:]")
EXAM_REFERENCE_PREFIX = re.compile(
    r"^(?:@\s*(?:所有人|全体)\s*|(?:关于|更正(?:一下)?|通知|提醒|取消|作废|撤销(?:该)?|撤回(?:该)?|忽略|原定的?|这场|那场)\s*)+"
)
# "带学生证和2B铅笔" → 学生证和2B铅笔（到下一个标点为止）
EXAM_BRING_RE = re.compile(r"(?:携带|自备|带)[ \t]*[：:]?[ \t]*([^，。；;！!\r\n]*)")
# find_place 只认"三教"这类写法，考试卡补一条"地点…"兜底（如"教三201"）
EXAM_PLACE_RE = re.compile(r"地点[ \t]*[：:]?[ \t]*([^，。；\n]{2,12})")
# 楼名 + 房号也算地点：教一/教二…/教九/教学楼/实验楼/理科楼/综合楼 后跟数字房号（可带字母），
# 整段写进 place，如"教二305""实验楼305""教一102"
EXAM_BUILDING_PLACE_RE = re.compile(
    r"((?:教学楼|实验楼|理科楼|综合楼|教[一二三四五六七八九])\s*[A-Za-z]?\d+[A-Za-z0-9]*)"
)

# 撤销/更正专用词：出现在任何卡里都说明不了指向哪一条，匹配时排除
REVISION_STOP = ("刚才", "上条", "之前", "那条", "截止", "更正",
                 "取消", "作废", "改成", "改为", "一下", "今晚", "明天",
                 # 会误匹配到无关卡片的套话（如"下周一上课，大家不用带电脑"）
                 "大家", "上课", "下周",
                 # 调课通知里的常见词，避免"下次课改到三教302"误指向待办卡
                 "腾讯", "会议", "教室", "下次",
                 # "请相互转告"套话切出来的二字词
                 "请相", "相互", "互转", "转告")
# "刚才"类指代词：配合"30 分钟内"才认为指向刚发的那条消息
REVISION_PRONOUN_NEAR = ("刚才", "刚刚", "那条")
# "之前"类指代词：原发送者本人说时，取此人在本群最新的一张卡，不限时间
REVISION_PRONOUN_FAR = ("之前", "上条", "上面", "前面")
GENERIC_HOMEWORK_REVISION = re.compile(
    r"(?:^|[，,。；;\s])(?:这次|本次|此次)?作业\s*(?:截止(?:时间)?|提交时间|时间)?\s*"
    r"(?:改到|改为|改成|调整为|延期到|推迟到|顺延到|提前到)"
)
HOMEWORK_SUBJECTS = ("作业", "实验报告", "预习报告", "课后习题", "读书报告", "读书笔记", "读书心得")

AT_ALL_RE = re.compile(r"@\s*所有人|@\s*全体|\[有全体消息\]")

ACTION, NOTICE, DISCUSSION, CHATTER = "action", "notice", "discussion", "chatter"


@dataclass
class Profile:
    name: str = "李承宇"
    aliases: tuple[str, ...] = ("承宇", "小李", "李同学")
    sid: str = "20230137"
    klass: str = "计科 2301"
    group_no: str = "第3组"
    electives: tuple[str, ...] = ("数据库系统", "计算机网络")
    duties: tuple[str, ...] = ()


def relevance(text: str, group_name: str, profile: Profile) -> tuple[float, str]:
    """判断一条消息是不是在说你（方案书 7.1）。"""
    if "@" + profile.name in text or any("@" + a in text for a in profile.aliases):
        return 1.0, "直接 @你"
    if profile.name in text or any(a in text for a in profile.aliases):
        return 1.0, "提到你的名字"
    if profile.sid and profile.sid in text:
        return 1.0, "提到你的学号"
    if profile.group_no and profile.group_no in text:
        return 0.9, f"点到{profile.group_no}"
    if profile.klass.replace(" ", "") in text.replace(" ", ""):
        return 0.85, "点到你的班级"
    for c in profile.electives:
        if c in text or c in group_name:
            return 0.8, f"你选了{c}"
    return 0.4, "群内广播"


class Model:
    def __init__(self, path: Path):
        d = json.loads(Path(path).read_text(encoding="utf-8"))
        self.labels: list[str] = d["labels"]
        self.intercept: list[float] = d["intercept"]
        self.weights: dict[str, list[float]] = d["weights"]
        self.holdout_acc: float = d.get("holdout_acc", 0.0)
        self.manual_keys = d.get("manual_keys", MANUAL_KEYS)

    def scores(self, vec: dict[str, float]) -> list[float]:
        s = list(self.intercept)
        for k, v in vec.items():
            w = self.weights.get(k)
            if not w:
                continue
            for i in range(len(s)):
                s[i] += w[i] * v
        return s

    def predict(self, vec: dict[str, float]) -> tuple[str, float]:
        s = self.scores(vec)
        m = max(s)
        ex = [math.exp(x - m) for x in s]
        z = sum(ex)
        probs = [e / z for e in ex]
        i = max(range(len(probs)), key=lambda k: probs[k])
        return self.labels[i], probs[i]


@dataclass
class Card:
    card_id: str
    kind: str                      # todo / jielong / info / exam
    title: str
    deadline: datetime | None
    needs_confirm: bool
    channel: str | None
    place: str | None
    group_id: str
    group_name: str
    sender: str
    source_msg_id: str
    source_text: str
    sent_at: datetime
    relevance: float
    relevance_reason: str
    confidence: float
    reasons: list[str] = field(default_factory=list)
    context: list[dict] = field(default_factory=list)
    updated: bool = False
    revoked: bool = False
    bring: str = ""                # 考试/日程卡：要带的东西（其他类型留空）

    def to_dict(self, now: datetime | None = None) -> dict:
        return {
            "card_id": self.card_id,
            "kind": self.kind,
            "title": self.title,
            "deadline": self.deadline.isoformat(timespec="seconds") if self.deadline else None,
            "deadline_human": human_deadline(self.deadline, now),
            "needs_confirm": self.needs_confirm,
            "channel": self.channel,
            "place": self.place,
            "bring": self.bring,
            "group_name": self.group_name,
            "sender": self.sender,
            "source_msg_id": self.source_msg_id,
            "source_text": self.source_text,
            "sent_at": self.sent_at.isoformat(timespec="seconds"),
            "relevance": self.relevance,
            "relevance_reason": self.relevance_reason,
            "confidence": round(self.confidence, 3),
            "reasons": self.reasons,
            "context": self.context,
            "updated": self.updated,
            "revoked": self.revoked,
        }


class Pipeline:
    def __init__(self, model: Model, profile: Profile | None = None, use_profile: bool = True,
                 use_rules: bool = True, use_burst: bool = True, keep_context: int = 3):
        self.model = model
        self.profile = profile or Profile()
        self.use_profile = use_profile
        self.use_rules = use_rules
        self.use_burst = use_burst
        self.keep_context = keep_context
        self.stats = Stats()
        self.cards: list[Card] = []
        self.by_source: dict[str, Card] = {}
        self.recent: dict[str, list[dict]] = {}
        self.n_in = self.n_noise = self.n_level3 = 0
        self.acks: dict[str, list[datetime]] = {}
        self.candidates: dict[str, list[dict]] = {}
        self.n_promoted = 0
        self.log: list[dict] = []

    # ---- 第一级强规则：命中就直接定性，不让统计模型压过明确证据
    TEACHER_HINTS = ("老师", "辅导员", "班长", "课代表", "社长", "主任")
    QUESTION_HINTS = ("吗", "？", "?", "呢", "吧？")

    @staticmethod
    def _is_question(text: str) -> bool:
        """是不是在提问：含"吗/？/?/吧？"，或者整句以"呢"收尾。
        句中的"呢"是语气词（"周五之前呢，把报告交一下"），不算提问。"""
        t = text.strip()
        return any(q in t for q in ("吗", "？", "?", "吧？")) or t.rstrip("。！!~… ").endswith("呢")

    def _rule_label(self, vec: dict[str, float], text: str, sender: str) -> str | None:
        has_time = vec.get("M:has_time", 0) > 0
        has_action = vec.get("M:has_action", 0) > 0
        at_me = vec.get("M:at_me", 0) > 0
        at_all = vec.get("M:at_all", 0) > 0
        notifier = vec.get("M:sender_notifier", 0) > 0
        is_teacher = any(h in (sender or "") for h in self.TEACHER_HINTS)
        asking = self._is_question(text)
        first_person = text.strip().startswith(("我", "俺", "咱们"))

        # 明确的"动作 + 时限"就是待办，哪怕句式没见过
        if has_action and has_time and not first_person and not asking:
            if at_me or at_all or notifier or is_teacher:
                return ACTION
        # 常发通知的人让你做一件事，没写时间也要出卡片（标待确认），不能装看不见
        if has_action and is_teacher and not first_person and not asking \
                and len(text.strip()) <= 40:
            if any(v in text for v in ("交", "提交", "填", "报名", "签到", "上传", "领")):
                return ACTION
        return None

    # ---- 变更 / 撤销是自己看文本判断的，不用任何标签
    @staticmethod
    def _asking(text: str) -> bool:
        return any(w in text for w in REVISION_ASK_WORDS)

    def _split_items(self, text: str, ts: datetime) -> list[str]:
        """把一条消息拆成"各自带动作、各自带具体时间"的子句；不够两件就返回空列表。

        接龙、更正/撤销、考试通知都不拆（它们有自己的流程）。只有一个时间的消息
        （"周五前交报告，交到邮箱，逾期不候"）也不会被拆。
        """
        if len(text) < 8 or "接龙" in text:
            return []
        if self.detect_revision(text) is not None or self._exam_word(text):
            return []
        items = []
        for clause in re.split(r"[，,；;。\n：:]", text):
            clause = clause.strip()
            if len(clause) < 4:
                continue
            if find_action(clause)[1] is None:
                continue
            if parse_deadline(clause, ts).value is None:
                continue
            items.append(clause)
        return items if len(items) >= 2 else []

    @staticmethod
    def detect_revision(text: str) -> str | None:
        # 问句（"不用交了吗"）是在打听，不是在下指令
        if Pipeline._asking(text):
            return None
        if any(w in text for w in REVOKE_WORDS):
            return "revoke"
        if any(w in text for w in REVISE_WORDS):
            return "supersede"
        # 自然改期词只有对得上某张卡时才算更正（见 process）
        if any(w in text for w in NATURAL_REVISE_WORDS):
            return "supersede"
        return None

    @staticmethod
    def _natural_revision_only(text: str) -> bool:
        """只命中自然改期词、没命中原七个更正词：可能是调课通知，需先找到目标卡。"""
        return not any(w in text for w in REVISE_WORDS) \
            and any(w in text for w in NATURAL_REVISE_WORDS)

    @staticmethod
    def _revision_like(text: str) -> bool:
        return any(w in text for w in REVOKE_WORDS + REVISE_WORDS)

    def _context(self, group_id: str) -> list[dict]:
        return list(self.recent.get(group_id, [])[-self.keep_context:])

    def _remember(self, msg: dict) -> None:
        self.recent.setdefault(msg["group_id"], []).append(
            {"sender": msg["sender"], "text": msg["text"], "ts": msg["ts"]})
        if len(self.recent[msg["group_id"]]) > 20:
            self.recent[msg["group_id"]] = self.recent[msg["group_id"]][-20:]

    def process(self, msg: dict) -> list[Card]:
        """处理一条消息，返回本次新增/更新的卡片（可能为空）。"""
        ts = datetime.fromisoformat(msg["ts"]) if isinstance(msg["ts"], str) else msg["ts"]
        text = msg["text"]
        # 一条消息里有两件以上各带具体时间的事（"周三前交A，周五前交B"）：拆开，各出一张卡
        if not msg.get("_split"):
            items = self._split_items(text, ts)
            if len(items) >= 2:
                produced_all: list[Card] = []
                for i, part in enumerate(items):
                    sub = dict(msg)
                    sub["text"] = part
                    sub["msg_id"] = f"{msg['msg_id']}#{i + 1}"
                    sub["_split"] = True
                    produced_all.extend(self.process(sub))
                return produced_all
        gid, sender = msg["group_id"], msg["sender"]
        self.n_in += 1
        produced: list[Card] = []

        # 第 0 步：噪声。仍要 observe，因为发送者统计靠它计数
        noise = is_noise(text)
        if noise:
            self.n_noise += 1
            self.stats.observe(gid, sender, ts, text, CHATTER)
            self._remember(msg)
            if self.use_burst:
                self._track_ack(gid, ts, text)
            return produced

        # 第 0.5 步：重复推送去重。若本群（gid 相同）已有某张卡，其 source_text
        # 去掉所有空格后与本条 text 去掉所有空格后完全相同，视为同一条消息被
        # 重复推送：直接丢弃，不出新卡、不更新、也不记入上下文（不 _remember）。
        # 只在本群内比较；不同群同文字仍各出一张。source_text 可能为空，需判空。
        # 被撤销的卡已从 self.cards 移除，所以删卡后重发能重新出卡。
        norm_text = re.sub(r"\s+", "", text or "")
        if norm_text:
            for c in self.cards:
                if c.group_id != gid:
                    continue
                src = c.source_text or ""
                # 只认 30 分钟内的重复推送：隔很多天再发同一句话（如每周的"交周报"）是新通知
                if src and re.sub(r"\s+", "", src) == norm_text \
                        and c.sent_at is not None and abs(ts - c.sent_at) <= timedelta(minutes=30):
                    return produced

        # 第 1 步：特征（依赖 Stats 的当前状态，所以先算后 observe）
        # use_burst 只决定"要不要跟确认回复重打分"，发送者统计照常传给特征
        vec = vectorize(text, sender, self.stats, gid, ts, msg.get("course"))
        # 线上没有标签，用模型自己的判断更新"这个发送者是不是常发通知的人"
        self.stats.observe(gid, sender, ts, text, None)

        # 第 2 步：分类
        if self.use_rules:
            label, prob = self.model.predict(vec)
        else:
            # 消融：去掉第一级规则时，只剩文本特征
            only_text = {k: v for k, v in vec.items() if k.startswith("T:")}
            label, prob = self.model.predict(only_text)
        rule = self._rule_label(vec, text, sender) if self.use_rules else None
        if rule:
            label, prob = rule, max(prob, 0.75)
        # 提问不是待办（"周五之前交实验报告吗"）：统计模型误判成待办时在这里拦下
        if label == ACTION and self._is_question(text):
            label = DISCUSSION
        self.stats.sender_notify[(gid, sender)] += 1 if label in (ACTION, NOTICE) else 0

        rel, rel_reason = relevance(text, msg.get("group_name", ""), self.profile) \
            if self.use_profile else (0.5, "未启用档案")

        if label in (DISCUSSION, CHATTER) and vec.get("M:has_action") and vec.get("M:has_time"):
            self._register_candidate(msg, ts, rel, rel_reason)

        # 变更 / 撤销：优先处理，避免把"改到下周"当成新待办
        rev = self.detect_revision(text)
        exam_word = self._exam_word(text)
        if exam_word and (any(w in text for w in EXAM_RESCHED_WORDS) or (rev and exam_word in EXAM_WORDS)):
            target = self._apply_exam_revision(gid, sender, text, exam_word, rev, ts)
            self._remember(msg)
            return [target] if target is not None else produced
        # 问句里出现撤销/更正词：既不算更正，也不能顺势变成一张新待办
        if rev is None and self._asking(text) and self._revision_like(text):
            self._remember(msg)
            return produced
        if rev:
            target = self._revision_target(
                msg, text, title_only=(rev == "supersede" and self._natural_revision_only(text)))
            if self._revision_ambiguous:
                self._remember(msg)
                return produced
            if target is not None:
                if rev == "revoke":
                    target.revoked = True
                    self.cards = [c for c in self.cards if c is not target]
                    self.by_source.pop(target.source_msg_id, None)
                    self._remember(msg)
                    return produced
                e = extract(text, ts)
                if e.deadline:
                    target.deadline = e.deadline
                    target.needs_confirm = False
                    target.updated = True
                    produced.append(target)
                    self._remember(msg)
                    return produced
                self._remember(msg)
                return produced
            # 自然改期词（"改到/挪到/延期"…）也可能是调课通知，对不上任何卡时
            # 不按更正处理，继续走下面的正常消息流程。
            if not (rev == "supersede" and self._natural_revision_only(text)):
                # 对不上任何卡：撤销/更正只对"说得清指的是哪条"的消息生效，
                # 不能顺势把这句"作废/更正"当成新待办再建一张卡
                self._remember(msg)
                return produced

        # 接龙：独立通道，不依赖分类结果（接龙可能被误判成闲聊）
        j = jl.parse(text, ts)
        if j.is_jielong and not j.joined:
            card = Card(
                card_id=f"c{len(self.cards) + 1:04d}",
                kind="jielong",
                title=f"接龙：{j.title}（你还没接）",
                deadline=j.deadline,
                needs_confirm=j.deadline is None,
                channel="群接龙",
                place=None,
                group_id=gid,
                group_name=msg.get("group_name", gid),
                sender=sender,
                source_msg_id=msg["msg_id"],
                source_text=text,
                sent_at=ts,
                relevance=1.0 if j.roster is not None else rel,
                relevance_reason="接龙名单里没有你",
                confidence=max(prob, 0.9),
                reasons=[j.note],
                context=self._context(gid),
            )
            self.cards.append(card)
            self.by_source[msg["msg_id"]] = card
            produced.append(card)
            self._remember(msg)
            return produced
        if j.is_jielong:
            # 我已经接过了：这是"已办"，不是待办，也不是闲聊
            self._remember(msg)
            return produced

        # 考试/日程卡：故意放在撤销/更正和接龙之后、按分类标签分流之前，
        # 这样分类器判成"通知"甚至"闲聊"的考试消息也照样出卡（docs/考试卡规则.md）
        if exam_word:
            asking = self._exam_asking(text)
            first_person = text.strip().startswith(EXAM_ASK_PREFIX)
            trusted = any(h in (sender or "") for h in self.TEACHER_HINTS) \
                or AT_ALL_RE.search(text) is not None
            # 新考试/日程：考试词 + 具体时间 + 可信发送者 + 非提问/非第一人称
            if trusted and not asking and not first_person:
                ex = extract(text, ts)
                if ex.deadline is not None:
                    place = ex.place or self._exam_place(text)
                    card = Card(
                        card_id=f"c{len(self.cards) + 1:04d}",
                        kind="exam",
                        title=exam_word + (f"（{place}）" if place else ""),
                        deadline=ex.deadline,
                        needs_confirm=ex.time_conf == "low",
                        channel=ex.channel,
                        place=place,
                        bring=self._exam_bring(text),
                        group_id=gid,
                        group_name=msg.get("group_name", gid),
                        sender=sender,
                        source_msg_id=msg["msg_id"],
                        source_text=text,
                        sent_at=ts,
                        relevance=rel,
                        relevance_reason=rel_reason,
                        confidence=max(prob, 0.85),
                        reasons=[f"考试/日程：命中“{exam_word}”"] + ex.reasons,
                        context=self._context(gid),
                    )
                    self.cards.append(card)
                    self.by_source[msg["msg_id"]] = card
                    produced.append(card)
                    self._remember(msg)
                    return produced

            # 提到考试但条件不满足（时间不具体、同学提问、第一人称…）：不出卡
            self._remember(msg)
            return produced

        # 第 3 步：只处理①②
        if label in (ACTION, NOTICE):
            self.n_level3 += 1
            e: Extracted = extract(text, ts, is_jielong=j.is_jielong)
            if label == NOTICE:
                # ②类只出信息卡：知道发生了什么，但不占用待办位
                if e.deadline is not None:
                    info = Card(
                        card_id=f"i{len(self.cards) + 1:04d}", kind="info",
                        title=self._fallback_title(text), deadline=e.deadline,
                        needs_confirm=False, channel=e.channel, place=e.place,
                        group_id=gid, group_name=msg.get("group_name", gid),
                        sender=sender, source_msg_id=msg["msg_id"], source_text=text,
                        sent_at=ts, relevance=rel, relevance_reason=rel_reason,
                        confidence=prob, reasons=["通知类信息，非待办"], context=self._context(gid))
                    self.cards.append(info)
                    produced.append(info)
                self._remember(msg)
                return produced
            # 与我无关的课程群广播不打扰我（老师/班委发的、@所有人 的除外）
            gname = msg.get("group_name", "")
            keep_group = any(h in (sender or "") for h in self.TEACHER_HINTS) \
                or AT_ALL_RE.search(text) is not None
            if self.use_profile and rel <= 0.4 and "课" in gname and not keep_group \
                    and not any(c in gname for c in self.profile.electives):
                self._remember(msg)
                return produced

            action = e.action or ("待确认：提交事项" if any(is_delivery_only(c) for c in RE_SPLIT.split(text)) else self._fallback_title(text))
            card = Card(
                card_id=f"c{len(self.cards) + 1:04d}",
                kind="todo",
                title=action,
                deadline=e.deadline,
                needs_confirm=e.needs_confirm,
                channel=e.channel,
                place=e.place,
                group_id=gid,
                group_name=msg.get("group_name", gid),
                sender=sender,
                source_msg_id=msg["msg_id"],
                source_text=text,
                sent_at=ts,
                relevance=rel,
                relevance_reason=rel_reason,
                confidence=prob,
                reasons=e.reasons,
                context=self._context(gid),
            )
            self.cards.append(card)
            self.by_source[msg["msg_id"]] = card
            produced.append(card)

        self._remember(msg)
        return produced

    ACK_WORDS = {"收到", "好的", "1", "+1", "收到收到", "好嘞", "ok", "OK", "明白", "嗯嗯", "好"}
    BURST_PROMOTE = 5      # 10 分钟内 >=5 条确认回复，升为待办

    def _track_ack(self, gid: str, ts: datetime, text: str) -> None:
        """回复潮：一条通知后面跟一串"收到"时，把它从"讨论"升成"要行动"。

        方案书 6.3「消息 i 的分类不是一次定死的」——每来一条确认回复就重打一次分。
        """
        if text.strip() not in self.ACK_WORDS:
            return
        self.acks.setdefault(gid, []).append(ts)
        self._rescore(gid, ts)

    def _register_candidate(self, msg: dict, ts: datetime, rel: float, rel_reason: str) -> None:
        self.candidates.setdefault(msg["group_id"], []).append({
            "msg": msg, "ts": ts, "carded": False, "burst": 0,
            "rel": rel, "rel_reason": rel_reason,
        })

    def _rescore(self, gid: str, now: datetime) -> None:
        cands = self.candidates.get(gid)
        if not cands:
            return
        acks = [t for t in self.acks.get(gid, []) if now - t <= timedelta(minutes=10)]
        for c in cands:
            if c["carded"] or now - c["ts"] > timedelta(minutes=10):
                continue
            c["burst"] = sum(1 for t in acks if c["ts"] < t <= now)
            if c["burst"] >= self.BURST_PROMOTE:
                self._promote(c)

    def _promote(self, cand: dict) -> None:
        msg, ts = cand["msg"], cand["ts"]
        j = jl.parse(msg["text"], ts)
        if j.is_jielong and j.joined:
            cand["carded"] = True
            return
        e = extract(msg["text"], ts)
        if e.deadline is None and e.action is None:
            return
        card = Card(
            card_id=f"c{len(self.cards) + 1:04d}",
            kind="todo",
            title=e.action or ("待确认：提交事项" if any(is_delivery_only(c) for c in RE_SPLIT.split(msg["text"])) else self._fallback_title(msg["text"])),
            deadline=e.deadline,
            needs_confirm=e.needs_confirm,
            channel=e.channel,
            place=e.place,
            group_id=msg["group_id"],
            group_name=msg.get("group_name", msg["group_id"]),
            sender=msg["sender"],
            source_msg_id=msg["msg_id"],
            source_text=msg["text"],
            sent_at=ts,
            relevance=cand["rel"],
            relevance_reason=cand["rel_reason"],
            confidence=0.62,
            reasons=[f"回复潮 {cand['burst']} 条确认回复，升为待办"] + e.reasons,
            context=self._context(msg["group_id"]),
        )
        self.cards.append(card)
        self.by_source[msg["msg_id"]] = card
        cand["carded"] = True
        self.n_promoted += 1

    @staticmethod
    def _fallback_title(text: str) -> str:
        # 先取第一个标点子句，做与 find_action 相同的时间/称呼清理；太短再退回旧办法
        first = text.strip().splitlines()[0] if text.strip() else ""
        clause = next((c.strip() for c in RE_FALLBACK_SPLIT.split(first) if c.strip()), "")
        cleaned = clean_title(clause) if clause else ""
        title = cleaned if len(cleaned) >= 2 else first
        return (title[:18] + "…") if len(title) > 18 else title

    def _latest_card(self, group_id: str) -> Card | None:
        for c in reversed(self.cards):
            if c.group_id == group_id:
                return c
        return None

    # ---- 考试/日程卡的小工具
    @staticmethod
    def _exam_word(text: str) -> str | None:
        """返回命中的考试词；"期中/期末"遇到提交动词就不算考试（仍是普通待办）。

        含"看完/看一下/看看/复习好/预习/背完"等准备类词时说的是考前准备，
        不是考试通知，直接返回 None，让它走原来的普通待办流程。
        """
        if any(w in text for w in EXAM_PREP_WORDS):
            return None
        for w in EXAM_WORDS:
            if w in text:
                return w
        if any(v in text for v in EXAM_SUBMIT_WORDS):
            return None
        for w in EXAM_WEAK_WORDS:
            if w in text:
                return w
        return None

    @staticmethod
    def _exam_asking(text: str) -> bool:
        """规则 4：问句不出卡、不改卡。"""
        return any(w in text for w in EXAM_ASK_WORDS)

    @staticmethod
    def _exam_bring(text: str) -> str:
        """取"带/携带/自备"后面到下一个标点的内容。"""
        m = EXAM_BRING_RE.search(text)
        return m.group(1).strip() if m else ""

    @staticmethod
    def _exam_place(text: str) -> str:
        """地点兜底：先按"地点…"取，认不出再按"教二305/实验楼305"这类楼名+房号取整段。"""
        m = EXAM_PLACE_RE.search(text)
        if m:
            return m.group(1).strip()
        m = EXAM_BUILDING_PLACE_RE.search(text)
        return m.group(1).strip() if m else ""

    def _apply_exam_revision(self, group_id: str, sender: str, text: str, hit: str,
                             revision: str | None, sent_at: datetime) -> Card | None:
        """考试更正必须唯一匹配同群的一张考试卡。"""
        if self._exam_asking(text) or text.strip().startswith(EXAM_ASK_PREFIX):
            return None
        reference = hit
        for part in EXAM_REFERENCE_SPLIT.split(AT_ALL_RE.sub(" ", text)):
            index = part.find(hit)
            if index < 0:
                continue
            prefix = EXAM_REFERENCE_PREFIX.sub("", part[:index].strip())
            name = "".join((prefix + hit).lower().split())
            if len(name) > len(reference):
                reference = name

        matches = [card for card in self.cards if card.kind == "exam" and card.group_id == group_id
                   and not card.revoked and reference in "".join(card.source_text.lower().split())]
        if len(matches) != 1:
            return None
        target = matches[0]
        trusted = any(h in sender for h in self.TEACHER_HINTS) or AT_ALL_RE.search(text) is not None
        if not trusted and (not sender or sender != target.sender):
            return None
        if revision == "revoke":
            target.revoked = True
            self.cards = [card for card in self.cards if card is not target]
            self.by_source.pop(target.source_msg_id, None)
            return None
        deadline = parse_deadline(EXAM_NEW_TIME_RE.split(text)[-1], sent_at)
        if deadline.value is None:
            return None
        target.deadline = deadline.value
        target.needs_confirm = deadline.confidence == "low"
        target.updated = True
        return target

    def _revision_target(self, msg: dict, text: str, title_only: bool = False) -> Card | None:
        """找这条撤销/更正真正指向的那张卡：只在本群、从新到旧找，对不上返回 None。

        发送者得是原发送者或老师一类的人，消息还要对得上事项（共同中文词）；
        指代词分两类："刚才"类只认 30 分钟内发的，"之前"类由原发送者本人说时
        不限时间，取此人在本群最新的一张卡。
        """
        self._revision_ambiguous = False
        sender = msg.get("sender") or ""
        text = AT_ALL_RE.sub(" ", text)
        gid = msg.get("group_id") or ""
        ts = msg["ts"] if isinstance(msg["ts"], datetime) else datetime.fromisoformat(msg["ts"])
        trusted = any(h in sender for h in self.TEACHER_HINTS)
        near = any(p in text for p in REVISION_PRONOUN_NEAR)
        far = any(p in text for p in REVISION_PRONOUN_FAR)
        keys: set[str] = set()
        for i in range(len(text) - 1):
            a, b = text[i], text[i + 1]
            if not ("\u4e00" <= a <= "\u9fff" and "\u4e00" <= b <= "\u9fff"):
                continue
            g = text[i:i + 2]
            if g in REVISION_STOP:
                continue
            keys.add(g)
        best = None
        best_score = 0
        tied = False
        best_matches = []
        for c in reversed(self.cards):
            if c.group_id != gid:
                continue
            if c.revoked:
                continue
            if not trusted and sender != c.sender:
                continue
            # 自然改期词（改到/挪到/延期…）容易撞上调课通知，只拿卡片标题来对，
            # 也不走"之前/刚才"这类指代兜底；原来的更正词仍按标题加原文匹配。
            blob = c.title or ""
            score = sum(g in blob for g in keys)
            if score > best_score:
                best, best_score = c, score
                tied = False
                best_matches = [c]
            elif score > 0 and score == best_score:
                tied = True
                best_matches.append(c)
        if best is not None:
            return self._defer_revision(text, best_matches) if tied else best
        if sender and GENERIC_HOMEWORK_REVISION.search(text):
            homework = [c for c in self.cards
                        if c.group_id == gid and c.sender == sender and not c.revoked and c.kind == "todo"
                        and 0 <= (ts - c.sent_at).total_seconds() <= 7 * 86400
                        and any(word in (c.source_text or "") for word in HOMEWORK_SUBJECTS)]
            if len(homework) > 1:
                return self._defer_revision(text, homework)
            if homework:
                return homework[0]
        if title_only:
            return None
        source_matches = [c for c in self.cards if c.group_id == gid and not c.revoked
                          and (trusted or sender == c.sender)
                          and any(key in (c.source_text or "") for key in keys)]
        if source_matches:
            return source_matches[0] if len(source_matches) == 1 else self._defer_revision(text, source_matches)
        for c in reversed(self.cards):
            if c.group_id != gid or c.revoked or (not trusted and sender != c.sender):
                continue
            if near:
                dt = (ts - c.sent_at).total_seconds()
                if 0 <= dt <= 30 * 60:
                    return c
            if far and sender == c.sender:
                return c
        return None

    def _defer_revision(self, text: str, matches: list[Card]) -> None:
        self._revision_ambiguous = True
        for card in matches:
            card.needs_confirm = True
            reason = "群里有变更，但对应事项不明确，请核对：" + text[:80]
            if reason not in card.reasons:
                card.reasons.append(reason)
        return None

    # ---- 输出
    def active_cards(self) -> list[Card]:
        return [c for c in self.cards if not c.revoked]

    def ranked(self, limit: int = 3, now: datetime | None = None) -> list[Card]:
        """排序：按档次分档，档次小的在前；档次内按规则再排。"""
        now = now or datetime.now()
        act = self.active_cards()

        def key(c: Card):
            dl = c.deadline
            if dl is None:
                # 档次 2：没有 deadline 的卡，档次内按 relevance 从大到小
                tier, order = 2, -c.relevance
            elif dl < now:
                # 档次 3：已过期（deadline<now，含接龙），按 deadline 从大到小，
                # 即最近过期的排在最前
                tier, order = 3, -dl.timestamp()
            else:
                # 档次 0：未过期的接龙卡；档次 1：其他有 deadline 且未过期的卡。
                # 两档档次内都按 deadline 从小到大
                tier = 0 if c.kind == "jielong" else 1
                order = dl.timestamp()
            return (tier, order, -c.relevance)

        return sorted(act, key=key)[:limit]

    def run(self, rows: list[dict]) -> dict:
        for m in rows:
            self.process(m)
        return {
            "n_in": self.n_in,
            "n_noise": self.n_noise,
            "n_level3": self.n_level3,
            "n_cards": len(self.active_cards()),
            "cards": [c.to_dict() for c in self.ranked(limit=50)],
        }
