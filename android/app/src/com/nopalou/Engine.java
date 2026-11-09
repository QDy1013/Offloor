package com.nopalou;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 端侧三级流水线（对应方案书第 6、7 节）。
 * 全程不联网：规则在本地，分类器是本地权重，时间解析是本地规则。
 *
 * 与 Python 端严格对齐：同一套 8 维手工特征、同一份字符 2-gram 权重、
 * 同一套时间解析口径。Python 端训练，这里只做推理。
 */
public class Engine {
    private static final String TIME_UNCERTAIN = "时间表达不清楚，建议核对原文";

    // ---------- 常量（与 Python 端一致）----------
    static final String[] LABELS = {"action", "notice", "discussion", "chatter"};
    static final String[] ACTION_WORDS = {"交", "提交", "填", "签到", "报名", "接龙", "回复",
            "领取", "打卡", "上传", "传", "下载", "参加", "准备", "完成", "补交", "缴费", "选课"};
    static final String[] TIME_HINTS = {"前", "点", "分", "今天", "明天", "后天", "今晚", "明晚",
            "周", "星期", "月", "日", "截止", "之前", "上课"};
    static final String[] LINK_FILE = {"http", "www.", ".com", "[文件]", "[图片]", "[链接]", ".pdf", ".docx", ".zip"};
    static final String[] IDENTITY = {"学号", "班级", "班", "组", "选课", "选修", "同学", "课代表",
            "班委", "数据库", "计算机网络", "操作系统", "数据结构", "软件工程", "概率论"};
    static final String[] CHANNELS = {"学习通", "雨课堂", "课程网站", "QQ 群文件", "邮箱", "教务系统",
            "课代表处", "微信转账", "班级接龙", "群接龙", "腾讯会议", "问卷", "飞书", "钉钉"};
    static final String[] VERBS = {"交", "提交", "上传", "传", "填", "报名", "签到", "接龙", "回复",
            "领取", "打卡", "参加", "完成", "补交", "缴费", "选课", "下载"};
    static final Set<String> ACKS = new HashSet<>(Arrays.asList(
            "收到", "好的", "1", "+1", "收到收到", "好嘞", "ok", "OK", "明白", "嗯嗯", "好"));
    static final String[] TEACHER_HINTS = {"老师", "辅导员", "班长", "课代表", "社长", "主任"};
    // 考试/日程卡（规则见 docs/考试卡规则.md）
    static final String[] EXAM_WORDS = {"考试", "测验", "小测", "笔试", "机考", "答辩"};
    // 单独的"期中""期末"要再看有没有提交动词："期末大作业周五前交"仍是普通待办
    static final String[] TERM_WORDS = {"期中", "期末"};
    static final String[] SUBMIT_WORDS = {"交", "提交", "上传"};
    // 准备类词（补充规则第二条）：说的是"考前把资料看完/预习"之类，不是考试通知。
    // 含这些词就不出考试卡，按普通待办走；带"范围"但没有这些词仍出卡。
    static final String[] EXAM_PREP = {"看完", "看一下", "看看", "复习好", "预习", "背完"};
    // 改时间：这些词 detectRevision 不认，要在撤销/更正分支之前单独处理
    static final String[] RESCHEDULE_WORDS = {"改到", "推迟到", "延期到", "提前到", "调整到", "挪到"};
    static final Pattern P_EXAM_NEW_TIME = Pattern.compile("改到|推迟到|延期到|提前到|调整到|挪到|改为|改成|调整为");
    // 提问词：带这些就不当"改时间"，只当学生在问
    static final String[] ASK_WORDS = {"吗", "？", "?", "呢", "什么", "几点", "哪"};
    // "带/携带/自备"后面到下一个标点之间的内容，就是考试要带的东西
    static final Pattern P_BRING = Pattern.compile("(?:携带|自备|带)[ \\t]*[：:]?[ \\t]*([^，。；;！!\\r\\n]*)");
    static final int EXAM_TITLE_MAX = 24;
    static final String[] VAGUE = {"尽快", "近期", "这两天", "最近", "过几天", "有空", "随时",
            "抓紧", "尽早", "改天", "之后", "等通知", "另行通知"};
    static final String[] REVISE_WORDS = {"更正", "改为", "改成", "调整为", "不作数", "说错了", "重新发一下"};
    // 自然说法：改期/延后/提前。这类词在"调课通知"里也常见（"下次课改到三教302"），
    // 所以单独放一组：只有 revisionTarget 找到了目标卡才按更正处理（见 process）。
    // 故意不含"改到""挪到"：它们会把调课通知误判成更正。
    static final String[] NATURAL_REVISE_WORDS = {"延期到", "推迟到", "顺延到", "提前到",
            "延期", "推迟", "顺延"};
    // "不用交/免交"这类否定说法也算撤销，但必须先指到某张卡才生效（见 process）
    static final String[] REVOKE_WORDS = {"作废", "取消", "忽略", "发错", "算了不", "撤销该", "撤回该",
            "不用交", "不用提交", "不需要交", "不需要提交",
            "无需交", "无需提交", "免交", "不用再交", "不用交了", "不交了"};
    // 提问词：出现在消息里说明是在问，不是在下达更正/撤销
    static final String[] REVISION_ASK_WORDS = {"吗", "？", "?", "呢"};
    static final String[] DEICTIC_WORDS = {"上条", "上一条", "刚才", "之前", "上面", "前面", "那条", "这条"};
    // 判断"说的是哪件事"时忽略的套话，避免 "@所有人""大家" 这类字样把无关消息也算成命中
    static final String[] SUBJECT_NOISE = {"所有人", "全体", "大家", "同学们", "各位", "同学",
            "注意", "记得", "务必", "一下", "那个", "这个", "消息"};
    static final long REVISION_WINDOW = 30 * 60_000L;   // 指代词只在原消息 30 分钟内有效
    static final int BURST_PROMOTE = 5;
    static final long BURST_WINDOW_MS = 10 * 60_000L;

