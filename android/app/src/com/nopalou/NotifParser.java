package com.nopalou;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通知解析的纯逻辑：单条/多条、发送者分隔符、折叠计数、占位符与纯计数清洗。
 * 纯 Java，不依赖 android，方便在电脑上直接跑测试。
 */
public final class NotifParser {

    /** 开头的折叠计数，例如 “[3条]”“[12条] ”，只去开头一次。 */
    private static final Pattern FOLD_COUNT =
            Pattern.compile("^\\s*\\[\\d+条\\]\\s*");

    /** 整段只有计数文字，例如 “3条新消息”“你有5条消息”。 */
    private static final Pattern COUNTER =
            Pattern.compile("^(你有)?\\d+条新?消息$");

    /** 整段只是一个占位符时视为空，夹在正文中间则保持原样。 */
    private static final String[] PLACEHOLDERS = {
            "[图片]", "[表情]", "[语音]", "[视频]",
            "[文件]", "[动画表情]", "[位置]", "[链接]",
    };

    /** 没有发送者时使用的默认名称，用于多条消息。 */
    private static final String FALLBACK_SENDER = "群友";

    /** 单条消息没有发送者分隔符、且 title 为空时的发送者。 */
    private static final String GROUP_SENDER = "群消息";

    /** 工具类，不实例化。 */
    private NotifParser() {
    }

    /** 解析出来的一条消息。 */
    public static final class Parsed {
        /** 发送者名称，永不为 null。 */
        public final String sender;
        /** 正文，永不为 null，且已去掉首尾空白。 */
        public final String body;
        /** 消息时间（毫秒），未知为 0。 */
        public final long time;

        /** 保存一条解析结果。 */
        Parsed(String sender, String body, long time) {
            this.sender = sender;
            this.body = body;
            this.time = time;
        }
    }

    /**
     * 把通知里的字段解析成消息列表。
     * 任何参数都可能为 null，任何情况下都不抛异常，也不返回 null。
     */
    public static List<Parsed> parse(String title, String text,
                                     String[] msgSenders, String[] msgTexts,
                                     long[] msgTimes, boolean isSummary) {
        // 结果列表先建好，后面所有分支都往这里加。
        List<Parsed> result = new ArrayList<Parsed>();
        // 系统生成的分组汇总通知没有可用的单条消息，直接返回空列表。
        if (isSummary) return result;
        // 有多条消息时优先按多条消息解析，忽略 text。
        if (msgTexts != null && msgTexts.length > 0) {
            // 逐条处理，保持通知里的原始顺序。
            for (int i = 0; i < msgTexts.length; i++) {
                // 正文先清洗，占位符和纯计数都会被洗成空串。
                String body = clean(msgTexts[i]);
                // 清洗后为空说明这条没有可用内容，跳过。
                if (body.isEmpty()) continue;
                // 取出对应的发送者，数组缺失或越界都当作没有发送者。
                String rawSender = msgSenders != null && i < msgSenders.length
                        ? msgSenders[i] : null;
                // 发送者去首尾空白。
                String sender = trim(rawSender);
                // 发送者为空时用默认名称。
                if (sender.isEmpty()) sender = FALLBACK_SENDER;
                // 时间缺失或越界时取 0。
                long time = msgTimes != null && i < msgTimes.length ? msgTimes[i] : 0L;
                // 加入结果列表。
                result.add(new Parsed(sender, body, time));
            }
            // 多条消息处理完毕，不再看 text。
            return result;
        }
        // 没有多条消息时退回使用正文 text。
        String cleaned = clean(text);
        // 正文清洗后为空，说明通知里没有可展示的内容。
        if (cleaned.isEmpty()) return result;
        String hint = "";
        if (cleaned.startsWith("[有全体消息]")) {
            cleaned = cleaned.substring("[有全体消息]".length()).trim();
            hint = "[有全体消息] ";
            if (cleaned.isEmpty() || isPlaceholder(cleaned) || isCounter(cleaned)) return result;
        }
        // 在清洗后的正文里找发送者分隔符。
        int sep = NotifFilter.senderSeparator(cleaned);
        if (sep >= 0) {
            // 分隔符之前是发送者，去掉首尾空白。
            String sender = trimmed(cleaned.substring(0, sep));
            // 分隔符之后是正文，去掉首尾空白。
            String body = trimmed(cleaned.substring(sep + 1));
            // 正文为空或是占位符时，这条消息没有实际内容。
            if (body.isEmpty() || isPlaceholder(body) || isCounter(body)) return result;
            // 发送者解析后为空时用默认名称，避免出现空字符串。
            if (sender.isEmpty()) sender = FALLBACK_SENDER;
            // 单条消息不知道时间，统一取 0。
            result.add(new Parsed(sender, hint + body, 0L));
            return result;
        }
        // 找不到分隔符：title 为空时说明来源不明，用“群消息”。
        String sender = trimmed(title).isEmpty() ? GROUP_SENDER : FALLBACK_SENDER;
        // 整段清洗后的文本就是正文。
        result.add(new Parsed(sender, hint + cleaned, 0L));
        // 返回只有一条的列表。
        return result;
    }

