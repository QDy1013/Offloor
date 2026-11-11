package com.nopalou;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 端侧小模型的纯逻辑部分（不依赖 Android，JVM 上可测）：
 * 输出格式约束（GBNF 语法）、模型文件校验、推理输出清理和本机模式下的批量参数。
 */
final class LocalLlmCore {
    private LocalLlmCore() {}

    /** 推荐模型：Qwen2.5-1.5B-Instruct 的 Q4_K_M 量化。用户可以改下载地址或从文件导入任意 GGUF。 */
    static final String RECOMMENDED_NAME = "Qwen2.5-1.5B-Instruct · Q4_K_M";
    static final String RECOMMENDED_URL =
            "https://modelscope.cn/models/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/master/qwen2.5-1.5b-instruct-q4_k_m.gguf";
    /** 建议上限：超过它设置页会提示“偏大”，但仍允许使用。 */
    static final long SOFT_LIMIT_BYTES = 1024L * 1024 * 1024;
    /** 硬上限：再大的文件在手机上几乎跑不动，直接拒绝，避免占满存储。 */
    static final long HARD_LIMIT_BYTES = 1536L * 1024 * 1024;
    /** 本机模式每批消息数：小模型一次看太多条容易漏项、串号，也更容易超出上下文。 */
    static final int BATCH = 3;
    static final int CONTEXT = 4096;
    static final int JUDGE_MAX_TOKENS = 1500;

    /** GGUF 文件以 "GGUF" 四个字节开头，后跟小端版本号（目前为 2 或 3）。 */
    static boolean isGguf(byte[] head) {
        if (head == null || head.length < 8) return false;
        if (head[0] != 'G' || head[1] != 'G' || head[2] != 'U' || head[3] != 'F') return false;
        int version = (head[4] & 0xff) | (head[5] & 0xff) << 8 | (head[6] & 0xff) << 16 | (head[7] & 0xff) << 24;
        return version >= 2 && version <= 3;
    }

    static String sizeText(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.CHINA, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        return String.format(Locale.CHINA, "%d MB", Math.max(1, Math.round(bytes / (1024.0 * 1024))));
    }

    /** Qwen3 等推理模型会先输出 <think>…</think>；去掉它，免得里面的花括号干扰 JSON 截取。 */
    static String stripThink(String s) {
        if (s == null) return "";
        String v = s;
        int open;
        while ((open = v.indexOf("<think>")) >= 0) {
            int close = v.indexOf("</think>", open);
            v = close < 0 ? v.substring(0, open) : v.substring(0, open) + v.substring(close + "</think>".length());
        }
        return v.trim();
    }

    /** Qwen3 系列支持在提示词里用 /no_think 关掉思考；其他模型不加，免得多出无意义的字。 */
    static boolean wantsNoThink(String modelName) {
        return modelName != null && modelName.toLowerCase(Locale.ROOT).contains("qwen3");
    }

    /**
     * 复核结果的输出语法：恰好 n 项、id 依次为 "1".."n"、字段齐全且顺序固定，截止时间只能是空串或 yyyy-MM-dd HH:mm。
     * 小模型最常见的失败是 JSON 不完整、漏项或编号错乱，用语法约束在采样阶段就排除。
     * 字段长度上限比提示词里的要求略宽，同时保证一批最多几百个 token 就能写完。
     */
    static String judgeGrammar(int n) {
        if (n < 1) throw new IllegalArgumentException("n");
        StringBuilder g = new StringBuilder();
        g.append("root ::= \"{\\\"items\\\":[\"");
        for (int i = 1; i <= n; i++) {
            if (i > 1) g.append(" \",\"");
            g.append(" item").append(i);
        }
        g.append(" \"]}\"\n");
        for (int i = 1; i <= n; i++) {
            g.append("item").append(i).append(" ::= \"{\\\"id\\\":\\\"").append(i).append("\\\",\\\"todo\\\":\" bool")
                    .append(" \",\\\"kind\\\":\" kind")
                    .append(" \",\\\"title\\\":\" title")
                    .append(" \",\\\"deadline\\\":\" deadline")
                    .append(" \",\\\"time_sure\\\":\" bool")
                    .append(" \",\\\"deadline_evidence\\\":\" evidence")
                    .append(" \",\\\"place\\\":\" short")
                    .append(" \",\\\"channel\\\":\" short")
                    .append(" \",\\\"bring\\\":\" short")
                    .append(" \",\\\"confidence\\\":\" conf")
                    .append(" \",\\\"note\\\":\" note")
                    .append(" \"}\"\n");
        }
        g.append("bool ::= \"true\" | \"false\"\n");
        g.append("kind ::= \"\\\"todo\\\"\" | \"\\\"exam\\\"\"\n");
        g.append("deadline ::= \"\\\"\\\"\" | \"\\\"\" [0-9] [0-9] [0-9] [0-9] \"-\" [0-1] [0-9] \"-\" [0-3] [0-9] \" \" [0-2] [0-9] \":\" [0-5] [0-9] \"\\\"\"\n");
        g.append("conf ::= \"0\" | \"1\" | \"0.\" [0-9] | \"0.\" [0-9] [0-9] | \"1.0\"\n");
        g.append("title ::= \"\\\"\" ch{0,24} \"\\\"\"\n");
        g.append("evidence ::= \"\\\"\" ch{0,60} \"\\\"\"\n");
        g.append("short ::= \"\\\"\" ch{0,40} \"\\\"\"\n");
        g.append("note ::= \"\\\"\" ch{0,30} \"\\\"\"\n");
        g.append("ch ::= [^\"\\\\\\x7F\\x00-\\x1F] | \"\\\\\" [\"\\\\/nt]\n");
        return g.toString();
    }

    /** 测试连接用：只允许 {"ok":true}。 */
    static final String PING_GRAMMAR = "root ::= \"{\\\"ok\\\":true}\"\n";

    static byte[] utf8(String s) { return (s == null ? "" : s).getBytes(StandardCharsets.UTF_8); }
}