    static final Pattern P_CLOCK = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{2})");
    // 钟点支持中文数字：八点、十点半、两点、七点；分钟紧跟在钟点后，"半"=30 分
    static final Pattern P_HOUR = Pattern.compile("(\\d{1,2}|[零〇一二两三四五六七八九十]{1,3})\\s*[点时]"
            + "(?:\\s*(半)|\\s*(\\d{1,2}|[零〇一二两三四五六七八九十]{1,2})\\s*分)?");
    // 月日写法："1月10日" / "11 月 20 日" / "12/31" / "2026-1-10"，以及 "M.D"（9.14、10.4、12.31）。
    // "M.D"（M=1~12、D=1~31）默认就当月日；只有当它紧挨着前面是"习题/第/练习/例"，
    // 或紧挨着后面跟"页/题/章/节/小节"时，才当章节编号忽略。"2-3页" 仍然不算日期。
    static final Pattern P_ABS_CN = Pattern.compile("(?:(\\d{4})\\s*年\\s*)?(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]?");
    static final Pattern P_ABS_NUM = Pattern.compile(
            "(?:(\\d{4})\\s*[/\\-]\\s*)?(?<!\\d)(\\d{1,2})\\s*[/\\-.]\\s*(\\d{1,2})(?!\\d)"
                    + "(?!\\s*[页题章节张个分秒周])");
    static final String[] MD_STOP_PRE = {"习题", "练习", "第", "例"};
    static final String[] MD_STOP_POST = {"小节", "页", "题", "章", "节"};
    // 时段词：只看钟点前面最近的一个，避免"明早8点交，晚了不收"被"晚了"带偏
    static final String[] PERIOD_PM = {"下午", "傍晚", "晚上", "晚间", "夜里", "晚"};
    static final String[] PERIOD_AM = {"上午", "早上", "早晨", "凌晨", "一大早", "明早", "今早", "早"};
    static final String[] PERIOD_NOON = {"中午", "正午"};
    static final Pattern P_WEEK = Pattern.compile("(下下|下|本|这)?\\s*(?:周|星期|礼拜)\\s*(?:周|星期|礼拜)?\\s*([一二三四五六日天七])");
    static final Pattern P_WEEKEND = Pattern.compile("(这|本|下)?\\s*周末");
    static final Pattern P_MONTHEND = Pattern.compile("(这|本|下)?\\s*个?\\s*月底");
    static final Pattern P_DAYONLY = Pattern.compile("(这个月|本月|当月)?\\s*(\\d{1,2})\\s*[日号]");
    // "中午前/中午之前"后面没有具体几点时，按当天 12:00 处理（"上午前"等不动）
    static final Pattern P_NOON_BEFORE = Pattern.compile("中午\\s*(?:之前|前)");
    static final Pattern P_TITLE_TIME_WORDS = Pattern.compile(
            "大后天|后天|明天|明日|明晚|明早|今天|今日|今晚|今早|当天|上午|早上|早晨|凌晨|中午|正午|下午|傍晚|晚上|晚间|夜里");
    static final Pattern P_ATALL = Pattern.compile("@\\s*所有人|@\\s*全体|\\[有全体消息\\]");
    static final Pattern P_SPLIT = Pattern.compile("[，。；;\\n！!？?]");
    // 点号后紧跟一两位数字时按月日处理，例如“3.23 22:00 前报名”。
    static final Pattern P_NUMLINE = Pattern.compile(
            "(?m)^[ \\t]*(\\d{1,3})[ \\t]*(?:\\.(?!\\d{1,2}(?!\\d))|[、)])[ \\t]*(\\S[^\\r\\n]*)$");
    static final Pattern P_TITLE = Pattern.compile("[（(]([^）)]*)[）)]");

    // ---- 标题清理用的公共正则（findAction 与 fallbackTitle 共用，与 Python clean_title 一致）----
    // 时段词里补了单字"早""晚"，但它们必须紧跟数字 + 点/时才删，
    // 所以整段写成「时段词? 数字 点/时」，不会误删"早自习"。
    static final String TITLE_PERIOD =
            "(?:凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|今晚|明晚|今早|明早|早|晚)?";
    static final Pattern P_CLEAN_HOUR = Pattern.compile(
            TITLE_PERIOD + "\\s*(\\d{1,2}|[一二三四五六七八九十]{1,3})\\s*[点时]\\s*"
                    + "(?:半|\\d{0,2}\\s*分?)\\s*(?:之前|以前|前|截止)?");
    static final Pattern P_CLEAN_CLOCK = Pattern.compile(
            TITLE_PERIOD + "\\s*(\\d{1,2})\\s*[:：]\\s*(\\d{2})\\s*(?:之前|以前|前|截止)?");
    // "本周内/这周内/下周"后面的"内"和"前/之前"一样整段删掉
    // "这个周末前/这周末/本周末"整段删（必须在 P_CLEAN_WEEK 之前）
    static final Pattern P_CLEAN_WEEKEND = Pattern.compile(
            "(?:这个|这|本|下个|下)?\\s*周末\\s*(?:之前|以前|前|截止|内)?");
    static final Pattern P_CLEAN_WEEK = Pattern.compile(
            "(下|本|这)(周|星期|礼拜)[一二三四五六日天]?\\s*(?:之前|以前|前|截止|内)?");
    // "本月底/月底/月末/本月内/月内"（含可选"前/之前"）
    static final Pattern P_CLEAN_MONTH = Pattern.compile(
            "(?:这|本|下)?\\s*个?\\s*月\\s*(?:底|末|内)\\s*(?:之前|以前|前)?");
    static final Pattern P_CLEAN_DAY = Pattern.compile(
            "(?:今天|明天|后天|明晚|今晚|大后天|本周|这周|下周|周[一二三四五六日天]"
                    + "|星期[一二三四五六日天]|\\d{1,2}\\s*[月.]\\s*\\d{1,2}\\s*[日号]?|月底|周末)\\s*"
                    + "(?:凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里)?\\s*(?:之前|以前|前|截止)?");
    // 句首孤立的时段词（如"晚上交作业"）。单字"早/晚"不放进来，避免删掉"早自习"。
    static final Pattern P_CLEAN_LEAD = Pattern.compile(
            "^(?:凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里)\\s*(?:之前|以前|前|截止)?");
    static final Pattern P_CLEAN_PREFIX = Pattern.compile(
            "^((?:请务必|请|大家|同学们|各位同学|各位|全体同学|全体|同学|记得|务必|注意|于)\\s*)+");
    // 兜底标题取第一个标点子句（不含冒号，冒号前往往是"作业："这类前缀）
    static final Pattern P_FALLBACK_SPLIT = Pattern.compile("[，。；！？,;!?]");

    // ---------- 数据 ----------
    public static class Msg {
        public String id, groupId, groupName, sender, text, pkg;
        public long ts;
        public boolean timeUncertain;
        Msg(String id, String groupId, String groupName, String sender, String text, String pkg, long ts) {
            this.id = id; this.groupId = groupId; this.groupName = groupName;
            this.sender = sender; this.text = text; this.pkg = pkg; this.ts = ts;
        }
    }

    public static class Card {
        public String id, kind, title, channel, place, groupId, groupName, sender, sourceId, sourceText;
        public String sourcePackage;
        public boolean deadlineUserSet;

        public String deadlineHint() {
            if (deadlineUserSet) return null;
            return uncertainClassDeadline(blank(lastRevisionText) ? sourceText : lastRevisionText);
        }
        private static final Pattern PACKAGE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");
        String titleOverride;
        String lastRevisionText;
        public String bring;           // 考试卡专用：要带的东西（"带学生证和2B铅笔" → 学生证和2B铅笔）
        public String deadlineText = "待确认";
        public long deadline;          // 0 = 待确认
        public long sourceTs;          // 原消息时间，用来判断"刚才那条"是否还在 30 分钟内
        public String jielongKey;      // 接龙标题（去掉时间括注），同群同标题只保留一张卡
        public String platformKey;     // 学习平台通知：课程 + 事项名，同平台同键只保留一张卡
        public boolean needsConfirm, updated;
        public boolean sourceTimeUncertain;
        public boolean aiChecked;      // 上一级（大模型）已复核过
        public final TaskChanges changes = new TaskChanges();
        public boolean cancelled;
        public long lastChangeTs;
        public double confidence;
        public String reason = "";
        public List<String> context = new ArrayList<>();
        public boolean done;
        long removedAt;

        public String displayTitle() {
            return titleOverride == null ? title : titleOverride;
        }

        public String sourcePackageName() {
            if (!blank(sourcePackage)) return sourcePackage.trim();
            if (groupId == null) return "";
            int separator = groupId.indexOf('|');
            String candidate = separator < 0 ? groupId : groupId.substring(0, separator);
            if (candidate.indexOf('.') < 0) return "";
            return PACKAGE_NAME.matcher(candidate).matches() ? candidate : "";
        }

        public String sourceAppName() {
            switch (sourcePackageName()) {
                case "com.tencent.mm": return "微信";
                case "com.tencent.mobileqq": return "QQ";
                case "com.ss.android.lark": return "飞书";
                case "com.alibaba.android.rimet": return "钉钉";
                case "com.tencent.tim": return "TIM";
                case "com.chaoxing.mobile": return "学习通";
                case "com.able.wisdomtree": return "知到";
                case "com.xuetangx.ykt": return "雨课堂";
                case "com.nopalou.replay": return "测试通知";
                case "com.android.shell":
                case "android": return "系统通知";
                case "demo": return "演示样例";
                case "screenshot": return "截图识别";
                case "share": return "分享";
                case "jwxt": return "教务系统";
                default: return "来源待确认";
            }
        }

        public long calendarStart() {
            return deadline == 0 || "exam".equals(kind) ? deadline : deadline - 3600_000L;
        }
    }

    /**
     * 我的档案：用户在设置页填写，引擎只读。
     * 默认空档案，不内置任何人；演示身份只出现在 demo() 里。
     */
    public static class Profile {
        public String name;              // 本名，如"张三"
        public List<String> aliases;     // 别名/昵称
        public String studentId;         // 学号
        public String groupNo;           // 小组，如"第5组"
        public String className;         // 班级，如"软工2402"
        public List<String> courses;     // 我的课程

        public Profile() {
            aliases = new ArrayList<>();
            courses = new ArrayList<>();
        }

        public boolean isEmpty() {
            return blank(name) && blank(studentId) && blank(groupNo) && blank(className)
                    && clean(aliases).isEmpty() && clean(courses).isEmpty();
        }

        /** 文本格式：每行 "key<TAB>值"，值用 esc/unesc 转义，列表项一行一条。 */
        public String encode() {
            StringBuilder b = new StringBuilder();
            b.append("NAME\t").append(esc(name)).append('\n');
            b.append("SID\t").append(esc(studentId)).append('\n');
            b.append("GROUP\t").append(esc(groupNo)).append('\n');
            b.append("CLASS\t").append(esc(className)).append('\n');
            for (String a : nonNull(aliases)) b.append("ALIAS\t").append(esc(a)).append('\n');
            for (String c : nonNull(courses)) b.append("COURSE\t").append(esc(c)).append('\n');
            return b.toString();
        }

        /** 与 encode 对称；空串 / null 得到空档案，不抛异常。 */
        public static Profile decode(String s) {
            Profile p = new Profile();
            if (s == null) return p;
            for (String line : s.split("\n")) {
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                int t = line.indexOf('\t');
                if (t < 0) continue;
                String k = line.substring(0, t);
                String v = unesc(line.substring(t + 1));
                if ("NAME".equals(k)) p.name = v;
                else if ("SID".equals(k)) p.studentId = v;
                else if ("GROUP".equals(k)) p.groupNo = v;
                else if ("CLASS".equals(k)) p.className = v;
                else if ("ALIAS".equals(k)) p.aliases.add(v == null ? "" : v);
                else if ("COURSE".equals(k)) p.courses.add(v == null ? "" : v);
            }
            return p;
        }

        /** 演示用档案（只给演示脚本 / 演示模式）。 */
        public static Profile demo() {
            Profile p = new Profile();
            p.name = "李承宇"; p.aliases = new ArrayList<>(Arrays.asList("承宇", "小李", "李同学")); p.studentId = "20230137";
            p.groupNo = "第3组"; p.className = "计科2301"; p.courses = new ArrayList<>(Arrays.asList("数据库系统", "计算机网络"));
            return p;
        }
    }

    static class Deadline {
        long value; boolean hasTime; String conf; boolean expired;
        Deadline(long v, boolean h, String c) { value = v; hasTime = h; conf = c; }
        boolean ok() { return value > 0 && !"none".equals(conf); }
        static Deadline none() { return new Deadline(0, false, "none"); }
    }

    // ---------- 状态 ----------
    final double[] intercept = new double[4];
    final List<Card> cards = new ArrayList<>();
    private final List<Card> removedCards = new ArrayList<>();
    private long stateRevision;
    private long savedRevision;
    private String aiEpoch = java.util.UUID.randomUUID().toString();
    private boolean revisionAmbiguous;
    private final java.util.LinkedHashSet<String> notificationIds = new java.util.LinkedHashSet<>();
    boolean notificationSeen(String id) { return notificationIds.contains(id); }
    boolean markNotification(String id) {
        if (blank(id) || !notificationIds.add(id)) return false;
        while (notificationIds.size() > 8000) notificationIds.remove(notificationIds.iterator().next());
        return true; // 随下一次有效事项存档提交；纯闲聊无需单独整份写盘。
    }
    boolean acknowledgeImage(String id) {
        if(!markNotification(id))return false;
        notifyChanged(); return true; // 图片队列已提交，明确持久化完成账本。
    }
    boolean processNotification(Msg message, boolean platform) {
        if (notificationSeen(message.id)) return false;
        if (platform) processPlatform(message); else process(message);
        markNotification(message.id);
        return true;
    }
    long seq;                                                // 已发出的最大卡片编号，单调递增
    final Map<String, List<Pending>> candidates = new HashMap<>();
    final Map<String, long[]> senderStat = new HashMap<>();   // group|sender -> {total, notify}
    final Map<String, List<String>> recent = new HashMap<>();

    public int nIn, nNoise, nLevel3, nPromoted;
    public SelectedListener listener;
    Profile profile = new Profile();

    public interface SelectedListener { void onCardsChanged(); }

    /** 设置我的档案；null 当作空档案（不内置任何人）。 */
    public void setProfile(Profile p) {
        profile = p == null ? new Profile() : p;
    }

    public Profile getProfile() { return profile; }

    String aiEpoch() { return aiEpoch; }
    boolean acceptsAiEpoch(String epoch) { return epoch != null && aiEpoch.equals(epoch); }
    String profileKey() {
        try { return StateFormat.digest(profile.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    String groupVersion(String group) {
        GroupInfo g = groups.get(group);
        return g == null ? null : g.aiVersion;
    }
    boolean currentRequest(Escalation request) {
        if (request == null || request.msg == null || !acceptsAiEpoch(request.epoch)
                || !profileKey().equals(request.profileKey)
                || !Objects.equals(groupVersion(request.msg.groupId), request.scopeVersion)) return false;
        if (request.cardId == null) return !MODE_IGNORE.equals(groupMode(request.msg.groupId));
        Card c = cardById(request.cardId);
        return c != null && !c.done && Objects.equals(aiCardState(c), request.cardState);
    }

    // ---------- 档案读取（空串一律忽略，避免 contains("") 恒真）----------
    static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    static List<String> nonNull(List<String> v) { return v == null ? new ArrayList<String>() : v; }

    static List<String> clean(List<String> v) {
        List<String> out = new ArrayList<>();
        for (String s : nonNull(v)) if (!blank(s)) out.add(s.trim());
        return out;
    }

    List<String> meNames() {
        List<String> out = new ArrayList<>();
        if (!blank(profile.name)) out.add(profile.name.trim());
        out.addAll(clean(profile.aliases));
        return out;
    }

    String meSid() { return blank(profile.studentId) ? "" : profile.studentId.trim(); }
    String meGroup() { return blank(profile.groupNo) ? "" : profile.groupNo.trim(); }
    String meClass() { return blank(profile.className) ? "" : profile.className.trim(); }

    String[] meCourses() {
        List<String> c = clean(profile.courses);
        return c.toArray(new String[0]);
    }

    static String stripSpaces(String s) { return s == null ? "" : s.replace(" ", ""); }

    static class Pending {
        Msg m; boolean carded; int burst;
        Pending(Msg m) { this.m = m; }
    }

    public Engine(InputStream weightsTsv) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(weightsTsv, "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty()) continue;
            if (line.startsWith("#labels")) continue;
            if (line.startsWith("#intercept")) {
                String[] p = line.split("\t");
                String[] v = p[1].split(",");
                for (int i = 0; i < 4 && i < v.length; i++) intercept[i] = Double.parseDouble(v[i]);
                continue;
            }
            int t = line.lastIndexOf('\t');
            if (t <= 0) continue;
            String key = line.substring(0, t);
            String[] v = line.substring(t + 1).split(",");
            double[] w = new double[4];
            for (int i = 0; i < 4 && i < v.length; i++) w[i] = Double.parseDouble(v[i]);
            multi.put(key, w);
        }
        br.close();
    }

    final Map<String, double[]> multi = new HashMap<>();
    private Engine() { }

    // ---------- 特征 ----------
    static boolean containsAny(String s, String[] arr) {
        for (String a : arr) if (s.contains(a)) return true;
        return false;
    }

    static String stripPunct(String s) {
        return s.replaceAll("[\\s\\p{Punct}，。、；：！？…—·（）《》\"'“”‘’]", "");
    }

    public static boolean isNoise(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return true;
        String core = stripPunct(t);
        if (core.isEmpty()) return true;
        if (ACKS.contains(core)) return true;
        if (core.matches("[0-9+\\-]+")) return true;
        if (core.matches("(哈|h|H|2|3|笑|2333)+")) return true;
        if (t.contains("撤回了一条消息") || t.contains("撤回一条消息")) return true;
        return core.length() <= 2 && ("？?。…额哦嗯啊".contains(core));
    }

    double notifier(String gid, String sender) {
        long[] v = senderStat.get(gid + "|" + sender);
        if (v == null || v[0] < 3) return 0;
        return (v[1] / (double) v[0] >= 0.30) ? 1 : 0;
    }

    void observe(String gid, String sender, boolean notify) {
        long[] v = senderStat.get(gid + "|" + sender);
        if (v == null) { v = new long[]{0, 0}; senderStat.put(gid + "|" + sender, v); }
        v[0]++;
        if (notify) v[1]++;
    }

    Map<String, Double> featurize(Msg m) {
        Map<String, Double> f = new HashMap<>();
        String t = m.text;
        boolean atMe = false;
        for (String n : meNames()) if (t.contains(n)) atMe = true;
        if (t.contains("@我")) atMe = true;
        f.put("M:at_me", atMe ? 1.0 : 0.0);
        f.put("M:at_all", P_ATALL.matcher(t).find() ? 1.0 : 0.0);
        f.put("M:has_time", containsAny(t, TIME_HINTS) ? 1.0 : 0.0);
        f.put("M:has_action", containsAny(t, ACTION_WORDS) ? 1.0 : 0.0);
        f.put("M:has_link_file", containsAny(t, LINK_FILE) ? 1.0 : 0.0);
        f.put("M:sender_notifier", notifier(m.groupId, m.sender));
        boolean ident = containsAny(t, IDENTITY);
        String myGroup = meGroup();
        if (!myGroup.isEmpty() && t.contains(myGroup)) ident = true;
        String myClass = meClass();
        if (!myClass.isEmpty() && stripSpaces(t).contains(stripSpaces(myClass))) ident = true;
        for (String e : meCourses()) if (t.contains(e)) ident = true;
        f.put("M:has_identity", ident ? 1.0 : 0.0);
        f.put("M:reply_burst", 0.0);          // 线上判断当刻，未来的"收到"还没出现
        // 交互项
        if (f.get("M:has_time") > 0 && f.get("M:has_action") > 0) f.put("X:time_action", 1.0);
        if (f.get("M:has_time") > 0 && f.get("M:has_action") > 0 && f.get("M:sender_notifier") > 0)
            f.put("X:time_action_notifier", 1.0);
        if (atMe && f.get("M:has_action") > 0) f.put("X:atme_action", 1.0);
        // 文本 2-gram
        String s = stripPunct(t);
        for (int i = 0; i + 1 < s.length(); i++) f.put("T:" + s.substring(i, i + 2), 1.0);
        return f;
    }

    double[] scores(Map<String, Double> f) {
        double[] sc = intercept.clone();
        for (Map.Entry<String, Double> e : f.entrySet()) {
            double[] w = multi.get(e.getKey());
            if (w == null) continue;
            for (int i = 0; i < 4; i++) sc[i] += w[i] * e.getValue();
        }
        return sc;
    }

    static int argmax(double[] a) {
        int k = 0;
        for (int i = 1; i < a.length; i++) if (a[i] > a[k]) k = i;
        return k;
    }

    // ---------- 时间归一化 ----------
    /** 单个中文字符转数字；不是数字返回 -1。 */
    static int cnDigit(char c) {
        if (c == '〇') return 0;
        if (c == '两') return 2;
        return "零一二三四五六七八九".indexOf(c);
    }

    /** 中文数字转整数（"十"=10、"二十三"=23、"两"=2）；认不出返回 -1。 */
    static int cn2int(String s) {
        if (s == null || s.isEmpty()) return -1;
        if (s.matches("\\d+")) return Integer.parseInt(s);
        if (s.length() == 1) {
            if (s.charAt(0) == '十') return 10;
            return cnDigit(s.charAt(0));
        }
        int shi = s.indexOf('十');
        if (shi < 0) return -1;
        int hi = shi == 0 ? 1 : cnDigit(s.charAt(0));
        int lo = shi == s.length() - 1 ? 0 : cnDigit(s.charAt(s.length() - 1));
        if (hi < 0 || lo < 0) return -1;
        return hi * 10 + lo;
    }

    static long at(Calendar c, int hh, int mm) {
        Calendar x = (Calendar) c.clone();
        x.set(Calendar.HOUR_OF_DAY, hh);
        x.set(Calendar.MINUTE, mm);
        x.set(Calendar.SECOND, 0);
        x.set(Calendar.MILLISECOND, 0);
        return x.getTimeInMillis();
    }

    static long addDays(long ts, int days) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTimeInMillis();
    }

    static int weekday(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        int w = c.get(Calendar.DAY_OF_WEEK);   // 1=Sun
        return (w + 5) % 7;                     // 0=Mon
    }

    /** 钟点前面最近出现的时段词；没有就返回 null。 */
    static String periodBefore(String t, int pos) {
        int best = -1;
        String hit = null;
        String[][] sets = {PERIOD_PM, PERIOD_AM, PERIOD_NOON};
        for (String[] set : sets) {
            for (String w : set) {
                int i = t.lastIndexOf(w, Math.max(0, pos - 1));
                if (i < 0 || i + w.length() > pos) continue;
                if (i > best) { best = i; hit = w; }
            }
        }
        return hit;
    }

    /** 时段修正：晚/晚上→+12，"中午12点"→12:00，上午/早上不改。 */
    static int adjustHour(int hh, String period) {
        if (period == null) return hh;
        for (String w : PERIOD_PM) if (w.equals(period)) return hh < 12 ? hh + 12 : hh;
        for (String w : PERIOD_NOON) if (w.equals(period)) return hh < 11 ? 12 : hh;
        return hh;
    }

    /** 绝对日期；返回 {年(0=没写), 月, 日}，不是日期返回 null。 */
    static int[] absDate(String t) {
        int bestAt = -1;
        int[] hit = null;
        Matcher cn = P_ABS_CN.matcher(t);
        while (cn.find()) {
            int[] d = dateOf(cn);
            if (d != null) { bestAt = cn.start(); hit = d; break; }
        }
        Matcher num = P_ABS_NUM.matcher(t);
        while (num.find()) {
            if (isSectionNumber(t, num.start(), num.end())) continue;
            int[] d = dateOf(num);
            if (d != null) {
                if (bestAt < 0 || num.start() < bestAt) { hit = d; bestAt = num.start(); }
                break;
            }
        }
        return hit;
    }

    /**
     * "M.D" 紧挨着前面是"习题/第/练习/例"（中间的空格不算间隔），或紧挨着后面跟"页/题/章/节/小节"，
     * 说明是章节/页码编号而不是日期。
     */
    static boolean isSectionNumber(String t, int start, int end) {
        int i = start;
        while (i > 0 && Character.isWhitespace(t.charAt(i - 1))) i--;
        for (String w : MD_STOP_PRE) {
            int from = i - w.length();
            if (from >= 0 && t.regionMatches(from, w, 0, w.length())) return true;
        }
        int j = end;
        while (j < t.length() && Character.isWhitespace(t.charAt(j))) j++;
        for (String w : MD_STOP_POST) {
            if (t.regionMatches(j, w, 0, w.length())) return true;
        }
        return false;
    }

    static int[] dateOf(Matcher m) {
        int mo = Integer.parseInt(m.group(2)), dd = Integer.parseInt(m.group(3));
        if (mo < 1 || mo > 12 || dd < 1 || dd > 31) return null;
        int yy = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
        return new int[]{yy, mo, dd};
    }

    public static Deadline parseDeadline(String text, long send) {
        if (text == null || text.isEmpty()) return Deadline.none();
        String t = text.replace("：", ":");
        for (String v : VAGUE) if (t.contains(v)) return Deadline.none();

        boolean hasTime = false;
        boolean rollover = false;       // "24点/24:00" = 次日 0 点，日期要 +1 天
        int hh = -1, mm = 0;
        Matcher mc = P_CLOCK.matcher(t);
        if (mc.find()) {
            hh = Integer.parseInt(mc.group(1));
            mm = Integer.parseInt(mc.group(2));
            if (hh == 24 && mm == 0) {
                hh = 0; mm = 0; rollover = true;
            } else if (hh > 23 || mm > 59) {
                return Deadline.none();
            } else {
                hh = adjustHour(hh, periodBefore(t, mc.start()));
            }
            hasTime = true;
        } else {
            Matcher mh = P_HOUR.matcher(t);
            if (mh.find()) {
                int v = cn2int(mh.group(1));
                if (v < 0 || v > 24) return Deadline.none();
                if (mh.group(2) != null) {
                    mm = 30;
                } else if (mh.group(3) != null) {
                    mm = cn2int(mh.group(3));
                    if (mm < 0 || mm > 59) return Deadline.none();
                } else {
                    mm = 0;
                }
                if (v == 24) {
                    if (mm != 0) return Deadline.none();
                    hh = 0; mm = 0; rollover = true;
                } else {
                    hh = adjustHour(v, periodBefore(t, mh.start()));
                }
                hasTime = true;
            }
        }

        // "中午前/中午之前"没有具体钟点 → 当天 12:00
        if (!hasTime && P_NOON_BEFORE.matcher(t).find()) {
            hh = 12; mm = 0; hasTime = true;
        }

        long base = 0; boolean high = false;
        int[] ad = absDate(t);
        if(ad==null) {
            if(P_ABS_CN.matcher(t).find()) return Deadline.none();
            Matcher raw=P_ABS_NUM.matcher(t);
            while(raw.find()) if(!isSectionNumber(t,raw.start(),raw.end())) return Deadline.none();
        }
        if (ad != null) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(send);
            int year = ad[0] > 0 ? ad[0] : c.get(Calendar.YEAR);
            c.set(year, ad[1] - 1, ad[2], 0, 0, 0);
            c.set(Calendar.MILLISECOND, 0);
            // Calendar 会把 2 月 30 日顺延成 3 月 2 日，回读对不上就当没写日期
            if (c.get(Calendar.MONTH) == ad[1] - 1 && c.get(Calendar.DAY_OF_MONTH) == ad[2]) {
                // 只写月日、且比发送时间早 60 天以上 → 说的是明年
                if (ad[0] <= 0 && c.getTimeInMillis()
                        < sendCalendar(send).getTimeInMillis() - 60L * 86400000L) {
                    c.add(Calendar.YEAR, 1);
                }
                base = c.getTimeInMillis(); high = true;
            } else return Deadline.none();
        }
        if (base == 0) {
            Matcher md = P_DAYONLY.matcher(t);
            if (md.find()) {
                int dd = Integer.parseInt(md.group(2));
                if (dd >= 1 && dd <= 31) {
                    Calendar c = Calendar.getInstance();
                    c.setTimeInMillis(send);
                    int month = c.get(Calendar.MONTH);
                    int year = c.get(Calendar.YEAR);
                    c.set(year, month, dd, 0, 0, 0);
                    c.set(Calendar.MILLISECOND, 0);
                    if(c.get(Calendar.MONTH)!=month || c.get(Calendar.DAY_OF_MONTH)!=dd) return Deadline.none();
                    base = c.getTimeInMillis();
                    if (base < at(sendCalendar(send), 0, 0) && md.group(1) == null) {
                        month++;
                        if (month > 11) { month = 0; year++; }
                        c.set(year, month, dd, 0, 0, 0);
                        if(c.get(Calendar.MONTH)!=month || c.get(Calendar.DAY_OF_MONTH)!=dd) return Deadline.none();
                        base = c.getTimeInMillis();
                    }
                    high = true;
                }
            }
        }
        if (base == 0) {
            String[] words = {"大后天", "后天", "明天", "明日", "明晚", "明早", "今天", "今日", "今晚", "今早", "当天"};
            int[] deltas = {3, 2, 1, 1, 1, 1, 0, 0, 0, 0, 0};
            for (int i = 0; i < words.length; i++) {
                if (t.contains(words[i])) {
                    Calendar c = sendCalendar(send);
                    c.add(Calendar.DAY_OF_MONTH, deltas[i]);
                    base = at(c, 0, 0); high = true;
                    break;
                }
            }
        }
        if (base == 0) {
            Matcher mw = P_WEEK.matcher(t);
            if (mw.find()) {
                int wd = "一二三四五六日天七".indexOf(mw.group(2).charAt(0));
                if (wd == 6) wd = 6;
                if (wd >= 0) {
                    String pre = mw.group(1);
                    Calendar c = sendCalendar(send);
                    int cur = weekday(send);
                    int delta;
                    if ("下下".equals(pre)) delta = 14 - cur + wd;
                    else if ("下".equals(pre)) delta = 7 - cur + wd;
                    else if ("本".equals(pre) || "这".equals(pre)) delta = wd - cur;
                    else delta = (wd >= cur) ? (wd - cur) : (7 - cur + wd);
                    c.add(Calendar.DAY_OF_MONTH, delta);
                    base = at(c, 0, 0); high = true;
                }
            }
        }
        if (base == 0) {
            Matcher mw = P_WEEKEND.matcher(t);
            if (mw.find()) {
                int cur = weekday(send);
                int delta = ("下".equals(mw.group(1))) ? (6 - cur + 7) : (6 - cur);
                Calendar c = sendCalendar(send);
                c.add(Calendar.DAY_OF_MONTH, delta);
                base = at(c, 0, 0);
            }
        }
        if (base == 0) {
            Matcher mm2 = P_MONTHEND.matcher(t);
            if (mm2.find()) {
                Calendar c = sendCalendar(send);
                c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH));
                base = at(c, 0, 0);
            }
        }
        // "24点"是当天结束/次日 0 点：日期整体后移一天
        if (base != 0 && rollover) base = addDays(base, 1);
        if (base != 0 && !hasTime && t.contains("上课前")) {
            hh = 8; mm = 0; hasTime = true;
        }
        if (base == 0 && !hasTime) {
            Matcher duration = P_DURATION.matcher(t);
            if (!duration.find()) return Deadline.none();
            double amount = "半".equals(duration.group(1)) ? .5 : cn2int(duration.group(1));
            long unit = "小时".equals(duration.group(2)) ? 3600000L : 60000L;
            if (amount <= 0 || amount > 168 || duration.find()) return Deadline.none();
            return new Deadline(send + (long)(amount * unit), true, "high");
        }

        if (base == 0) {                    // 只有时刻
            if (rollover) {
                // "24点"只有时刻：直接按次日 0 点，不再做"已过就顺延"的判断
                return new Deadline(at(sendCalendar(addDays(send, 1)), 0, 0), true, "medium");
            }
            long cand = at(sendCalendar(send), hh, mm);
            if (cand <= send) cand = at(sendCalendar(addDays(send, 1)), hh, mm);
            return new Deadline(cand, true, "medium");
        }
        if (!hasTime) {
            long cand = at(sendCalendar(base), 23, 59);
            if (uncertainClassDeadline(t) != null) return new Deadline(cand, false, "low");
            return new Deadline(cand, false, high ? "high" : "medium");
        }
        long cand = at(sendCalendar(base), hh, mm);
        boolean expired = cand < send;
        return new Deadline(cand, true, expired ? "low" : (high ? "high" : "medium"));
    }

    static final Pattern P_CLASS_DEADLINE = Pattern.compile(
            "(?:(?:\\d{1,2}月\\d{1,2}[日号]?|今天|明天|后天|下周[一二三四五六日天])\\s*)?(?:下课前|放学前)");
    static final Pattern P_DURATION = Pattern.compile("(?<![\\d零一二两三四五六七八九十])([\\d]{1,3}|[一二两三四五六七八九十]{1,3}|半)\\s*(小时|分钟)\\s*(?:之内|内|后截止|后提交|后交)");

    static String uncertainClassDeadline(String text) {
        if (blank(text) || P_CLOCK.matcher(text).find() || P_HOUR.matcher(text).find()) return null;
        Matcher hint = P_CLASS_DEADLINE.matcher(text);
        return hint.find() ? hint.group().trim() : null;
    }

    static Calendar sendCalendar(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    // ---------- 抽取 ----------
    static String findChannel(String t) {
        for (String c : CHANNELS) if (t.contains(c)) return c;
        Matcher recipient = Pattern.compile("(?:交|提交|上交|发|发送)(?:给|至)\\s*([\\p{IsHan}]{0,6}?(?:组长|老师|助教|课代表|班长|辅导员|负责人))(?:处)?(?:[，。；！!\\s]|$)").matcher(t);
        if (recipient.find()) return recipient.group(1);
        Matcher m = Pattern.compile("(?:交|提交|上传|发)到\\s*([^\\s，。；、]{2,8})").matcher(t);
        if (m.find()) return m.group(1);
        return null;
    }

    static String findPlace(String t) {
        // "教三201""三教201"两种说法都认
        Matcher m = Pattern.compile("(教学楼|综合楼|理科楼|三教|二教|一教|四教|五教|教三|教二|教一|教四|教五|实验楼|图书馆|体育馆|报告厅|线上|腾讯会议)[^，。；\\n]{0,8}").matcher(t);
        if (m.find()) return m.group(0).trim();
        return null;
    }

    /**
     * 命中的考试词；没有返回 null。
     * 单独的"期中/期末"只在消息里没有提交动词时才算考试（"期末大作业…提交"仍是普通待办）。
     * 含准备类词（看完/看一下/看看/复习好/预习/背完）时说的是考前准备，不是考试通知，
     * 直接返回 null"没命中考试词"，让它走原来的普通待办流程（docs 补充规则第二条）。
     */
    static String examHit(String t) {
        if (t == null) return null;
        // 准备类词优先判断：命中就不算考试，和提交动词一样走普通待办
        if (containsAny(t, EXAM_PREP)) return null;
        for (String w : EXAM_WORDS) if (t.contains(w)) return w;
        if (containsAny(t, SUBMIT_WORDS)) return null;
        for (String w : TERM_WORDS) if (t.contains(w)) return w;
        return null;
    }

    /** 考试名称和时间分开显示，避免改期后标题仍留有原来的时刻。 */
    static String examTitle(String t, String hit) {
        for (String part : P_SPLIT.split(t)) {
            String s = part.trim();
            int index = s.indexOf(hit);
            if (index < 0) continue;
            String subject = P_ATALL.matcher(s.substring(0, index)).replaceAll(" ");
            for (Pattern pattern : new Pattern[]{P_ABS_CN, P_ABS_NUM, P_DAYONLY, P_WEEK, P_WEEKEND,
                    P_MONTHEND, P_CLOCK, P_HOUR, P_TITLE_TIME_WORDS}) {
                subject = pattern.matcher(subject).replaceAll(" ");
            }
            subject = subject.trim().replaceFirst("^(?:举行|进行|将于|定于|于)\\s*", "").trim();
            if (subject.length() + hit.length() > EXAM_TITLE_MAX) {
                subject = subject.substring(0, EXAM_TITLE_MAX - hit.length()).trim();
            }
            return subject + hit;
        }
        return hit;
    }

    /** 要带的东西："带学生证和2B铅笔" → 学生证和2B铅笔；没有就 null。 */
    static String findBring(String t) {
        Matcher m = P_BRING.matcher(t == null ? "" : t);
        if (!m.find()) return null;
        String s = m.group(1).trim();
        return s.isEmpty() ? null : s;
    }

    /** 去掉子句里的时间表达、称呼前缀、渠道后缀，供 findAction / 兜底标题共用。 */
    static String cleanTitle(String clause) {
        String s = clause == null ? "" : clause;
        if (s.startsWith("[有全体消息]")) s = s.substring("[有全体消息]".length()).trim();
        // 时段词 + 钟点（含"半"）+ 可选后缀"之前/以前/前/截止"，整段一起删
        s = P_CLEAN_HOUR.matcher(s).replaceAll("");
        // hh:mm 前面同样可能带时段词，后面同样可能带"之前/前/截止"
        s = P_CLEAN_CLOCK.matcher(s).replaceAll("");
        s = P_CLEAN_WEEKEND.matcher(s).replaceAll("");
        s = P_CLEAN_WEEK.matcher(s).replaceAll("");
        s = P_CLEAN_MONTH.matcher(s).replaceAll("");
        s = P_CLEAN_DAY.matcher(s).replaceAll("");
        s = P_CLEAN_LEAD.matcher(s).replaceAll("");
        s = s.replaceAll("(上课前|放学前|上课之前)", "");
        s = s.replaceAll("^[一二三四五六日天下本周这\\s]+", "");
        // 去掉渠道后缀
        s = s.replaceAll("(交|提交|上传|发)到[^，。；、]{0,12}", "");
        s = s.replaceAll("[，,]?\\s*(学习通|雨课堂|课程网站|QQ 群文件|邮箱|教务系统|课代表处|微信转账)[^，。；]{0,6}", "");
        s = P_CLEAN_PREFIX.matcher(s).replaceAll("");
        s = s.replaceAll("(啊|哈|呀|哦|啦|吧|喔|哟)+$", "");
        s = s.replaceAll("[，,、]+$", "");
        return s.trim();
    }

    /**
     * 返回 {事项短语, 命中的行动词}。
     * 按标点切子句，挑第一个含行动词且不像闲聊的子句，用 cleanTitle 去掉
     * 时间表达、称呼前缀和渠道后缀。清理后太短时，退到第一个非空子句
     * （它通常是"作业：…习题"这种事项名，时间在后面的子句里）。
     */
    static boolean deliveryOnly(String text) {
        return text != null && cleanTitle(text).matches(
                "(?:交|提交|上交|发|发送)(?:给|至)\\s*[\\p{IsHan}]{0,6}(?:组长|老师|助教|课代表|班长|辅导员|负责人)(?:处)?");
    }

    static boolean containsDelivery(String text) {
        if (text == null) return false;
        for (String clause : P_SPLIT.split(text)) if (deliveryOnly(clause)) return true;
        return false;
    }

    static String deliverySubject(List<String> clauses, int before) {
        for (int i = before - 1; i >= 0; i--) {
            String candidate = clauses.get(i);
            candidate = candidate.split("(?:请|务必|需|须|应)(?:于|在)", 2)[0];
            candidate = cleanTitle(candidate).replaceFirst("^[^\\p{L}\\p{N}（(]+", "").trim();
            if (containsAny(candidate, new String[]{"作业", "报告", "材料", "申请表", "登记表", "论文", "习题"})) {
                return candidate;
            }
        }
        return null;
    }

    static String[] findAction(String text) {
        if (text == null) return new String[]{null, null};
        List<String> clauses = new ArrayList<>();
        for (String c : P_SPLIT.split(text)) {
            c = c.trim();
            if (!c.isEmpty()) clauses.add(c);
        }
        String best = null, verb = null;
        int bestI = -1;
        for (int i = 0; i < clauses.size(); i++) {
            String clause = clauses.get(i);
            // 排除"我明天交"这类第一人称陈述（是讨论，不是给你的通知）
            if (clause.startsWith("我") || clause.startsWith("俺") || clause.startsWith("咱")) continue;
            String v = null;
            for (String vb : VERBS) if (clause.contains(vb)) { v = vb; break; }
            if (v == null) continue;
            best = clause; bestI = i; verb = v;
            break;
        }
        if (best == null) return new String[]{null, null};
        if (deliveryOnly(best)) return new String[]{deliverySubject(clauses, bestI), verb};
        String s = cleanTitle(best);
        if (s.length() < 3) {
            // 前一个子句（第一个非空子句，且不是被选中的那个）同样清理后当标题
            String prev = bestI > 0 ? cleanTitle(clauses.get(0)) : "";
            if (!prev.isEmpty()) return new String[]{prev, verb};
            // 没有可用前句：沿用旧办法（<2 才退回子句原文）
            if (s.length() < 2) return new String[]{best, verb};
        }
        return new String[]{s, verb};
    }

    static boolean isJielong(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (!t.contains("接龙")) return false;    // 只有编号行的作业清单不算接龙
        if (t.contains("#接龙")) return true;
        return P_NUMLINE.matcher(t).find();       // 光说"接龙"没名单的，不当接龙
    }

    String[] jielongInfo(String text) {   // {title, joined("1"/"0"), rosterSize}
        String title = null;
        for (String line : text.split("\\n")) {
            if (line.trim().isEmpty()) continue;
            if (line.trim().startsWith("#接龙")) {
                // "#接龙 周末聚餐，周五18点前报名"：标题写在同一行时，取标签后面第一个子句
                String rest = line.trim().substring("#接龙".length()).replaceAll("^[ ：:]+", "");
                rest = rest.split("[，,。；;（(]")[0].trim();
                if (!rest.isEmpty()) { title = rest; break; }
                continue;
            }
            Matcher mt = P_TITLE.matcher(line);
            title = mt.find() ? line.substring(0, mt.start()).trim() : line.trim();
            break;
        }
        if (title == null || title.isEmpty()) title = "未命名接龙";
        List<String> roster = new ArrayList<>();
        Matcher m = P_NUMLINE.matcher(text);
        while (m.find()) {
            String body = m.group(2).trim();
            String name = body.split("[\\s（(：:]")[0];
            if (!name.isEmpty()) roster.add(name);
        }
        List<String> names = meNames();
        String sid = meSid();
        boolean joined = false;
        for (String r : roster) {
            for (String n : names) if (r.contains(n)) joined = true;
            if (!sid.isEmpty() && r.contains(sid)) joined = true;
        }
        return new String[]{title, joined ? "1" : "0", String.valueOf(roster.size())};
    }

    // ---------- 主流程 ----------
    /** 是不是在提问：含"吗/？/?/吧？"，或者整句以"呢"收尾；句中的"呢"是语气词，不算提问。 */
    static boolean isQuestion(String text) {
        String t = text == null ? "" : text.trim();
        if (t.contains("吗") || t.contains("？") || t.contains("?") || t.contains("吧？")) return true;
        String u = t.replaceAll("[。！!~… ]+$", "");
        return u.endsWith("呢");
    }

    public String ruleLabel(Map<String, Double> f, String text, String sender) {
        boolean hasTime = f.get("M:has_time") > 0, hasAction = f.get("M:has_action") > 0;
        boolean atMe = f.get("M:at_me") > 0, atAll = f.get("M:at_all") > 0;
        boolean notifier = f.get("M:sender_notifier") > 0, teacher = containsAny(sender, TEACHER_HINTS);
        boolean asking = isQuestion(text);
        boolean firstPerson = text.trim().startsWith("我") || text.trim().startsWith("俺")
                || text.trim().startsWith("咱们");
        if (firstPerson && hasAction) return "discussion";
        if (hasAction && hasTime && !firstPerson && !asking) {
            if (atMe || atAll || notifier || teacher) return "action";
        }
        if (hasAction && teacher && !firstPerson && !asking && text.trim().length() <= 40) {
            if (containsAny(text, new String[]{"交", "提交", "填", "报名", "签到", "上传", "领"})) return "action";
        }
        return null;
    }

    private boolean splitting = false;   // 正在处理拆出来的子消息，防止再拆

    /** 一条消息里有两件以上各带动作、各带具体时间的事：拆开，各出一张卡（和 Python 版一致）。 */
    List<String> splitItems(String text, long ts) {
        List<String> items = new ArrayList<>();
        if (text == null || text.length() < 8 || text.contains("接龙")) return items;
        if (detectRevision(text) != null || examHit(text) != null) return items;
        for (String clause : text.split("[，,；;。\\n：:]")) {
            clause = clause.trim();
            if (clause.length() < 4) continue;
            if (findAction(clause)[1] == null) continue;
            if (!parseDeadline(clause, ts).ok()) continue;
            items.add(clause);
        }
        return items.size() >= 2 ? items : new ArrayList<String>();
    }

    public void process(Msg m) {
        if (m == null || !m.timeUncertain) { processCore(m); return; }
        Escalator saved = escalator;
        List<Escalation> pending = new ArrayList<>();
        escalator = pending::add;
        try {
            processCore(m);
            for (Card c : cards) {
                if (Objects.equals(c.sourceId, m.id) || (Objects.equals(c.groupId, m.groupId)
                        && c.lastChangeTs == m.ts && Objects.equals(c.lastRevisionText, m.text))) {
                    c.sourceTimeUncertain = true;
                    c.needsConfirm = true;
                    c.reason = "来源缺少可靠发送日期或时刻，请核对相对时间";
                    notifyChanged();
                }
            }
            if (saved != null) for (Escalation e : pending) {
                if (e.cardId != null) e.cardState = aiCardState(cardById(e.cardId));
                saved.escalate(e);
            }
        } finally { escalator = saved; }
    }

    private void processCore(Msg m) {
        if (!splitting) {
            // 按群管理：登记这个群；用户设成“不用管”的群直接跳过，不出卡、不参与上下文。
            GroupInfo gi = touchGroup(m.groupId, m.groupName, m.pkg, m.ts);
            if (gi != null && MODE_IGNORE.equals(gi.mode)) {
                nIn++;
                nNoise++;
                return;
            }
        }
        if (!splitting && m.text != null) {
            List<String> items = splitItems(m.text, m.ts);
            if (items.size() >= 2) {
                splitting = true;
                try {
                    for (int i = 0; i < items.size(); i++) {
                        process(new Msg(m.id + "#" + (i + 1), m.groupId, m.groupName, m.sender,
                                items.get(i), m.pkg, m.ts));
                    }
                } finally {
                    splitting = false;
                }
                return;
            }
        }
        nIn++;
        String t = m.text == null ? "" : m.text;
        if (isNoise(t)) {
            nNoise++;
            observe(m.groupId, m.sender, false);
            remember(m);
            if (ACKS.contains(t.trim())) trackAck(m);
            return;
        }

        // ---------- 重复推送去重 ----------
        // 同一群里若已有某张卡的原文（去空格后）与本条消息完全相同，说明是同一条消息
        // 被重复推送：直接丢弃，不出新卡、不更新已有卡、也不 remember。
        // 可恢复的移除保留去重依据，防止同一通知重发后立即回到首页。
        String flatText = stripSpaces(t);
        if (isDuplicate(cards, m, flatText) || isDuplicate(removedCards, m, flatText)) return;

        Map<String, Double> f = featurize(m);
        double[] sc = scores(f);
        int idx = argmax(sc);
        String label = LABELS[idx];
        String rule = ruleLabel(f, t, m.sender);
        if (rule != null) label = rule;
        double prob = rule != null ? 1.0 : topProb(sc);
        // 提问不是待办（"周五之前交实验报告吗"）：统计模型误判成待办时在这里拦下
        if ("action".equals(label) && isQuestion(t)) label = "discussion";
        // 重点关注的群：带动作和时间的“讨论/闲聊”也当待办（提问除外），宁可多出一张让用户确认。
        boolean focus = MODE_FOCUS.equals(groupMode(m.groupId));
        if (focus && ("discussion".equals(label) || "chatter".equals(label))
                && f.get("M:has_action") > 0 && f.get("M:has_time") > 0 && !isQuestion(t)) {
            label = "action";
        }
        observe(m.groupId, m.sender, "action".equals(label) || "notice".equals(label));

        double rel = relevance(t, m.groupName);
        String rev = detectRevision(t);

        // 考试变更先按考试名称定位，避免通用待办的关键词把同群另一场考试选中。
        String examWord = examHit(t);
        String who = m.sender == null ? "" : m.sender;
        if ((rev != null || (examWord != null && containsAny(t, RESCHEDULE_WORDS)))
                && (containsAny(who, TEACHER_HINTS) || focus || P_ATALL.matcher(t).find())
                && !t.trim().matches("^(?:我|俺|咱).*")) {
            GroupInfo changed = groups.get(m.groupId);
            if (changed != null) { changed.aiVersion = java.util.UUID.randomUUID().toString(); stateRevision++; }
        }
        if (examWord != null && (containsAny(t, RESCHEDULE_WORDS)
                || (rev != null && containsAny(t, EXAM_WORDS)))) {
            applyExamRevision(m, t, examWord, rev);
            remember(m);
            return;
        }

        // 问句里出现撤销/更正词：既不算更正，也不能顺势变成一张新待办
        if (rev == null && revisionAsking(t) && revisionLike(t)) {
            remember(m);
            return;
        }

        if (rev != null) {
            // 只命中自然改期词（"延期/推迟/顺延/提前"）时，可能只是调课通知：
            // 只拿卡片标题对目标，也不走"之前/刚才"指代兜底；对不上就继续走正常流程。
            boolean naturalOnly = "supersede".equals(rev) && naturalRevisionOnly(t);
            Card target = revisionTarget(m, t, naturalOnly);
            if (revisionAmbiguous) { remember(m); return; }
            if (target != null) {
                if (m.ts < Math.max(target.sourceTs, target.lastChangeTs)) {
                    target.changes.record(m, "旧变更早于当前版本，已忽略"); notifyChanged(); remember(m); return;
                }
                if ("revoke".equals(rev)) {
                    archiveCancellation(target, m);
                    notifyChanged();
                    remember(m);
                    return;
                }
                Deadline d = parseDeadline(t, m.ts);
                if (d.ok()) {
                    target.changes.record(m, "截止时间调整为 " + absoluteTime(d.value));
                    target.changes.removeOlder(m.ts);
                    target.lastChangeTs = Math.max(target.lastChangeTs, m.ts);
                    target.deadline = d.value;
                    target.deadlineText = humanDeadline(d.value);
                    target.needsConfirm = "low".equals(d.conf);
                    target.deadlineUserSet = false;
                    target.updated = true;
                    target.lastRevisionText = t;
                    target.reason = target.needsConfirm ? TIME_UNCERTAIN : "";
                    notifyChanged();
                    remember(m);
                    return;
                }
                remember(m);
                return;
            }
            // 对不上任何卡：撤销/更正只对"说得清指的是哪条"的消息生效，
            // 不能顺势把这句"作废/更正"当成新待办再建一张卡
            if (!naturalOnly) {
                remember(m);
                return;
            }
        }

        if (isJielong(t)) {
            String[] jl = jielongInfo(t);
            Card same = null;
            for (Card c : cards) {
                if ("jielong".equals(c.kind) && m.groupId != null && m.groupId.equals(c.groupId)
                        && jl[0] != null && jl[0].equals(c.jielongKey)) { same = c; break; }
            }
            if ("1".equals(jl[1])) {
                if (same != null) {
                    cards.remove(same);
                    notifyChanged();
                }
                remember(m);
                return;          // 已接过：撤掉提醒卡，既不是待办也不是闲聊
            }
            Deadline d = parseDeadline(t, m.ts);
            if (same != null) {
                same.title = "接龙：" + jl[0] + "（你还没接）";
                same.reason = jl[2] + " 人已接，名单里没有你";
                same.sourceText = t;
                same.sourceId = m.id;
                same.sourceTs = m.ts;
                if (d.ok()) {
                    same.deadline = d.value;
                    same.deadlineText = humanDeadline(d.value);
                    same.needsConfirm = "low".equals(d.conf);
                    same.deadlineUserSet = false;
                }
                notifyChanged();
                remember(m);
                return;          // 同群同标题只更新原来那张卡
            }
            Card c = new Card();
            c.id = newCardId();
            c.kind = "jielong";
            c.title = "接龙：" + jl[0] + "（你还没接）";
            c.jielongKey = jl[0];
            c.deadline = d.ok() ? d.value : 0;
            c.deadlineText = humanDeadline(c.deadline);
            c.needsConfirm = c.deadline == 0 || "low".equals(d.conf);
            c.channel = "群接龙";
            c.sourcePackage = m.pkg;
            c.groupId = m.groupId;
            c.groupName = m.groupName;
            c.sender = m.sender;
            c.sourceId = m.id;
            c.sourceText = t;
            c.sourceTs = m.ts;
            c.reason = jl[2] + " 人已接，名单里没有你";
            c.confidence = 0.95;
            c.context = new ArrayList<>(recent.getOrDefault(m.groupId, new ArrayList<>()));
            cards.add(c);
            remember(m);
            notifyChanged();
            return;
        }

        // ---------- 考试/日程卡（规则见 docs/考试卡规则.md）----------
        // 放在"接龙之后、按分类标签分流之前"：分类器判成通知/闲聊的考试通知也照样出卡
        // （下面 notice 分支会直接丢消息），且不再另出一张 todo 卡。
        if (examWord != null) {
            Deadline ed = parseDeadline(t, m.ts);
            String sender = m.sender == null ? "" : m.sender;
            boolean trusted = focus || containsAny(sender, TEACHER_HINTS) || P_ATALL.matcher(t).find();
            boolean ask = containsAny(t, ASK_WORDS);
            boolean firstPerson = t.trim().startsWith("我") || t.trim().startsWith("俺")
                    || t.trim().startsWith("咱们");
            if (ed.ok() && trusted && !ask && !firstPerson) {
                Card c = new Card();
                c.id = newCardId();
                c.kind = "exam";
                c.title = examTitle(t, examWord);
                c.deadline = ed.value;
                c.deadlineText = humanDeadline(ed.value);
                c.needsConfirm = "low".equals(ed.conf);       // 置信度低才要用户核对
                c.channel = findChannel(t);
                c.place = findPlace(t);
                c.bring = findBring(t);
                c.sourcePackage = m.pkg;
                c.groupId = m.groupId;
                c.groupName = m.groupName;
                c.sender = m.sender;
                c.sourceId = m.id;
                c.sourceText = t;
                c.sourceTs = m.ts;
                c.confidence = 0.85;
                c.reason = c.needsConfirm ? TIME_UNCERTAIN : "";
                c.context = new ArrayList<>(recent.getOrDefault(m.groupId, new ArrayList<>()));
                cards.add(c);
                remember(m);
                notifyChanged();
                if (c.needsConfirm) escalateCard(c, m, "exam_time");
                return;
            }
            // 提问 / 第一人称 / 不可信 / 时间含糊（"另行通知"）：考试类消息不出卡
            // 可信来源但时间没解析出来的考试通知，交给上一级再看一次。
            if (trusted && !ask && !firstPerson) escalateMessage(m, "exam", prob, "exam_unparsed");
            remember(m);
            return;
        }

        if ("discussion".equals(label) || "chatter".equals(label)) {
            if (f.get("M:has_action") > 0 && f.get("M:has_time") > 0) {
                List<Pending> group = candidates.computeIfAbsent(m.groupId, key -> new ArrayList<>());
                group.removeIf(p -> m.ts - p.m.ts > BURST_WINDOW_MS);
                group.add(new Pending(m));
            }
            if (rule == null && shouldReview(f, prob, t)) escalateMessage(m, label, prob, "low_conf");
            remember(m);
            return;
        }

        nLevel3++;
        Deadline d = parseDeadline(t, m.ts);
        if ("notice".equals(label)) {
            if (rule == null && shouldReview(f, prob, t)) escalateMessage(m, label, prob, "low_conf");
            remember(m);
            return;                      // ②类只出信息卡，不占待办位
        }
        boolean keepGroup = focus || containsAny(m.sender == null ? "" : m.sender, TEACHER_HINTS)
                || P_ATALL.matcher(t).find();
        if (rel <= 0.4 && m.groupName.contains("课") && !keepGroup
                && !containsAny(m.groupName, meCourses())) {
            remember(m);
            return;                      // 与我无关的课程群广播不打扰
        }
        String[] act = findAction(t);
        Card c = new Card();
        c.id = newCardId();
        c.kind = "todo";
        boolean missingSubject = act[0] == null && containsDelivery(t);
        c.title = missingSubject ? "待确认：提交事项" : act[0] != null ? act[0] : fallbackTitle(t);
        c.deadline = d.ok() ? d.value : 0;
        c.deadlineText = humanDeadline(c.deadline);
        c.needsConfirm = missingSubject || !d.ok() || "low".equals(d.conf);
        c.channel = findChannel(t);
        c.place = findPlace(t);
        c.sourcePackage = m.pkg;
        c.groupId = m.groupId;
        c.groupName = m.groupName;
        c.sender = m.sender;
        c.sourceId = m.id;
        c.sourceText = t;
        c.sourceTs = m.ts;
        c.confidence = 0.8;
        c.reason = missingSubject ? "原消息只写了提交对象，未说明要提交什么" : c.needsConfirm ? TIME_UNCERTAIN : "";
        c.context = new ArrayList<>(recent.getOrDefault(m.groupId, new ArrayList<>()));
        cards.add(c);
        remember(m);
        notifyChanged();
        // 规则抽取没把握（缺事项、缺时间、时间含糊）或分类本身拿不准：交给上一级补全/复核。
        if (c.needsConfirm || prob < REVIEW_PROB) escalateCard(c, m, c.needsConfirm ? "extract" : "low_conf");
    }

    /**
     * 学习平台（学习通 / 知到 / 雨课堂）自己发的通知：不走群聊分类和发送者信任，
     * 按模板解析出事项后直接出卡；同平台同课程同事项名只保留一张，改期就更新、撤回就删掉。
     *
     * @return true 表示这条通知已处理（包括判定为不是待办而忽略）
     */
    public boolean processPlatform(Msg m) {
        String app = PlatformNotice.appName(m.pkg);
        if (app == null) return false;
        // 学习平台按整个 App 管理：设成“不用管”就不再出卡。
        GroupInfo gi = touchGroup(m.pkg + "|" + app, app, m.pkg, m.ts);
        nIn++;
        if (gi != null && MODE_IGNORE.equals(gi.mode)) {
            nNoise++;
            return true;
        }
        PlatformNotice.Notice n = PlatformNotice.parse(m.pkg, m.groupName, m.text, m.ts);
        if (n == null) {
            nNoise++;
            return true;
        }
        nLevel3++;
        // 用户移除过的同一件事：平台重复推送时不再顶回首页。
        for (Card r : removedCards) {
            if (m.pkg.equals(r.sourcePackage) && n.key.equals(r.platformKey) && !PlatformNotice.UPDATE.equals(n.action)) return true;
        }
        Card same = null;
        for (Card c : cards) {
            if (m.pkg.equals(c.sourcePackage) && n.key.equals(c.platformKey)) { same = c; break; }
        }
        if (PlatformNotice.REVOKE.equals(n.action)) {
            if (same != null) {
                cards.remove(same);
                notifyChanged();
            }
            return true;
        }
        if (same != null) {
            boolean changed = false;
            if (n.deadline > 0 && n.deadline != same.deadline && !same.deadlineUserSet) {
                same.deadline = n.deadline;
                same.deadlineText = humanDeadline(n.deadline);
                same.needsConfirm = !n.timeSure;
                same.reason = same.needsConfirm ? TIME_UNCERTAIN : "";
                same.updated = true;
                same.lastRevisionText = n.raw;
                changed = true;
            }
            if (changed) notifyChanged();
            return true;          // 同一件事的重复推送：有新时间就改期，没有就忽略
        }
        Card c = new Card();
        c.id = newCardId();
        c.kind = n.kind;
        c.title = n.title;
        c.platformKey = n.key;
        c.deadline = n.deadline;
        c.deadlineText = humanDeadline(n.deadline);
        c.needsConfirm = n.deadline == 0 || !n.timeSure;
        boolean signin = PlatformNotice.KIND_SIGNIN.equals(n.kind), grade = PlatformNotice.KIND_GRADE.equals(n.kind);
        c.channel = "exam".equals(n.kind) || signin || grade ? null : app;
        c.sourcePackage = m.pkg;
        c.groupId = m.pkg + "|" + (n.course.isEmpty() ? app : n.course);
        c.groupName = n.course.isEmpty() ? app : n.course;
        c.sender = "平台通知";
        c.sourceId = m.id;
        c.sourceText = n.raw;
        c.sourceTs = m.ts;
        c.confidence = 0.9;
        c.reason = signin ? "尽快到" + app + "里签到"
                : grade ? "到" + app + "里看一下成绩，看过点完成"
                : n.deadline == 0 ? "通知里没有写截止时间，请到" + app + "里查看"
                : c.needsConfirm ? TIME_UNCERTAIN : "";
        c.context = new ArrayList<>();
        cards.add(c);
        notifyChanged();
        return true;
    }

    static String detectRevision(String t) {
        // 问句（"不用交了吗"）是在打听，不是在下指令
        if (revisionAsking(t)) return null;
        for (String w : REVOKE_WORDS) if (t.contains(w)) return "revoke";
        for (String w : REVISE_WORDS) if (t.contains(w)) return "supersede";
        // 自然改期词只有对得上某张卡时才算更正（见 process）
        for (String w : NATURAL_REVISE_WORDS) if (t.contains(w)) return "supersede";
        return null;
    }

    /** 消息里有提问词：在问，不是在下达更正/撤销。 */
    static boolean revisionAsking(String t) {
        return containsAny(t, REVISION_ASK_WORDS);
    }

    /** 消息里有任何撤销/更正词（含自然改期词）。 */
    static boolean revisionLike(String t) {
        return containsAny(t, REVOKE_WORDS) || containsAny(t, REVISE_WORDS) || containsAny(t, NATURAL_REVISE_WORDS);
    }

    /** 只命中自然改期词、没命中原七个更正词：可能是调课通知，需先找到目标卡。 */
    static boolean naturalRevisionOnly(String t) {
        return !containsAny(t, REVISE_WORDS) && containsAny(t, NATURAL_REVISE_WORDS);
    }

    Card latestCard(String gid) {
        for (int i = cards.size() - 1; i >= 0; i--) if (gid.equals(cards.get(i).groupId)) return cards.get(i);
        return null;
    }

    /** 从考试词前取名称，略过通知开头的格式词。 */
    private static String examReference(String text, String hit) {
        text = P_ATALL.matcher(text).replaceAll(" ");
        String reference = hit;
        for (String part : text.split("[，,。；;\\n！!？?：:]")) {
            int index = part.indexOf(hit);
            if (index < 0) continue;
            String prefix = part.substring(0, index).trim().replaceFirst(
                    "^(?:@\\s*(?:所有人|全体)\\s*|(?:关于|更正(?:一下)?|通知|提醒|取消|作废|撤销(?:该)?|撤回(?:该)?|忽略|原定的?|这场|那场)\\s*)+", "");
            String name = stripSpaces(prefix + hit).toLowerCase(Locale.ROOT);
            if (name.length() > reference.length()) reference = name;
        }
        return reference;
    }

    private void applyExamRevision(Msg m, String text, String examWord, String revision) {
        String trimmed = text.trim();
        if (m.groupId == null || containsAny(text, ASK_WORDS)
                || trimmed.startsWith("我") || trimmed.startsWith("俺") || trimmed.startsWith("咱")) return;
        String reference = examReference(text, examWord);
        Card target = null;
        for (Card card : cards) {
            if (!"exam".equals(card.kind) || !m.groupId.equals(card.groupId) || card.sourceText == null) continue;
            if (!stripSpaces(card.sourceText).toLowerCase(Locale.ROOT).contains(reference)) continue;
            if (target != null) return; // 同一群内仍有多个匹配的考试，等待明确通知。
            target = card;
        }
        if (target == null) return;
        if (m.ts < Math.max(target.sourceTs, target.lastChangeTs)) {
            target.changes.record(m, "旧考试变更早于当前版本，已忽略"); notifyChanged(); return;
        }
        String sender = m.sender == null ? "" : m.sender;
        if (!containsAny(sender, TEACHER_HINTS) && !P_ATALL.matcher(text).find()
                && (sender.isEmpty() || !sender.equals(target.sender))) return;
        if ("revoke".equals(revision)) {
            archiveCancellation(target, m);
        } else {
            Matcher change = P_EXAM_NEW_TIME.matcher(text);
            int start = 0;
            while (change.find()) start = change.end();
            Deadline deadline = parseDeadline(text.substring(start), m.ts);
            if (!deadline.ok()) return;
            target.changes.record(m, "考试时间调整为 " + absoluteTime(deadline.value));
            target.changes.removeOlder(m.ts);
            target.lastChangeTs = Math.max(target.lastChangeTs, m.ts);
            target.deadline = deadline.value;
            target.deadlineUserSet = false;
            target.deadlineText = humanDeadline(deadline.value);
            target.needsConfirm = "low".equals(deadline.conf);
            target.updated = true;
            target.lastRevisionText = text;
        }
        notifyChanged();
    }

    /** 撤销/更正专用来词：出现在任何卡里都说明不了指向哪一条，匹配时排除。 */
    static final String[] REVISION_STOP = {"刚才", "上条", "之前", "那条", "截止", "更正",
            "取消", "作废", "改成", "改为", "一下", "今晚", "明天", "大家", "上课", "下周",
            "腾讯", "会议", "教室", "下次", "请相", "相互", "互转", "转告"};
    static final Pattern P_GENERIC_HOMEWORK_REVISION = Pattern.compile(
            "(?:^|[，,。；;\\s])(?:这次|本次|此次)?作业\\s*(?:截止(?:时间)?|提交时间|时间)?\\s*"
                    + "(?:改到|改为|改成|调整为|延期到|推迟到|顺延到|提前到)");
    static final String[] HOMEWORK_SUBJECTS = {"作业", "实验报告", "预习报告", "课后习题",
            "读书报告", "读书笔记", "读书心得"};

    /** "刚才"类指代词：只认 30 分钟内发的那条。 */
    static final String[] REVISION_PRONOUN_NEAR = {"刚才", "刚刚", "那条"};
    /** "之前"类指代词：原发送者本人说时不限时间，直接取此人在本群最新的一张卡。 */
    static final String[] REVISION_PRONOUN_FAR = {"之前", "上条", "上面", "前面"};

    /**
     * 找这条撤销/更正消息真正指向的那张卡：只在同一群里、从新到旧找。
     * 发送者要是原发送者或老师一类的人，且消息对得上事项（共同中文词）；
     * 指代词分两类："刚才"类只认 30 分钟内发的，"之前"类由原发送者本人说时不限时间，
     * 取此人在本群最新的一张卡。对不上就返回 null，调用方因此什么都不做。
     */
    Card revisionTarget(Msg m, String t) {
        return revisionTarget(m, t, false);
    }

    /** titleOnly 为真时只拿卡片标题对目标，不走"之前/刚才"指代兜底（自然改期词用，防调课通知误指）。 */
    Card revisionTarget(Msg m, String t, boolean titleOnly) {
        revisionAmbiguous = false;
        t = P_ATALL.matcher(t).replaceAll(" ");
        String sender = m.sender == null ? "" : m.sender;
        String gid = m.groupId == null ? "" : m.groupId;
        boolean trusted = containsAny(sender, TEACHER_HINTS);
        boolean near = containsAny(t, REVISION_PRONOUN_NEAR);
        boolean far = containsAny(t, REVISION_PRONOUN_FAR);
        Set<String> keys = new HashSet<>();
        for (int i = 0; i + 1 < t.length(); i++) {
            char a = t.charAt(i), b = t.charAt(i + 1);
            if (a < 0x4E00 || a > 0x9FFF || b < 0x4E00 || b > 0x9FFF) continue;
            String g = t.substring(i, i + 2);
            boolean stop = false;
            for (String s : REVISION_STOP) if (s.equals(g)) { stop = true; break; }
            if (!stop) keys.add(g);
        }
        Card best = null;
        int bestScore = 0;
        boolean tied = false;
        List<Card> bestMatches = new ArrayList<>();
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card c = cards.get(i);
            if (!gid.equals(c.groupId) || c.done) continue;
            if (!trusted && !sender.equals(c.sender)) continue;
            String blob = c.title == null ? "" : c.title;
            int score = 0;
            for (String g : keys) {
                if (blob.contains(g)) score++;
            }
            if (score > bestScore) {
                best = c;
                bestScore = score;
                tied = false;
                bestMatches.clear();
                bestMatches.add(c);
            } else if (score > 0 && score == bestScore) {
                tied = true;
                bestMatches.add(c);
            }
        }
        if (best != null) return tied ? deferRevision(m, t, bestMatches) : best;
        // 泛称只接受同一发送者、最近一周内唯一的一份作业，不能靠“最新一张”猜。
        if (P_GENERIC_HOMEWORK_REVISION.matcher(t).find() && !blank(sender)) {
            List<Card> homework = new ArrayList<>();
            for (Card c : cards) {
                long age = m.ts - c.sourceTs;
                if (!gid.equals(c.groupId) || !sender.equals(c.sender) || c.done
                        || !"todo".equals(c.kind) || age < 0 || age > 7 * 86400_000L
                        || !containsAny(c.sourceText == null ? "" : c.sourceText, HOMEWORK_SUBJECTS)) continue;
                homework.add(c);
            }
            if (homework.size() > 1) return deferRevision(m, t, homework);
            if (homework.size() == 1) return homework.get(0);
        }
        if (titleOnly) return null;
        List<Card> sourceMatches = new ArrayList<>();
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card c = cards.get(i);
            if (c.done || !gid.equals(c.groupId) || (!trusted && !sender.equals(c.sender))) continue;
            if (c.sourceText != null) {
                boolean matches = false;
                for (String key : keys) if (c.sourceText.contains(key)) { matches = true; break; }
                if (matches) {
                    sourceMatches.add(c);
                }
            }
        }
        if (sourceMatches.size() > 1) return deferRevision(m, t, sourceMatches);
        if (sourceMatches.size() == 1) return sourceMatches.get(0);
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card c = cards.get(i);
            if (!gid.equals(c.groupId) || (!trusted && !sender.equals(c.sender))) continue;
            if (near) {                              // "刚才"类：30 分钟内
                long dt = m.ts - c.sourceTs;
                if (dt >= 0 && dt <= 30 * 60_000L) return c;
            }
            if (far && sender.equals(c.sender)) return c;
        }
        return null;
    }

    /** 保留原时间并暂停确定时间提醒，让用户看见待核对原因。 */
    private Card deferRevision(Msg message, String text, List<Card> matches) {
        revisionAmbiguous = true;
        for (Card c : matches) {
            c.changes.defer(message, c.needsConfirm);
            c.needsConfirm = true;
            c.reason = "群里有变更，但对应事项不明确，请核对：" + clip(text, 80);
        }
        notifyChanged();
        return null;
    }

    private static String absoluteTime(long time) {
        return time <= 0 ? "待确认" : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new java.util.Date(time));
    }
    private void archiveCancellation(Card card, Msg source) {
        card.changes.record(source, "来源通知取消了该事项");
        card.cancelled = true; card.removedAt = Math.max(1, source.ts);
        card.lastRevisionText = source.text; card.lastChangeTs = Math.max(card.lastChangeTs, source.ts);
        cards.remove(card);
        if (!removedCards.contains(card)) removedCards.add(card);
    }
    public boolean resolvePendingChange(Card selected, String messageId) {
        return resolvePendingChange(selected, messageId, System.currentTimeMillis());
    }
    public boolean resolvePendingChange(Card selected, String messageId, long actedAt) {
        if (selected == null || actedAt <= 0 || !cards.contains(selected)) return false;
        Msg change = selected.changes.find(messageId);
        if (change == null || !Objects.equals(change.groupId, selected.groupId)) return false;
        String revision = detectRevision(change.text);
        Deadline deadline = parseDeadline(change.text, change.ts);
        if (!"revoke".equals(revision) && !deadline.ok()) return false;
        for (Card c : new ArrayList<>(cards)) {
            if (!Objects.equals(c.groupId, change.groupId) || c.changes.find(messageId) == null) continue;
            c.changes.remove(messageId);
            if(c!=selected)c.changes.action(actedAt,"本人","变更已关联到「"+selected.displayTitle()+"」，本项保留原安排",change.text);
            if (!c.changes.hasPending()) {
                c.needsConfirm = c.deadline == 0 || (!c.deadlineUserSet && c.changes.baseNeedsConfirm);
                c.reason = c.needsConfirm ? TIME_UNCERTAIN : "";
            }
        }
        if (change.ts < Math.max(selected.sourceTs,selected.lastChangeTs)) selected.changes.action(actedAt,"本人","变更早于当前版本，保留当前时间",change.text);
        else if ("revoke".equals(revision)) {
            selected.changes.action(actedAt,"本人","确认这条取消通知对应本事项",change.text);
            archiveCancellation(selected,change);selected.removedAt=actedAt;
        }
        else {
            selected.changes.action(actedAt,"本人", "确认对应事项，时间调整为 " + absoluteTime(deadline.value),change.text);
            selected.deadline = deadline.value; selected.deadlineText = humanDeadline(deadline.value);
            selected.sourceTimeUncertain = change.timeUncertain;
            selected.needsConfirm = change.timeUncertain || "low".equals(deadline.conf) || selected.changes.hasPending();
            selected.deadlineUserSet = false; selected.updated = true;
            selected.lastRevisionText = change.text; selected.lastChangeTs = change.ts;
            selected.reason = selected.needsConfirm ? TIME_UNCERTAIN : "";
        }
        notifyChanged(); return true;
    }
    public boolean ignorePendingChange(Card selected, String messageId) {
        return ignorePendingChange(selected,messageId,System.currentTimeMillis());
    }
    public boolean ignorePendingChange(Card selected, String messageId, long actedAt) {
        if (selected == null || actedAt <= 0 || !cards.contains(selected) || selected.changes.find(messageId) == null) return false;
        Msg change = selected.changes.find(messageId);
        for (Card c : cards) if (Objects.equals(c.groupId, change.groupId) && c.changes.find(messageId) != null) {
            c.changes.remove(messageId);
            c.changes.action(actedAt,"本人","忽略这条变更，保留当前安排",change.text);
            if (!c.changes.hasPending()) { c.needsConfirm = c.deadline == 0 || (!c.deadlineUserSet && c.changes.baseNeedsConfirm); c.reason = c.needsConfirm ? TIME_UNCERTAIN : ""; }
        }
        notifyChanged(); return true;
    }

    void trackAck(Msg m) {
        List<Pending> group = candidates.get(m.groupId);
        if (group == null) return;
        group.removeIf(p -> m.ts - p.m.ts > BURST_WINDOW_MS);
        for (Pending p : group) {
            if (m.ts <= p.m.ts) continue;
            p.burst++;
            if (p.burst >= BURST_PROMOTE) promote(p);
        }
        group.removeIf(p -> p.carded);
        if (group.isEmpty()) candidates.remove(m.groupId);
    }

    void promote(Pending p) {
        // 护栏：第一人称陈述、反问、"停课/取消"这类，回复潮再大也不是待办
        String t = p.m.text.trim();
        if (t.startsWith("我") || t.startsWith("俺") || t.startsWith("咱们")) return;
        if (t.endsWith("吗") || t.endsWith("呢") || t.endsWith("？") || t.endsWith("?")) return;
        if (t.contains("停课") || t.contains("取消") || t.contains("不用来")) return;
        Deadline d = parseDeadline(p.m.text, p.m.ts);
        String[] act = findAction(p.m.text);
        if (!d.ok() && act[0] == null) return;
        Card c = new Card();
        c.id = newCardId();
        c.kind = "todo";
        boolean missingSubject = act[0] == null && containsDelivery(p.m.text);
        c.title = missingSubject ? "待确认：提交事项" : act[0] != null ? act[0] : fallbackTitle(p.m.text);
        c.deadline = d.ok() ? d.value : 0;
        c.deadlineText = humanDeadline(c.deadline);
        c.needsConfirm = missingSubject || !d.ok() || "low".equals(d.conf);
        c.channel = findChannel(p.m.text);
        c.sourcePackage = p.m.pkg;
        c.groupId = p.m.groupId;
        c.groupName = p.m.groupName;
        c.sender = p.m.sender;
        c.sourceId = p.m.id;
        c.sourceText = p.m.text;
        c.sourceTs = p.m.ts;
        c.confidence = 0.62;
        c.reason = missingSubject ? "原消息只写了提交对象，未说明要提交什么" : "回复潮 " + p.burst + " 条确认回复，升为待办";
        c.context = new ArrayList<>(recent.getOrDefault(p.m.groupId, new ArrayList<>()));
        cards.add(c);
        p.carded = true;
        nPromoted++;
        notifyChanged();
    }

    static String fallbackTitle(String t) {
        // 先取第一个标点子句，做与 findAction 相同的时间/称呼清理；太短再退回旧办法
        if (t == null) return null;
        String trimmed = t.trim();
        String first = trimmed.isEmpty() ? "" : trimmed.split("\\r\\n|\\r|\\n")[0];
        String clause = "";
        for (String c : P_FALLBACK_SPLIT.split(first)) {
            c = c.trim();
            if (!c.isEmpty()) { clause = c; break; }
        }
        String cleaned = clause.isEmpty() ? "" : cleanTitle(clause);
        String title = cleaned.length() >= 2 ? cleaned : first;
        return title.length() > 18 ? title.substring(0, 18) + "…" : title;
    }

    void remember(Msg m) {
        List<String> r = recent.computeIfAbsent(m.groupId, k -> new ArrayList<>());
        r.add(m.sender + "：" + (m.text.length() > 40 ? m.text.substring(0, 40) : m.text));
        while (r.size() > 3) r.remove(0);
    }

    /** 跟我有关的程度：全部读当前档案，空字段一律跳过。 */
    double relevance(String t, String groupName) {
        String s = t == null ? "" : t;
        for (String n : meNames()) if (s.contains(n)) return 1.0;
        String sid = meSid();
        if (!sid.isEmpty() && s.contains(sid)) return 1.0;
        String grp = meGroup();
        if (!grp.isEmpty() && s.contains(grp)) return 0.9;
        String cls = meClass();
        if (!cls.isEmpty() && stripSpaces(s).contains(stripSpaces(cls))) return 0.85;
        for (String e : meCourses()) {
            if (s.contains(e) || (groupName != null && groupName.contains(e))) return 0.8;
        }
        return 0.4;
    }

    private static boolean isDuplicate(List<Card> source, Msg message, String flatText) {
        for (Card card : source) {
            if (card.cancelled) continue;
            // 先跳过其他群和窗口外的卡，避免为历史原文反复生成去空格字符串。
            if (card.sourceText != null && Objects.equals(message.groupId, card.groupId)
                    && Math.abs(message.ts - card.sourceTs) <= 30 * 60_000L
                    && flatText.equals(stripSpaces(card.sourceText))) return true;
        }
        return false;
    }

    /** 移除误识别卡片，保留完整内容供用户恢复。 */
    public boolean dismissCard(Card card, long now) {
        return dismissCard(card,now,"本人","移除事项，可在已移除列表恢复");
    }
    private boolean dismissCard(Card card,long now,String actor,String description) {
        if (card == null || now <= 0 || !cards.remove(card)) return false;
        card.changes.action(now,actor,description,card.displayTitle());
        card.removedAt = now;
        removedCards.add(card);
        notifyChanged();
        return true;
    }

    public List<Card> removedCards() {
        List<Card> result = new ArrayList<>(removedCards);
        result.sort((a, b) -> Long.compare(b.removedAt, a.removedAt));
        return result;
    }

    /** 已有更新的同题接龙时保留当前名单，避免恢复出两张提醒。 */
    public boolean restoreCard(Card card) {
        return restoreCard(card,System.currentTimeMillis());
    }
    public boolean restoreCard(Card card,long actedAt) {
        if (card == null || actedAt <= 0 || !removedCards.contains(card)) return false;
        for (Card active : cards) {
            if (card.cancelled && Objects.equals(card.groupId, active.groupId) && Objects.equals(card.title, active.title)) return false;
            if (card.id != null && card.id.equals(active.id)) return false;
            if ("jielong".equals(card.kind) && "jielong".equals(active.kind)
                    && Objects.equals(card.groupId, active.groupId)
                    && Objects.equals(card.jielongKey, active.jielongKey)
                    && (!blank(card.jielongKey) || Objects.equals(card.title, active.title))) return false;
            if (!blank(card.platformKey) && Objects.equals(card.platformKey, active.platformKey)
                    && Objects.equals(card.sourcePackage, active.sourcePackage)) return false;
        }
        removedCards.remove(card);
        card.removedAt = 0;
        if (card.cancelled) { card.cancelled = false; card.needsConfirm = true; card.reason = "该事项曾被来源取消，请核对后确认"; }
        cards.add(card);
        card.changes.action(actedAt,"本人","从已移除列表恢复事项",card.displayTitle());
        notifyChanged();
        return true;
    }

    public void clearRemovedCards() {
        removedCards.clear();
        notifyChanged();
    }

    /** null 保留当前时间，0 将时间标记为待确认。原始标题仍用于匹配群更正。 */
    public boolean editCard(Card card, String title, Long deadline) {
        return editCard(card,title,deadline,System.currentTimeMillis());
    }
    public boolean editCard(Card card,String title,Long deadline,long actedAt) {
        if(card==null || title==null || actedAt<=0 || !cards.contains(card))return false;
        String label = title.trim();
        if (!cards.contains(card) || label.isEmpty() || (deadline != null && deadline < 0)) return false;
        String previousTitle=card.displayTitle();long previousTime=card.deadline;
        boolean changed=!Objects.equals(previousTitle,label) || (deadline!=null && (!card.deadlineUserSet || previousTime!=deadline));
        if(!changed)return true;
        card.titleOverride = label.equals(card.title) ? null : label;
        if (deadline != null) {
            card.deadlineUserSet = true;
            card.deadline = deadline;
            card.deadlineText = humanDeadline(deadline);
            card.needsConfirm = deadline == 0 || card.changes.hasPending();
            if (TIME_UNCERTAIN.equals(card.reason)) card.reason = "";
        }
        String detail="标题："+previousTitle+" → "+card.displayTitle();
        if(deadline!=null)detail+="\n时间："+absoluteTime(previousTime)+" → "+absoluteTime(card.deadline);
        card.changes.action(actedAt,"本人",deadline==null?"修正事项标题":"核对事项标题和时间",detail);
        notifyChanged();
        return true;
    }
    public boolean setCardDone(Card card,boolean done,long actedAt) {
        if(card==null || actedAt<=0 || !cards.contains(card) || card.done==done)return false;
        card.done=done;
        card.changes.action(actedAt,"本人",done?"标记事项已完成":"撤销完成，恢复待办",card.displayTitle());
        notifyChanged();return true;
    }

    public static String humanDeadline(long ts) {
        if (ts == 0) return "待确认";
        Calendar now = Calendar.getInstance();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        long d0 = sendCalendar(now.getTimeInMillis()).getTimeInMillis();
        long d1 = sendCalendar(ts).getTimeInMillis();
        int days = (int) Math.round((d1 - d0) / 86400000.0);
        String hm = String.format(Locale.CHINA, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        if (days == 0) return "今天 " + hm;
        if (days == 1) return "明天 " + hm;
        if (days == 2) return "后天 " + hm;
        if (days >= 3 && days <= 6) return "周" + "一二三四五六日".charAt(weekday(ts)) + " " + hm;
        return (c.get(Calendar.MONTH) + 1) + " 月 " + c.get(Calendar.DAY_OF_MONTH) + " 日 " + hm;
    }

    void notifyChanged() {
        stateRevision++;
        if (listener != null) listener.onCardsChanged();
    }

    public long stateRevision() { return stateRevision; }

    public boolean hasStateChanges() { return stateRevision != savedRevision; }

    public void markStateSaved(long revision) { savedRevision = revision; }

    // ---------- 二级 / 三级复核 ----------
    // 第一级（规则 + 逻辑回归 + 规则抽取）在本机即时出结果；拿不准的消息和卡片通过 escalator
    // 交给上一级（大模型）复判分类、补全字段。上一级的结论用 applyAi 写回，规则仍是兜底：
    // 没配置上一级、没网、调用失败时，卡片保持第一级的结果，不会丢。

    /** 分类最高概率低于它就算“拿不准”。 */
    static final double REVIEW_PROB = 0.6;
    /** 上一级认为“不是待办”时，至少这么有把握才会移走第一级出的卡。 */
    static final double AI_DROP_CONF = 0.8;
    /** 上一级从漏判消息里新建待办的最低把握。 */
    static final double AI_ADD_CONF = 0.7;

    /** 送去复核的一条：要么是一张已出的卡（补全/复核），要么是一条没出卡的消息（复判）。 */
    public static class Escalation {
        public String epoch, cardState;  // 本机校验用，不发送给模型
        public String scopeVersion, profileKey;
        public String cardId;            // 非空：复核这张卡
        public Msg msg;                  // 原消息
        public String label;             // 第一级的分类
        public double prob;              // 第一级的最高概率
        public String reason;            // extract / low_conf / exam_time / exam_unparsed / screenshot
        public List<String> context = new ArrayList<>();
    }

    public interface Escalator { void escalate(Escalation e); }

    /** 由宿主（安卓端）装上；为 null 时不复核，行为与原来完全一致。 */
    public Escalator escalator;

    /** 上一级的结论，字段与提示词约定的输出结构一一对应。 */
    public static class AiVerdict {
        public String epoch, cardState;
        public String scopeVersion, scopeGroup, profileKey;
        public String cardId;            // 复核卡片时回填
        public Msg msg;                  // 复判消息时回填
        public boolean todo;             // 是不是需要我去做的事
        public String kind = "todo";     // todo / exam
        public String title;
        public long deadline;            // 0 = 没有明确时间
        public boolean timeSure;
        public String place, channel, bring;
        public double confidence;
        public String note;              // 一句话理由，显示在卡片上
    }

    static double topProb(double[] sc) {
        double max = sc[argmax(sc)], sum = 0;
        for (double v : sc) sum += Math.exp(v - max);
        return 1.0 / sum;
    }

    /** 没出卡的消息里，哪些值得让上一级再看一眼：带动作又带时间，或模型拿不准且有一点线索。 */
    boolean shouldReview(Map<String, Double> f, double prob, String t) {
        if (isQuestion(t)) return false;
        boolean act = f.get("M:has_action") > 0, time = f.get("M:has_time") > 0;
        return (act && time) || (prob < REVIEW_PROB && (act || time));
    }

    void escalateCard(Card c, Msg m, String why) {
        if (escalator == null || c == null) return;
        Escalation e = new Escalation();
        e.cardId = c.id;
        e.epoch = aiEpoch;
        prepareRequest(e, m);
        e.cardState = aiCardState(c);
        e.msg = m;
        e.label = c.kind;
        e.prob = c.confidence;
        e.reason = why;
        if (c.context != null) e.context.addAll(c.context);
        try { escalator.escalate(e); } catch (RuntimeException ignored) { }
    }

    void escalateMessage(Msg m, String label, double prob, String why) {
        if (escalator == null) return;
        Escalation e = new Escalation();
        e.epoch = aiEpoch;
        prepareRequest(e, m);
        stateRevision++; // 未出卡的复判也要保存群版本，重启后继续验证。
        e.msg = m;
        e.label = label;
        e.prob = prob;
        e.reason = why;
        e.context.addAll(recent.getOrDefault(m.groupId, new ArrayList<>()));
        try { escalator.escalate(e); } catch (RuntimeException ignored) { }
    }

    Card cardById(String id) {
        if (id == null) return null;
        for (Card c : cards) if (id.equals(c.id)) return c;
        return null;
    }

    private void prepareRequest(Escalation request, Msg m) {
        if (!groups.containsKey(m.groupId)) touchGroup(m.groupId, m.groupName, m.pkg, m.ts);
        request.scopeVersion = groupVersion(m.groupId);
        request.profileKey = profileKey();
    }
    Escalation beginImageRequest(Msg message) {
        Escalation request = new Escalation();
        request.epoch = aiEpoch; request.msg = message;
        prepareRequest(request, message);
        stateRevision++;
        return request;
    }

    /** 精确比较请求时的卡片内容，拒绝改期、人工修改或其他更新前排队的旧结果。 */
    static String aiCardState(Card c) {
        StringBuilder out = new StringBuilder();
        for (String value : new String[]{c.id, c.kind, c.title, c.titleOverride, c.sourceId,
                c.sourceText, c.lastRevisionText, c.channel, c.place, c.bring, c.reason,
                String.valueOf(c.deadline), String.valueOf(c.sourceTs), String.valueOf(c.needsConfirm),
                String.valueOf(c.deadlineUserSet), String.valueOf(c.done), String.valueOf(c.removedAt),
                String.valueOf(c.aiChecked), String.valueOf(c.confidence), String.valueOf(c.sourceTimeUncertain)}) {
            out.append(value == null ? -1 : value.length()).append(':');
            if (value != null) out.append(value);
        }
        out.append("changes:").append(c.changes.revision);
        return out.toString();
    }

    /** 上一级给出的时间要落在合理范围内：不早于消息前一天，不晚于一年后。 */
    static boolean plausibleDeadline(long deadline, long sentAt) {
        if (deadline <= 0) return false;
        long base = sentAt > 0 ? sentAt : System.currentTimeMillis();
        return deadline >= base - 86400_000L && deadline <= base + 366L * 86400_000L;
    }

    static String clip(String s, int max) {
        if (blank(s)) return null;
        String v = s.trim().replaceAll("\\s+", " ");
        return v.length() > max ? v.substring(0, max) + "…" : v;
    }

    /**
     * 写回上一级的结论。用户亲手改过的标题/时间、已完成的卡一律不动。
     * @return true 表示卡片有变化
     */
    public boolean applyAi(AiVerdict v, long now) {
        if (v == null || !Double.isFinite(v.confidence) || v.confidence > 1
                || v.confidence < (v.todo ? AI_ADD_CONF : AI_DROP_CONF)
                || (v.epoch != null && !aiEpoch.equals(v.epoch))) return false;
        if (v.profileKey != null && !v.profileKey.equals(profileKey())) return false;
        if (v.scopeVersion != null && !Objects.equals(v.scopeVersion, groupVersion(v.scopeGroup))) return false;
        if (v.cardId != null) {
            Card c = cardById(v.cardId);
            if (c == null || c.done) return false;           // 已被移除 / 已完成：不打扰
            if (v.cardState != null && !v.cardState.equals(aiCardState(c))) return false;
            c.aiChecked = true;
            if (!v.todo) {
                if (v.confidence >= AI_DROP_CONF && c.titleOverride == null && !c.deadlineUserSet
                        && !"jielong".equals(c.kind)) {
                    c.reason = "AI 复核认为这不是需要你做的事" + (blank(v.note) ? "" : "：" + clip(v.note, 40))
                            + "。如有误可在“已移除的卡片”恢复";
                    return dismissCard(c,now,"AI 复核","复核认为无需处理，移入可恢复记录");
                }
                notifyChanged();
                return true;
            }
            boolean changed = false;
            String title = clip(v.title, 24);
            boolean weakTitle = c.title == null || c.title.startsWith("待确认") || c.needsConfirm
                    || c.confidence < 0.85;
            if (title != null && c.titleOverride == null && weakTitle && !"jielong".equals(c.kind)
                    && !title.equals(c.title)) {
                c.title = title;
                changed = true;
            }
            if (!c.deadlineUserSet && plausibleDeadline(v.deadline, c.sourceTs)
                    && (c.deadline == 0 || c.needsConfirm) && v.deadline != c.deadline) {
                c.deadline = v.deadline;
                c.deadlineText = humanDeadline(v.deadline);
                changed = true;
            }
            if (blank(c.place) && !blank(v.place)) { c.place = clip(v.place, 30); changed = true; }
            if (blank(c.channel) && !blank(v.channel) && !"jielong".equals(c.kind)) { c.channel = clip(v.channel, 20); changed = true; }
            if (blank(c.bring) && !blank(v.bring) && "exam".equals(c.kind)) { c.bring = clip(v.bring, 30); changed = true; }
            boolean sure = !c.changes.hasPending() && (c.deadlineUserSet || (!c.sourceTimeUncertain && plausibleDeadline(v.deadline, c.sourceTs)
                    && c.deadline == v.deadline && v.timeSure && !blank(c.title)
                    && !c.title.startsWith("待确认")));
            if (c.needsConfirm && sure) {
                c.needsConfirm = false;
                changed = true;
            }
            String note = c.needsConfirm ? "AI 已补全，但原文仍有不确定的地方，建议核对" : "AI 已补全标题和时间";
            if (changed || !note.equals(c.reason)) {
                c.reason = changed ? note : c.reason;
                c.confidence = Math.max(c.confidence, Math.min(0.95, v.confidence));
            }
            notifyChanged();
            return changed;
        }
        Msg m = v.msg;
        if (m == null || !v.todo || v.confidence < AI_ADD_CONF) return false;
        if (MODE_IGNORE.equals(groupMode(m.groupId))) return false;
        String text = m.text == null ? "" : m.text;
        String flat = stripSpaces(text);
        if (isDuplicate(cards, m, flat) || isDuplicate(removedCards, m, flat)) return false;
        String title = clip(v.title, 24);
        if (title == null) title = fallbackTitle(text);
        Card c = new Card();
        c.id = newCardId();
        c.kind = "exam".equals(v.kind) ? "exam" : "todo";
        c.title = title;
        c.deadline = plausibleDeadline(v.deadline, m.ts) ? v.deadline : 0;
        c.deadlineText = humanDeadline(c.deadline);
        c.sourceTimeUncertain = m.timeUncertain;
        c.needsConfirm = c.deadline == 0 || !v.timeSure || m.timeUncertain;
        c.channel = "exam".equals(c.kind) ? null : clip(v.channel, 20);
        c.place = clip(v.place, 30);
        c.bring = "exam".equals(c.kind) ? clip(v.bring, 30) : null;
        c.sourcePackage = m.pkg;
        c.groupId = m.groupId;
        c.groupName = m.groupName;
        c.sender = m.sender;
        c.sourceId = m.id;
        c.sourceText = text;
        c.sourceTs = m.ts;
        c.confidence = Math.min(0.9, v.confidence);
        c.aiChecked = true;
        c.reason = "AI 复核从群消息里找出的事项" + (c.needsConfirm ? "，时间请核对" : "");
        c.context = new ArrayList<>(recent.getOrDefault(m.groupId, new ArrayList<>()));
        cards.add(c);
        notifyChanged();
        return true;
    }

    // ---------- 教务系统的考试安排 ----------
    public static final String PKG_SCHOOL = "jwxt";

    /** 教务系统里的一场考试；start 为 0 表示只知道日期或时间未排。 */
    public static class SchoolExam {
        public String course, place;
        public long start;          // 毫秒；0 = 时间未知
        public boolean dateOnly;    // 只知道日期，时刻待定
        public boolean finished;
    }

    /**
     * 把教务系统的考试安排同步成考试卡：新考试出卡，改了时间/地点的更新原卡并标“有更新”。
     * 已结束的不出卡；用户移除过的不再顶回来；用户改过的时间不覆盖；
     * 群消息里已经有同一门课、时间相近（12 小时内）的考试卡时不重复出卡，只补地点。
     * @return 新增或更新的卡片数
     */
    public int syncSchoolExams(List<SchoolExam> exams, long now) {
        int changed = 0;
        for (SchoolExam x : exams) {
            if (x == null || blank(x.course) || x.finished) continue;
            if (x.start > 0 && x.start < now - 3 * 3600_000L) continue;      // 已经考完
            String key = "school:" + ChangeDiff.norm(x.course);
            boolean removed = false;
            for (Card r : removedCards) if (PKG_SCHOOL.equals(r.sourcePackage) && key.equals(r.platformKey)) removed = true;
            if (removed) continue;
            Card same = null;
            for (Card c : cards) if (PKG_SCHOOL.equals(c.sourcePackage) && key.equals(c.platformKey)) { same = c; break; }
            String title = x.course.trim().endsWith("考试") ? x.course.trim() : x.course.trim() + " 考试";
            String place = clip(x.place, 30);
            if (same != null) {
                boolean upd = false;
                if (!same.deadlineUserSet && x.start > 0 && x.start != same.deadline) {
                    same.deadline = x.start;
                    same.deadlineText = humanDeadline(x.start);
                    same.needsConfirm = x.dateOnly;
                    upd = true;
                }
                if (place != null && !place.equals(same.place)) { same.place = place; upd = true; }
                if (upd) {
                    same.updated = true;
                    same.reason = "教务系统的考试安排有变化";
                    changed++;
                    notifyChanged();
                }
                continue;
            }
            Card fromChat = null;
            String course = ChangeDiff.norm(x.course);
            for (Card c : cards) {
                if (!"exam".equals(c.kind) || c.done || PKG_SCHOOL.equals(c.sourcePackage)) continue;
                String t = ChangeDiff.norm(c.displayTitle()) + " " + ChangeDiff.norm(c.sourceText);
                boolean near = x.start == 0 || c.deadline == 0 || Math.abs(c.deadline - x.start) <= 12 * 3600_000L;
                if (t.contains(course) && near) { fromChat = c; break; }
            }
            if (fromChat != null) {
                if (blank(fromChat.place) && place != null) { fromChat.place = place; changed++; notifyChanged(); }
                continue;
            }
            Card c = new Card();
            c.id = newCardId();
            c.kind = "exam";
            c.title = clip(title, 24);
            c.platformKey = key;
            c.deadline = x.start > 0 ? x.start : 0;
            c.deadlineText = humanDeadline(c.deadline);
            c.needsConfirm = c.deadline == 0 || x.dateOnly;
            c.place = place;
            c.sourcePackage = PKG_SCHOOL;
            c.groupId = PKG_SCHOOL + "|教务系统";
            c.groupName = "教务系统";
            c.sender = "教务系统";
            c.sourceId = key;
            c.sourceText = x.course.trim() + (place == null ? "" : " · " + place);
            c.sourceTs = now;
            c.confidence = 0.95;
            c.reason = c.deadline == 0 ? "教务系统还没排出考试时间" : x.dateOnly ? "教务系统只给了日期，具体时刻待确认" : "来自教务系统的考试安排";
            c.context = new ArrayList<>();
            cards.add(c);
            changed++;
            notifyChanged();
        }
        return changed;
    }

    // ---------- 图片 / 语音 / 文件消息 ----------
    /** 同一个群同一个人连发的图片/语音，在这个窗口内合并成一张提示卡。 */
    static final long MEDIA_MERGE_MS = 10 * 60_000L;

    static String mediaWord(String placeholder) {
        if (placeholder == null) return null;
        switch (placeholder) {
            case "[图片]": return "图片";
            case "[语音]": return "语音";
            case "[视频]": return "视频";
            case "[文件]": return "文件";
            default: return null;
        }
    }

    /**
     * 通知里只有“[图片]”“[语音]”这类占位符，正文读不到。
     * 重点群、老师/班干部或 @全体 发的才出一张“去看一下”的提示卡（同人 10 分钟内合并），
     * 其余只记进上下文，让后面的文字消息能带上“前面发过图片”。
     * @return true 表示出了或更新了提示卡
     */
    public boolean noteMedia(Msg m, String placeholder) {
        String word = mediaWord(placeholder);
        if (m == null || word == null) return false;
        GroupInfo gi = touchGroup(m.groupId, m.groupName, m.pkg, m.ts);
        if (gi != null && MODE_IGNORE.equals(gi.mode)) return false;
        nIn++;
        remember(new Msg(m.id, m.groupId, m.groupName, m.sender, placeholder, m.pkg, m.ts));
        String sender = m.sender == null ? "" : m.sender;
        boolean focus = MODE_FOCUS.equals(groupMode(m.groupId));
        boolean trusted = focus || containsAny(sender, TEACHER_HINTS)
                || (m.text != null && m.text.contains("[有全体消息]"));
        if (!trusted) return false;
        for (Card c : cards) {
            if ("media".equals(c.kind) && !c.done && Objects.equals(c.groupId, m.groupId)
                    && Objects.equals(c.sender, m.sender) && Math.abs(m.ts - c.sourceTs) <= MEDIA_MERGE_MS) {
                if (!c.sourceText.contains(placeholder)) c.sourceText = c.sourceText + " " + placeholder;
                c.title = mediaTitle(sender, c.sourceText);
                c.sourceTs = m.ts;
                c.sourceId = m.id;
                notifyChanged();
                return true;
            }
        }
        for (Card r : removedCards) {
            if (Objects.equals(r.sourceId, m.id)) return false;
        }
        Card c = new Card();
        c.id = newCardId();
        c.kind = "media";
        c.sourceText = placeholder;
        c.title = mediaTitle(sender, placeholder);
        c.deadline = 0;
        c.deadlineText = humanDeadline(0);
        c.needsConfirm = false;
        c.sourcePackage = m.pkg;
        c.groupId = m.groupId;
        c.groupName = m.groupName;
        c.sender = m.sender;
        c.sourceId = m.id;
        c.sourceTs = m.ts;
        c.confidence = 0.5;
        c.reason = "语音".equals(word) || "视频".equals(word)
                ? "楼外读不到" + word + "内容，可能是口头通知，请到原群查看"
                : "楼外读不到" + word + "内容；如果是通知截图，截屏后分享给楼外即可自动识别";
        c.context = new ArrayList<>(recent.getOrDefault(m.groupId, new ArrayList<>()));
        cards.add(c);
        notifyChanged();
        return true;
    }

    static String mediaTitle(String sender, String placeholders) {
        List<String> kinds = new ArrayList<>();
        for (String p : new String[]{"[图片]", "[语音]", "[视频]", "[文件]"}) {
            if (placeholders != null && placeholders.contains(p)) kinds.add(mediaWord(p));
        }
        String who = blank(sender) || "群友".equals(sender) || "群消息".equals(sender) ? "群里" : sender.trim();
        String what = kinds.isEmpty() ? "消息" : String.join("和", kinds);
        return clip("看一下" + who + "发的" + what, 24);
    }

    // ---------- 落盘 ----------
    // 纯文本行格式，字段逐行 "key<TAB>值"，值里的反斜杠/换行/回车/制表符转义，
    // null 写成 \0；卡片以 CARD 开始、END 结束，上下文用 CTX 行重复。全部 UTF-8。

    String newCardId() {
        seq++;
        return "c" + seq;
    }

    public void saveState(OutputStream out) throws Exception {
        java.io.ByteArrayOutputStream payload = new java.io.ByteArrayOutputStream();
        writeState(payload);
        out.write(StateFormat.seal(payload.toByteArray()));
        out.flush();
    }

    private void writeState(OutputStream out) throws Exception {
        Writer w = new OutputStreamWriter(out, "UTF-8");
        w.write("NPL1\n");
        w.write("FORMAT\t2\n");
        w.write("SEQ\t" + seq + "\n");
        w.write("AIEPOCH\t" + esc(aiEpoch) + "\n");
        for (String id : notificationIds) w.write("SEEN\t" + esc(id) + "\n");
        // 群登记：旧版本读到 GROUP 行会跳过（不在卡片块里），不影响卡片恢复。
        for (GroupInfo g : groups.values()) {
            w.write("GROUP\t" + esc(g.id) + "\t" + esc(g.name) + "\t" + esc(g.pkg) + "\t" + g.mode
                    + "\t" + g.lastTs + "\t" + g.count + "\t" + esc(g.aiVersion) + "\n");
        }
        List<Card> stored = new ArrayList<>(cards);
        stored.addAll(removedCards);
        w.write("N\t" + stored.size() + "\n");
        for (Card c : stored) {
            w.write("CARD\n");
            w.write("id\t" + esc(c.id) + "\n");
            w.write("kind\t" + esc(c.kind) + "\n");
            w.write("title\t" + esc(c.title) + "\n");
            w.write("channel\t" + esc(c.channel) + "\n");
            w.write("place\t" + esc(c.place) + "\n");
            w.write("bring\t" + esc(c.bring) + "\n");
            w.write("groupId\t" + esc(c.groupId) + "\n");
            w.write("sourcePackage\t" + esc(c.sourcePackageName()) + "\n");
            w.write("groupName\t" + esc(c.groupName) + "\n");
            w.write("sender\t" + esc(c.sender) + "\n");
            w.write("sourceId\t" + esc(c.sourceId) + "\n");
            w.write("sourceText\t" + esc(c.sourceText) + "\n");
            w.write("deadlineText\t" + esc(c.deadlineText) + "\n");
            w.write("deadlineUserSet\t" + (c.deadlineUserSet ? "1" : "0") + "\n");
            if (c.titleOverride != null) w.write("titleOverride\t" + esc(c.titleOverride) + "\n");
            if (c.lastRevisionText != null) w.write("lastRevisionText\t" + esc(c.lastRevisionText) + "\n");
            w.write("deadline\t" + c.deadline + "\n");
            w.write("sourceTs\t" + c.sourceTs + "\n");
            w.write("sourceTimeUncertain\t" + (c.sourceTimeUncertain ? "1" : "0") + "\n");
            w.write("jielongKey\t" + esc(c.jielongKey) + "\n");
            if (c.platformKey != null) w.write("platformKey\t" + esc(c.platformKey) + "\n");
            w.write("needsConfirm\t" + (c.needsConfirm ? "1" : "0") + "\n");
            w.write("updated\t" + (c.updated ? "1" : "0") + "\n");
            w.write("done\t" + (c.done ? "1" : "0") + "\n");
            w.write("cancelled\t" + (c.cancelled ? "1" : "0") + "\nlastChangeTs\t" + c.lastChangeTs + "\n");
            c.changes.write(w);
            if (c.removedAt > 0) w.write("removedAt\t" + c.removedAt + "\n");
            if (c.aiChecked) w.write("ai\t1\n");
            w.write("confidence\t" + Double.toString(c.confidence) + "\n");
            w.write("reason\t" + esc(c.reason) + "\n");
            if (c.context == null) {
                w.write("CTXN\t-1\n");
            } else {
                w.write("CTXN\t" + c.context.size() + "\n");
                for (String s : c.context) w.write("CTX\t" + esc(s) + "\n");
            }
            w.write("END\n");
        }
        w.flush();
    }

    public void loadState(InputStream in) throws Exception {
        byte[] bytes = StateFormat.read(in);
        StateFormat.validate(bytes);
        Engine parsed = new Engine();
        parsed.profile = profile;
        parsed.loadValidatedState(new java.io.ByteArrayInputStream(bytes));
        cards.clear(); cards.addAll(parsed.cards);
        removedCards.clear(); removedCards.addAll(parsed.removedCards);
        groups.clear(); groups.putAll(parsed.groups);
        seq = Math.max(seq, parsed.seq);
        aiEpoch = parsed.aiEpoch;
        notificationIds.clear(); notificationIds.addAll(parsed.notificationIds);
        candidates.clear(); senderStat.clear(); recent.clear();
        stateRevision++;
        savedRevision = parsed.hasStateChanges() ? stateRevision - 1 : stateRevision;
    }

    private void loadValidatedState(InputStream in) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line = br.readLine();
        if (line == null || !"NPL1".equals(line.trim())) {
            cards.clear();                       // 空文件 / 非本格式：当作没有卡片，不抛异常
            removedCards.clear();
            stateRevision++;
            savedRevision = stateRevision;
            return;
        }
        List<Card> loaded = new ArrayList<>();
        Map<String, GroupInfo> loadedGroups = new LinkedHashMap<>();
        long loadedSeq = -1;
        Card cur = null;
        while ((line = br.readLine()) != null) {
            if (line.isEmpty()) continue;
            int t = line.indexOf('\t');
            String key = t < 0 ? line : line.substring(0, t);
            String val = t < 0 ? "" : line.substring(t + 1);
            if ("SEQ".equals(key)) { loadedSeq = toLong(val, loadedSeq); continue; }
            if ("AIEPOCH".equals(key) && cur == null && !blank(unesc(val))) { aiEpoch = unesc(val); continue; }
            if ("SEEN".equals(key) && cur == null && !blank(unesc(val))) { markNotification(unesc(val)); continue; }
            if ("GROUP".equals(key) && cur == null) {
                String[] f = val.split("\t", -1);
                if (f.length >= 6 && !blank(unesc(f[0]))) {
                    GroupInfo g = new GroupInfo();
                    g.id = unesc(f[0]);
                    g.name = unesc(f[1]);
                    g.pkg = unesc(f[2]) == null ? "" : unesc(f[2]);
                    g.mode = MODE_FOCUS.equals(f[3]) || MODE_IGNORE.equals(f[3]) ? f[3] : MODE_NORMAL;
                    g.lastTs = toLong(f[4], 0);
                    g.count = (int) Math.max(0, Math.min(Integer.MAX_VALUE, toLong(f[5], 0)));
                    if (f.length > 6 && !blank(unesc(f[6]))) g.aiVersion = unesc(f[6]);
                    loadedGroups.put(g.id, g);
                }
                continue;
            }
            if ("N".equals(key)) continue;
            if ("CARD".equals(key)) { cur = new Card(); continue; }
            if ("END".equals(key)) { if (cur != null) loaded.add(cur); cur = null; continue; }
            if (cur == null) continue;
            if ("id".equals(key)) cur.id = unesc(val);
            else if ("kind".equals(key)) cur.kind = unesc(val);
            else if ("title".equals(key)) cur.title = unesc(val);
            else if ("channel".equals(key)) cur.channel = unesc(val);
            else if ("place".equals(key)) cur.place = unesc(val);
            else if ("bring".equals(key)) cur.bring = unesc(val);
            else if ("groupId".equals(key)) cur.groupId = unesc(val);
            else if ("sourcePackage".equals(key)) cur.sourcePackage = unesc(val);
            else if ("groupName".equals(key)) cur.groupName = unesc(val);
            else if ("sender".equals(key)) cur.sender = unesc(val);
            else if ("sourceId".equals(key)) cur.sourceId = unesc(val);
            else if ("sourceText".equals(key)) cur.sourceText = unesc(val);
            else if ("deadlineText".equals(key)) cur.deadlineText = unesc(val);
            else if ("deadlineUserSet".equals(key)) cur.deadlineUserSet = "1".equals(val.trim());
            else if ("titleOverride".equals(key)) cur.titleOverride = unesc(val);
            else if ("lastRevisionText".equals(key)) cur.lastRevisionText = unesc(val);
            else if ("deadline".equals(key)) cur.deadline = toLong(val, 0);
            else if ("sourceTs".equals(key)) cur.sourceTs = toLong(val, 0);
            else if ("sourceTimeUncertain".equals(key)) cur.sourceTimeUncertain = "1".equals(val);
            else if ("jielongKey".equals(key)) cur.jielongKey = unesc(val);
            else if ("platformKey".equals(key)) cur.platformKey = unesc(val);
            else if ("needsConfirm".equals(key)) cur.needsConfirm = "1".equals(val.trim());
            else if ("updated".equals(key)) cur.updated = "1".equals(val.trim());
            else if ("done".equals(key)) cur.done = "1".equals(val.trim());
            else if ("cancelled".equals(key)) cur.cancelled = "1".equals(val.trim());
            else if ("lastChangeTs".equals(key)) cur.lastChangeTs = StateFormat.number(val);
            else if ("HISTORY".equals(key) || "PENDINGCHANGE".equals(key) || "changeRevision".equals(key) || "changeBase".equals(key)) cur.changes.read(key, val);
            else if ("removedAt".equals(key)) cur.removedAt = Math.max(0, toLong(val, 0));
            else if ("ai".equals(key)) cur.aiChecked = "1".equals(val.trim());
            else if ("confidence".equals(key)) cur.confidence = toDouble(val, 0);
            else if ("reason".equals(key)) cur.reason = unesc(val);
            else if ("CTXN".equals(key)) {
                long n = toLong(val, 0);
                cur.context = n < 0 ? null : new ArrayList<String>();
            } else if ("CTX".equals(key)) {
                if (cur.context == null) cur.context = new ArrayList<>();
                cur.context.add(unesc(val));
            }
        }
        cards.clear();
        removedCards.clear();
        groups.clear();
        groups.putAll(loadedGroups);
        if (loadedSeq > seq) seq = loadedSeq;
        boolean repairedTitle = false;
        for (Card c : loaded) {                  // 兜底：老数据里的编号也不能被撞
            if (!blank(c.sourceId)) markNotification(c.sourceId); // 旧存档只迁移确实有卡片的来源。
            if ("todo".equals(c.kind) && deliveryOnly(c.title) && !blank(c.sourceText)) {
                String[] corrected = findAction(c.sourceText);
                c.title = corrected[0] == null ? "待确认：提交事项" : corrected[0];
                if (blank(c.channel)) c.channel = findChannel(c.sourceText);
                if (corrected[0] == null) {
                    c.needsConfirm = true;
                    c.reason = "原消息只写了提交对象，未说明要提交什么";
                }
                repairedTitle = true;
            }
            if (c.deadlineHint() != null && !c.needsConfirm) {
                String originalTime = blank(c.lastRevisionText) ? c.sourceText : c.lastRevisionText;
                Deadline parsed = parseDeadline(originalTime, c.sourceTs);
                if (c.deadline == parsed.value) {
                    c.needsConfirm = true;
                    c.reason = "原文只说明下课或放学前，具体时刻待确认";
                } else c.deadlineUserSet = true;
                repairedTitle = true;
            }
            if (blank(c.sourcePackage)) c.sourcePackage = c.sourcePackageName();
            if (c.removedAt > 0) removedCards.add(c);
            else cards.add(c);
            long n = idNumber(c.id);
            if (n > seq) seq = n;
            if ("exam".equals(c.kind)) {
                String hit = examHit(c.sourceText);
                if (hit != null) c.title = examTitle(c.sourceText, hit);
            }
        }
        stateRevision++;
        savedRevision = repairedTitle ? stateRevision - 1 : stateRevision;
    }

    static long idNumber(String id) {
        if (id == null || id.length() < 2 || id.charAt(0) != 'c') return -1;
        long v = 0;
        for (int i = 1; i < id.length(); i++) {
            char ch = id.charAt(i);
            if (ch < '0' || ch > '9') return -1;
            v = v * 10 + (ch - '0');
        }
        return v;
    }

    static long toLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    static double toDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    static String esc(String s) {
        if (s == null) return "\\0";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') b.append("\\\\");
            else if (ch == '\n') b.append("\\n");
            else if (ch == '\r') b.append("\\r");
            else if (ch == '\t') b.append("\\t");
            else b.append(ch);
        }
        return b.toString();
    }

    static String unesc(String s) {
        if (s == null) return null;
        if ("\\0".equals(s)) return null;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != '\\' || i + 1 >= s.length()) { b.append(ch); continue; }
            char n = s.charAt(++i);
            if (n == 'n') b.append('\n');
            else if (n == 'r') b.append('\r');
            else if (n == 't') b.append('\t');
            else if (n == '\\') b.append('\\');
            else b.append('\\').append(n);
        }
        return b.toString();
    }

    public List<Card> ranked(int limit) {
        return ranked(limit, System.currentTimeMillis());
    }

    // 排序档次（小的在前）：
    //   0 = 未完成且未过期的接龙卡；
    //   1 = 未完成、有 deadline 且 deadline>=now 的其他卡；
    //   2 = 未完成、deadline==0 的卡；
    //   3 = 未完成且已过期（deadline>0 且 deadline<now，含接龙）；
    //   4 = c.done 为真的卡（不管其他条件，永远最后）。
    static int rankTier(Card c, long now) {
        if (c.done) return 4;
        if (c.deadline > 0 && c.deadline < now) return 3;   // 已过期，接龙也算
        if ("jielong".equals(c.kind)) return 0;             // 未过期的接龙卡
        if (c.deadline > 0) return 1;                       // 有 deadline 且未过期
        return 2;                                           // 无时间
    }

    public List<Card> ranked(int limit, long now) {
        return rankCards(new ArrayList<>(cards), limit, now);
    }

    public enum CardScope { ALL, OPEN, OVERDUE, COMPLETED }
    /** UPCOMING = 今天起：未完成、时间已确定、截止日在今天或以后。放在最后，旧的序号不变。 */
    public enum CardWindow { ANY, TODAY, TOMORROW, NEEDS_CONFIRM, UPCOMING }

    public List<Card> queryCards(String keywords, CardScope scope, int limit, long now) {
        return queryCards(keywords, scope, CardWindow.ANY, limit, now);
    }

    public List<Card> queryCards(String keywords, CardScope scope, CardWindow window, int limit, long now) {
        List<Card> result = new ArrayList<>();
        if (limit <= 0) return result;
        String normalized = keywords == null ? "" : keywords.trim().toLowerCase(Locale.ROOT);
        String[] terms = normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
        TimeZone zone = TimeZone.getDefault();
        long today = Math.floorDiv(now, 86400000L)
                + Math.floorDiv(Math.floorMod(now, 86400000L) + zone.getOffset(now), 86400000L);
        for (Card card : cards) {
            if (matchesScope(card, scope, now) && matchesWindow(card, window, today, now, zone) && matchesTerms(card, terms)) result.add(card);
        }
        return rankCards(result, limit, now);
    }

    private static boolean matchesScope(Card card, CardScope scope, long now) {
        switch (scope) {
            case OPEN: return !card.done;
            case OVERDUE: return rankTier(card, now) == 3;
            case COMPLETED: return card.done;
            default: return true;
        }
    }

    static long localDay(long ms, TimeZone zone) {
        return Math.floorDiv(ms, 86400000L) + Math.floorDiv(Math.floorMod(ms, 86400000L) + zone.getOffset(ms), 86400000L);
    }

    /**
     * 今天起：未完成、知道日期、日期在今天或以后。
     * 时刻已确定的，今天已过点就算过期，不放进来；
     * 只知道日期、时刻待确认的（如"11月12日下课前"），只要日期没过就放进来。
     */
    public static boolean isUpcoming(Card card, long now) {
        TimeZone zone = TimeZone.getDefault();
        return isUpcoming(card, localDay(now, zone), now, zone);
    }

    private static boolean isUpcoming(Card card, long today, long now, TimeZone zone) {
        if (card.done || card.deadline <= 0) return false;
        if (localDay(card.deadline, zone) < today) return false;
        return card.needsConfirm || card.deadline > now;
    }

    private static boolean matchesWindow(Card card, CardWindow window, long today, long now, TimeZone zone) {
        if (window == CardWindow.ANY) return true;
        if (card.done) return false;
        if (window == CardWindow.NEEDS_CONFIRM) return card.needsConfirm || card.deadline == 0;
        if (window == CardWindow.UPCOMING) return isUpcoming(card, today, now, zone);
        if (card.needsConfirm || card.deadline == 0) return false;
        long day = localDay(card.deadline, zone);
        return day - today == (window == CardWindow.TODAY ? 0 : 1);
    }

    private static boolean matchesTerms(Card card, String[] terms) {
        if (terms.length == 0) return true;
        String[] fields = {card.displayTitle(), card.titleOverride == null ? null : card.title,
                card.groupName, card.sender, card.channel, card.place, card.sourceAppName()};
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] != null) fields[i] = fields[i].toLowerCase(Locale.ROOT);
        }
        for (String term : terms) {
            boolean found = false;
            for (String field : fields) {
                if (field != null && field.contains(term)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static List<Card> rankCards(List<Card> out, int limit, long now) {
        out.sort((a, b) -> {
            int ta = rankTier(a, now), tb = rankTier(b, now);
            if (ta != tb) return Integer.compare(ta, tb);
            switch (ta) {
                case 0:                                       // 接龙：deadline 从小到大
                case 1:                                       // 未过期其他卡：deadline 从小到大
                    return Long.compare(a.deadline, b.deadline);
                case 2:                                       // 无时间：confidence 从大到小
                    return Double.compare(b.confidence, a.confidence);
                case 3:                                       // 已过期：deadline 从大到小（最近过期在前）
                    return Long.compare(b.deadline, a.deadline);
                default:                                      // 已完成：deadline 从小到大，==0 放最后
                    boolean za = a.deadline == 0, zb = b.deadline == 0;
                    if (za != zb) return za ? 1 : -1;
                    return za ? 0 : Long.compare(a.deadline, b.deadline);
            }
        });
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    // ---------- 按群管理 ----------
    public static final String MODE_NORMAL = "normal";
    public static final String MODE_FOCUS = "focus";
    public static final String MODE_IGNORE = "ignore";
    private static final int MAX_GROUPS = 300;

    /** 见过的群：名称、来源、最近一次消息时间、累计消息数和用户设定的处理方式。 */
    public static final class GroupInfo {
        public String id, name, pkg;
        public String mode = MODE_NORMAL;
        public long lastTs;
        public int count;
        String aiVersion = java.util.UUID.randomUUID().toString();

        /** 学习平台按整个 App 管理，卡片的群编号是“包名|课程”。 */
        public boolean isPlatform() { return PlatformNotice.isPlatform(pkg); }
    }

    private final Map<String, GroupInfo> groups = new LinkedHashMap<>();

    /**
     * 记一次群消息。登记本身不让存档变脏（闲聊不触发整份存档，见 Step32Test），
     * 群列表随下一次真正的存盘（出卡、改卡、改群设置）一起写入；没写入的群下次来消息时会重新登记。
     */
    GroupInfo touchGroup(String gid, String name, String pkg, long ts) {
        if (blank(gid)) return null;
        GroupInfo g = groups.get(gid);
        if (g == null) {
            if (groups.size() >= MAX_GROUPS) evictOldGroup();
            g = new GroupInfo();
            g.id = gid;
            g.name = blank(name) ? gid : name.trim();
            g.pkg = pkg == null ? "" : pkg;
            groups.put(gid, g);
        } else if (!blank(name)) {
            g.name = name.trim();
        }
        g.count++;
        if (ts > g.lastTs) g.lastTs = ts;
        return g;
    }

    /** 群太多时丢掉最久没消息的“普通”群；设过重点关注 / 不用管的群一直保留。 */
    private void evictOldGroup() {
        GroupInfo oldest = null;
        for (GroupInfo g : groups.values()) {
            if (!MODE_NORMAL.equals(g.mode)) continue;
            if (oldest == null || g.lastTs < oldest.lastTs) oldest = g;
        }
        if (oldest != null) groups.remove(oldest.id);
    }

    public String groupMode(String gid) {
        GroupInfo g = gid == null ? null : groups.get(gid);
        return g == null ? MODE_NORMAL : g.mode;
    }

    /** 群列表：重点关注在前、不用管在后，同类按最近消息时间倒序。 */
    public List<GroupInfo> groupList() {
        // 升级前就有的卡片，它们所在的群还没登记过：按卡片补登记，列表里不会是空的。
        for (Card c : cards) {
            String key = groupKeyOf(c);
            if (blank(key) || groups.containsKey(key)) continue;
            String pkg = c.sourcePackageName();
            String app = PlatformNotice.appName(pkg);
            GroupInfo g = new GroupInfo();
            g.id = key;
            g.name = app != null ? app : blank(c.groupName) ? key : c.groupName;
            g.pkg = pkg == null ? "" : pkg;
            g.lastTs = c.sourceTs;
            groups.put(key, g);
        }
        List<GroupInfo> out = new ArrayList<>(groups.values());
        out.sort((a, b) -> {
            int ra = MODE_FOCUS.equals(a.mode) ? 0 : MODE_IGNORE.equals(a.mode) ? 2 : 1;
            int rb = MODE_FOCUS.equals(b.mode) ? 0 : MODE_IGNORE.equals(b.mode) ? 2 : 1;
            if (ra != rb) return Integer.compare(ra, rb);
            return Long.compare(b.lastTs, a.lastTs);
        });
        return out;
    }

    /** 卡片所属的管理单元：聊天群按群编号，学习平台按整个 App。 */
    public String groupKeyOf(Card c) {
        if (c == null) return null;
        String pkg = c.sourcePackageName();
        String app = PlatformNotice.appName(pkg);
        return app != null ? pkg + "|" + app : c.groupId;
    }

    private boolean inGroup(Card c, GroupInfo g) {
        if (g.isPlatform()) return g.pkg.equals(c.sourcePackageName());
        return g.id.equals(c.groupId);
    }

    /** 这个群现在有几张未移除的卡。 */
    public int cardCount(String gid) {
        GroupInfo g = groups.get(gid);
        if (g == null) return 0;
        int n = 0;
        for (Card c : cards) if (inGroup(c, g)) n++;
        return n;
    }

    /**
     * 改群的处理方式。设为“不用管”时，这个群现有的卡一起移到“已移除的卡片”（可恢复）。
     * 群还没登记过（老卡片所在的群）时用 name / pkg 先登记。
     *
     * @return 因“不用管”被移除的卡片数
     */
    public int setGroupMode(String gid, String name, String pkg, String mode, long now) {
        if (blank(gid)) return 0;
        if (!MODE_FOCUS.equals(mode) && !MODE_IGNORE.equals(mode)) mode = MODE_NORMAL;
        GroupInfo g = groups.get(gid);
        if (g == null) {
            g = new GroupInfo();
            g.id = gid;
            g.name = blank(name) ? gid : name.trim();
            g.pkg = pkg == null ? "" : pkg;
            groups.put(gid, g);
        }
        if (!g.mode.equals(mode)) g.aiVersion = java.util.UUID.randomUUID().toString();
        g.mode = mode;
        int removed = 0;
        if (MODE_IGNORE.equals(mode)) {
            for (Card c : new ArrayList<>(cards)) {
                if (inGroup(c, g)) {
                    cards.remove(c);
                    c.removedAt = now;
                    removedCards.add(c);
                    removed++;
                }
            }
        }
        notifyChanged();
        return removed;
    }

    public void clearLocalData() {
        aiEpoch = java.util.UUID.randomUUID().toString();
        cards.clear();
        removedCards.clear();
        candidates.clear();
        senderStat.clear();
        recent.clear();
        nIn = nNoise = nLevel3 = nPromoted = 0;
        notifyChanged();
    }

    public List<Card> all() { return cards; }

    /**
     * 学习平台（学习通 / 知到 / 雨课堂）自己发出的通知。
     *
     * 和群聊不同：发送方就是平台，格式是模板句，不需要判断“是不是老师说的”。
     * 这里只做纯文本解析：是不是一件要做的事、叫什么、哪门课、什么时候截止、是新发布还是改期 / 撤回。
     * 纯 Java，不依赖 android，方便在电脑上直接跑测试。
     *
     * 规则是按平台通知常见写法写的，还没有用真实样本校准；监听器会把这几个平台的原始通知
     * 记进诊断日志（kind = “平台”），拿到真实样本后再补规则。
     */
    public static final class PlatformNotice {

        /** 包名 → 显示名。 */
        static final String[][] APPS = {
                {"com.chaoxing.mobile", "学习通"},
                {"com.able.wisdomtree", "知到"},
                {"com.xuetangx.ykt", "雨课堂"},
        };

        public static final String NEW = "new";
        public static final String UPDATE = "update";
        public static final String REVOKE = "revoke";

        private PlatformNotice() {
        }

        public static boolean isPlatform(String pkg) {
            return appName(pkg) != null;
        }

        /** 平台显示名；不是学习平台返回 null。 */
        public static String appName(String pkg) {
            if (pkg == null) return null;
            for (String[] app : APPS) {
                if (app[0].equals(pkg)) return app[1];
            }
            return null;
        }

        /** 解析结果。 */
        public static final class Notice {
            /** todo 或 exam。 */
            public String kind;
            public String title;
            /** 课程名，可能为空串。 */
            public String course;
            /** 截止时间，0 表示通知里没写。 */
            public long deadline;
            /** 时间来自明确的日期时刻（而不是只有“明天”这类）。 */
            public boolean timeSure;
            /** new / update / revoke。 */
            public String action;
            /** 同一件事的去重键：课程 + 标题，去空白。 */
            public String key;
            /** 原文（标题 + 正文）。 */
            public String raw;
        }

        // ---------- 词表 ----------
        /** 是一件“要做的事”：作业类。 */
        private static final String[] TASK_WORDS = {
                "作业", "习题", "练习", "实验报告", "报告", "论文", "讨论", "问卷", "任务点", "任务", "预习", "复习题",
        };
        /** 考试类：出考试卡。 */
        private static final String[] EXAM_WORDS = {
                "考试", "测验", "小测", "随堂测", "测试", "期中", "期末",
        };
        /** 不是待办：互动消息、已结束的事、实时课堂事件。（成绩、签到单独出卡，见下面两个词表。） */
        private static final String[] NOT_TODO = {
                "已截止", "已结束", "已过期", "答案已公布", "解析已",
                "点赞", "回复了你", "评论了", "关注了你", "私信",
                "直播", "正在上课", "开始上课", "课堂已开始", "弹幕",
        };
        /** 出成绩了：出一张“成绩”卡，当天看一下。只认“已批改/得分”这类说法，“计入平时成绩”不算。 */
        private static final String[] GRADE_WORDS = {
                "已批改", "批改完成", "被批改", "已被批改", "得分", "评分", "出分", "成绩已", "成绩公布", "公布成绩",
                "公布了成绩", "查看成绩", "分数已", "已评阅", "已评分",
        };
        /** 签到：出一张“签到”卡，截止时间按“N 分钟内”推算。签过了、结束了的不出。 */
        private static final String[] SIGNIN_DONE = {
                "已签到", "签到成功", "签到已结束", "签到结束", "补签成功", "签到已截止",
        };
        private static final Pattern P_WITHIN = Pattern.compile("(\\d{1,3})\\s*(分钟|小时)\\s*(?:之内|内|后截止|后结束|有效)");
        private static final Pattern P_SCORE = Pattern.compile("(?:得分|成绩|分数|评分)(?:为|是|[：:])?\\s*(\\d{1,3}(?:\\.\\d{1,2})?)\\s*分?");
        /** 签到没写时限时，默认 10 分钟内有效。 */
        static final long SIGNIN_DEFAULT_MS = 10 * 60_000L;
        private static final String[] REVOKE_WORDS = {"撤回", "已删除", "删除了", "已取消", "取消了", "作废"};
        private static final String[] UPDATE_WORDS = {"延期", "延长", "延至", "推迟", "顺延", "调整", "修改了截止", "更改", "变更"};
        /** 通知标题里这些词说明它是泛称（“作业通知”），不是课程名。 */
        private static final String[] GENERIC_TITLE = {
                "通知", "提醒", "消息", "学习通", "雨课堂", "知到", "智慧树", "超星", "系统", "助手", "待办",
        };

        private static final Pattern P_BRACKET = Pattern.compile("【([^】]{1,30})】");
        private static final Pattern P_BOOK = Pattern.compile("《([^》]{1,40})》");
        private static final Pattern P_QUOTE = Pattern.compile("[「“\"]([^」”\"]{1,40})[」”\"]");
        private static final Pattern P_LABEL_NAME = Pattern.compile(
                "(?:作业|测验|考试|小测|讨论|任务点|任务|问卷|练习)[：:]\\s*([^，。；,;\\n]{2,30}?)(?=\\s*(?:[，。；,;\\n]|将于|请于|需于|截止|$))");
        private static final Pattern P_COURSE_LABEL = Pattern.compile("(?:课程|所属课程)[：:]\\s*([^，。；,;\\n]{1,30})");
        private static final Pattern P_START_LABEL = Pattern.compile("(?:开始|发布|开放|布置)(?:时间|日期)[：:]?\\s*[^，。；,;\\n]*");
        private static final Pattern P_END_LABEL = Pattern.compile("(?:截止|结束|截至|到期)(?:时间|日期)?[：:]\\s*");
        private static final Pattern P_RELATIVE = Pattern.compile(
                "(?:还有|还剩|剩余|将在|将于|距离截止(?:还有)?)\\s*(\\d{1,3})\\s*(分钟|小时|天)\\s*(?:后)?\\s*(?:截止|结束|到期)?");
        private static final Pattern P_LEAD_NOISE = Pattern.compile(
                "^(?:【[^】]*】|\\[[^\\]]*\\]|您|你|有|一个|一份|新的|新|老师|教师|发布了|布置了|发布|布置|提醒|通知|[：:，,\\s])+");

        /**
         * 解析一条平台通知；不是待办（成绩、签到、互动消息、没有任务词）时返回 null。
         *
         * @param title 通知标题（可能是课程名，也可能是“作业通知”这类泛称）
         * @param text  通知正文
         * @param ts    通知时间
         */
        public static Notice parse(String pkg, String title, String text, long ts) {
            String app = appName(pkg);
            if (app == null) return null;
            String t = title == null ? "" : title.trim();
            String body = text == null ? "" : text.trim();
            String raw = t.isEmpty() ? body : body.isEmpty() ? t : t + "\n" + body;
            if (raw.isEmpty()) return null;
            String flat = raw.replace('\n', ' ');

            if (containsAny(flat, NOT_TODO_ALWAYS)) return null;
            // 签到：要马上去点，截止时间按“N 分钟内”推算。
            if (flat.contains("签到")) {
                if (containsAny(flat, SIGNIN_DONE)) return null;
                Notice n = new Notice();
                n.raw = raw;
                n.kind = KIND_SIGNIN;
                n.action = NEW;
                n.course = course(t, flat);
                n.title = n.course.isEmpty() ? "签到" : "签到：" + n.course;
                long within = SIGNIN_DEFAULT_MS;
                Matcher w = P_WITHIN.matcher(flat);
                if (w.find()) {
                    long v = Long.parseLong(w.group(1));
                    within = "小时".equals(w.group(2)) ? v * 3600_000L : v * 60_000L;
                }
                n.deadline = ts + within;
                n.timeSure = true;
                // 同一门课半小时内的签到通知算同一次。
                n.key = (n.course + "|签到|" + (ts / (30 * 60_000L))).replaceAll("\\s+", "");
                return n;
            }
            // 出成绩：当天看一下就行。
            if (containsAny(flat, GRADE_WORDS)) {
                Notice n = new Notice();
                n.raw = raw;
                n.kind = KIND_GRADE;
                n.action = NEW;
                n.course = course(t, flat);
                // 事项名只从正文里找，避免把“作业通知”这类标题当成事项。
                n.title = gradeTitle(body.isEmpty() ? flat : body.replace('\n', ' '), n.course);
                n.deadline = endOfDay(ts);
                n.timeSure = true;
                n.key = (n.course + "|" + n.title).replaceAll("\\s+", "");
                return n;
            }

            boolean exam = containsAny(flat, EXAM_WORDS);
            boolean task = exam || containsAny(flat, TASK_WORDS);
            if (!task) return null;
            if (containsAny(flat, NOT_TODO)) return null;

            Notice n = new Notice();
            n.raw = raw;
            n.kind = exam ? "exam" : "todo";
            n.action = containsAny(flat, REVOKE_WORDS) ? REVOKE : containsAny(flat, UPDATE_WORDS) ? UPDATE : NEW;
            n.course = course(t, flat);
            n.title = itemTitle(flat, n.course, exam);

            long[] d = deadline(flat, ts);
            n.deadline = d[0];
            n.timeSure = d[1] == 1;
            // 通知里的截止时间已经过了：不是要做的事（多半是“已截止”换了种说法）。
            if (n.deadline > 0 && n.deadline < ts && !REVOKE.equals(n.action)) return null;
            n.key = (n.course + "|" + n.title).replaceAll("\\s+", "");
            return n;
        }

        /** 课程名：【课程】、“课程：xx”，或者不是泛称的通知标题。 */
        static String course(String title, String flat) {
            Matcher m = P_COURSE_LABEL.matcher(flat);
            if (m.find()) return m.group(1).trim();
            m = P_BRACKET.matcher(flat);
            while (m.find()) {
                String c = m.group(1).trim();
                if (!containsAny(c, GENERIC_TITLE) && !containsAny(c, TASK_WORDS) && !containsAny(c, EXAM_WORDS)) return c;
            }
            if (!title.isEmpty() && title.length() <= 30 && !containsAny(title, GENERIC_TITLE)
                    && !containsAny(title, TASK_WORDS) && !containsAny(title, EXAM_WORDS)) return title;
            // 雨课堂、知到常见写法：“数据结构 老师发布了新作业”“线性代数 王老师布置了…”
            m = P_COURSE_TEACHER.matcher(flat);
            while (m.find()) {
                String c = m.group(1).trim();
                if (!containsAny(c, GENERIC_TITLE) && !containsAny(c, TASK_WORDS) && !containsAny(c, EXAM_WORDS)) return c;
            }
            return "";
        }

        public static final String KIND_SIGNIN = "signin";
        public static final String KIND_GRADE = "grade";
        /** 互动消息：不管里面提没提到签到、成绩，都不出卡。 */
        private static final String[] NOT_TODO_ALWAYS = {"点赞", "回复了你", "评论了", "关注了你", "私信"};
        private static final Pattern P_GRADE_TAIL = Pattern.compile(
                "(?:已经|已被|已|被)?(?:批改|评阅|评分|出分|公布|完成批改).*$");

        /** 成绩卡标题：“成绩：第二次作业 · 92分”。 */
        static String gradeTitle(String flat, String course) {
            String name = null;
            Matcher m = P_BOOK.matcher(flat);
            while (m.find() && name == null) {
                String c = m.group(1).trim();
                if (!c.equals(course)) name = c;
            }
            if (name == null) {
                for (String clause : flat.split("[，。；！？,;!?\\n]")) {
                    String c = clause.trim();
                    if (!(containsAny(c, TASK_WORDS) || containsAny(c, EXAM_WORDS))) continue;
                    c = P_BRACKET.matcher(c).replaceAll("");
                    c = P_GRADE_TAIL.matcher(c).replaceFirst("");
                    c = P_LEAD_NOISE.matcher(c).replaceFirst("").trim();
                    c = c.replaceAll("^(?:你的|您的)", "").trim();
                    if (!course.isEmpty() && c.startsWith(course)) c = c.substring(course.length()).trim();
                    c = c.replaceAll("^[的：:\\s]+", "").replaceAll("[的\\s]+$", "");
                    if (c.length() >= 2) { name = c; break; }
                }
            }
            String title = "成绩" + (name == null || name.isEmpty() ? (course.isEmpty() ? "" : "：" + course) : "：" + name);
            Matcher s = P_SCORE.matcher(flat);
            if (s.find()) title += " · " + s.group(1) + "分";
            return limit(title);
        }

        /** 当天 23:59。 */
        static long endOfDay(long ts) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(ts);
            c.set(Calendar.HOUR_OF_DAY, 23);
            c.set(Calendar.MINUTE, 59);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        }

        private static final Pattern P_COURSE_TEACHER = Pattern.compile(
                "(?:^|[\\s，,])([^\\s，,：:。【】《》]{2,20})\\s+[^\\s，,]{0,4}(?:老师|教师|助教)"
                        + "(?:发布|布置|发起|新建|上传|提醒|开启|推送|添加)");

        /** 事项名：《名称》/「名称」优先，否则取含任务词的第一个子句并去掉“老师发布了”等前缀。 */
        static String itemTitle(String flat, String course, boolean exam) {
            String label = exam ? examLabel(flat) : taskLabel(flat);
            String name = null;
            Matcher m = P_BOOK.matcher(flat);
            while (m.find() && name == null) {
                String c = m.group(1).trim();
                if (!c.equals(course)) name = c;
            }
            if (name == null) {
                m = P_QUOTE.matcher(flat);
                if (m.find()) name = m.group(1).trim();
            }
            if (name == null) {
                // “布置了新作业：链表练习”“测验：第一单元”
                m = P_LABEL_NAME.matcher(flat);
                if (m.find()) name = m.group(1).trim();
            }
            if (name != null && !name.isEmpty()) {
                boolean labelled = containsAny(name, TASK_WORDS) || containsAny(name, EXAM_WORDS);
                return limit(labelled ? name : label + "：" + name);
            }
            for (String clause : flat.split("[，。；！？,;!?\\n]")) {
                String c = clause.trim();
                if (!(containsAny(c, TASK_WORDS) || containsAny(c, EXAM_WORDS))) continue;
                c = P_BRACKET.matcher(c).replaceAll("");
                c = c.replaceAll("(?:将于|请于|需于|于|在).*$", "");
                c = c.replaceAll("(?:截止|结束|即将|快要|马上).*$", "");
                c = P_LEAD_NOISE.matcher(c).replaceFirst("").trim();
                if (!course.isEmpty() && c.startsWith(course)) c = c.substring(course.length()).trim();
                c = c.replaceAll("^[的：:\\s]+", "");
                if (c.length() >= 2) return limit(c);
            }
            return label;
        }

        private static String taskLabel(String flat) {
            for (String w : TASK_WORDS) if (flat.contains(w)) return "任务点".equals(w) || "任务".equals(w) ? "任务" : w;
            return "作业";
        }

        private static String examLabel(String flat) {
            for (String w : EXAM_WORDS) if (flat.contains(w)) return "期中".equals(w) || "期末".equals(w) ? w + "考试" : w;
            return "考试";
        }

        /**
         * 截止时间：先找“截止时间：xx”这类标签后面的时间，再去掉“开始/发布时间”整段后解析全文，
         * 最后识别“还有 2 小时截止”这类相对时间。返回 {时间, 是否确定(1/0)}。
         */
        static long[] deadline(String flat, long ts) {
            Matcher label = P_END_LABEL.matcher(flat);
            if (label.find()) {
                Engine.Deadline d = Engine.parseDeadline(flat.substring(label.end()), ts);
                if (d.ok()) return new long[]{d.value, "high".equals(d.conf) ? 1 : 0};
            }
            String withoutStart = P_START_LABEL.matcher(flat).replaceAll(" ");
            Engine.Deadline d = Engine.parseDeadline(withoutStart, ts);
            if (d.ok()) return new long[]{d.value, "high".equals(d.conf) ? 1 : 0};
            Matcher rel = P_RELATIVE.matcher(flat);
            if (rel.find()) {
                long n = Long.parseLong(rel.group(1));
                String unit = rel.group(2);
                long ms = "分钟".equals(unit) ? 60_000L : "小时".equals(unit) ? 3600_000L : 24 * 3600_000L;
                // 相对时间按通知时间推算，精度只到分钟，标为不确定让用户核对。
                long v = ts + n * ms;
                return new long[]{v - v % 60_000L, 0};
            }
            return new long[]{0, 0};
        }

        private static String limit(String s) {
            s = s.trim();
            return s.length() > 30 ? s.substring(0, 30) + "…" : s;
        }

        private static boolean containsAny(String s, String[] words) {
            if (s == null) return false;
            for (String w : words) if (s.contains(w)) return true;
            return false;
        }
    }
}