    /** 可提示用户去看的媒体占位符（表情、位置、链接不算）。 */
    private static final String[] MEDIA = {"[图片]", "[语音]", "[视频]", "[文件]"};

    /**
     * parse 会丢掉的“只有占位符”的消息里，挑出图片/语音/视频/文件，返回 body 为占位符本身。
     * 规则与 parse 对称：多条消息逐条看，单条看分隔符后的正文。不抛异常，不返回 null。
     */
    public static List<Parsed> media(String title, String text,
                                     String[] msgSenders, String[] msgTexts,
                                     long[] msgTimes, boolean isSummary) {
        List<Parsed> result = new ArrayList<Parsed>();
        if (isSummary) return result;
        if (msgTexts != null && msgTexts.length > 0) {
            for (int i = 0; i < msgTexts.length; i++) {
                String body = mediaOf(msgTexts[i]);
                if (body == null) continue;
                String sender = trim(msgSenders != null && i < msgSenders.length ? msgSenders[i] : null);
                if (sender.isEmpty()) sender = FALLBACK_SENDER;
                long time = msgTimes != null && i < msgTimes.length ? msgTimes[i] : 0L;
                result.add(new Parsed(sender, body, time));
            }
            return result;
        }
        if (text == null) return result;
        String s = FOLD_COUNT.matcher(text).replaceFirst("").trim();
        String hint = "";
        if (s.startsWith("[有全体消息]")) {
            s = s.substring("[有全体消息]".length()).trim();
            hint = "[有全体消息]";
        }
        int sep = NotifFilter.senderSeparator(s);
        String sender = sep >= 0 ? trimmed(s.substring(0, sep)) : (trimmed(title).isEmpty() ? GROUP_SENDER : FALLBACK_SENDER);
        String body = mediaOf(sep >= 0 ? s.substring(sep + 1) : s);
        if (body == null) return result;
        if (sender.isEmpty()) sender = FALLBACK_SENDER;
        result.add(new Parsed(sender, hint + body, 0L));
        return result;
    }

    /** 整段只是一个媒体占位符时返回它，否则 null。 */
    private static String mediaOf(String raw) {
        if (raw == null) return null;
        String s = FOLD_COUNT.matcher(raw).replaceFirst("").trim();
        for (String p : MEDIA) if (p.equals(s)) return p;
        return null;
    }

    /** 清洗正文：剥掉开头的折叠计数、去首尾空白，并把占位符和纯计数视为空。 */
    private static String clean(String raw) {
        // null 当作空串处理，避免后续空指针。
        if (raw == null) return "";
        // 只去掉开头的一次折叠计数，例如 “[3条] ”。
        String s = FOLD_COUNT.matcher(raw).replaceFirst("");
        // 去掉首尾空白。
        s = s.trim();
        // 整段只是占位符时视为没有内容。
        if (isPlaceholder(s)) return "";
        // 整段只是计数通知时同样视为没有内容。
        if (isCounter(s)) return "";
        // 返回清洗后的正文。
        return s;
    }

    /** 判断整段文本是不是单个占位符。 */
    private static boolean isPlaceholder(String s) {
        // 逐个比对固定的占位符列表。
        for (String p : PLACEHOLDERS) {
            // 完全相等才算占位符，夹在正文里的不处理。
            if (p.equals(s)) return true;
        }
        // 都不相等说明不是占位符。
        return false;
    }

    /** 判断整段文本是不是纯计数通知，例如 “3条新消息”。 */
    private static boolean isCounter(String s) {
        // 用正则整段匹配，匹配上说明没有任何正文。
        Matcher m = COUNTER.matcher(s);
        // 返回匹配结果。
        return m.matches();
    }

    /** 去首尾空白，null 返回空串。 */
    private static String trim(String s) {
        // null 统一成空串，调用方不用再判空。
        if (s == null) return "";
        // 去掉首尾空白字符。
        return s.trim();
    }

    /** 与 trim 同义，保留独立名字让语义更清楚。 */
    private static String trimmed(String s) {
        // 直接复用去空白逻辑。
        return trim(s);
    }
}
