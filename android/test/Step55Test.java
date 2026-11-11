package com.nopalou;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 端侧小模型的纯逻辑：输出语法、模型文件头、思考段清理、批量和大小提示。推理库本身见 LocalLlmSmoke。 */
public final class Step55Test {
    static int count;
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); count++; System.out.println("PASS " + name); }

    public static void main(String[] args) {
        byte[] v3 = {'G', 'G', 'U', 'F', 3, 0, 0, 0}, v2 = {'G', 'G', 'U', 'F', 2, 0, 0, 0};
        check("GGUF v3 文件头", LocalLlmCore.isGguf(v3));
        check("GGUF v2 文件头", LocalLlmCore.isGguf(v2));
        check("未知版本拒绝", !LocalLlmCore.isGguf(new byte[]{'G', 'G', 'U', 'F', 9, 0, 0, 0}));
        check("其他文件拒绝", !LocalLlmCore.isGguf("PK\u0003\u0004abcd".getBytes()) && !LocalLlmCore.isGguf(null) && !LocalLlmCore.isGguf(new byte[4]));

        check("去掉思考段", "{\"a\":1}".equals(LocalLlmCore.stripThink("<think>先想想 {x}</think>\n{\"a\":1}")));
        check("多段与未闭合思考", "{}".equals(LocalLlmCore.stripThink("<think>a</think>{}<think>没写完")));
        check("没有思考段原样", "{\"ok\":true}".equals(LocalLlmCore.stripThink(" {\"ok\":true} ")));
        check("空输入", "".equals(LocalLlmCore.stripThink(null)));

        check("Qwen3 关思考", LocalLlmCore.wantsNoThink("Qwen3-1.7B-Q3_K_M.gguf"));
        check("Qwen2.5 不加指令", !LocalLlmCore.wantsNoThink("qwen2.5-1.5b-instruct-q4_k_m.gguf") && !LocalLlmCore.wantsNoThink(null));

        String g = LocalLlmCore.judgeGrammar(3);
        check("语法有根规则", g.startsWith("root ::= "));
        for (int i = 1; i <= 3; i++) check("第 " + i + " 项规则与固定编号", g.contains("item" + i + " ::= \"{\\\"id\\\":\\\"" + i + "\\\""));
        check("不多出第 4 项", !g.contains("item4"));
        String[] fields = {"todo", "kind", "title", "deadline", "time_sure", "deadline_evidence", "place", "channel", "bring", "confidence", "note"};
        int last = -1;
        boolean ordered = true;
        String first = g.substring(g.indexOf("item1 ::="), g.indexOf('\n', g.indexOf("item1 ::=")));
        for (String f : fields) { int at = first.indexOf("\\\"" + f + "\\\":"); ordered &= at > last; last = at; }
        check("字段齐全且顺序与云端提示一致", ordered);
        check("截止时间只能是空串或 yyyy-MM-dd HH:mm", g.contains("deadline ::= \"\\\"\\\"\" | \"\\\"\" [0-9] [0-9] [0-9] [0-9] \"-\""));
        check("kind 只能是 todo/exam", g.contains("kind ::= \"\\\"todo\\\"\" | \"\\\"exam\\\"\""));
        check("字段长度有上限，生成必然收尾", g.contains("ch{0,24}") && g.contains("ch{0,60}") && !g.contains("ch*"));
        Matcher m = Pattern.compile("(?m)^([a-z0-9_]+) ::=").matcher(g);
        java.util.Set<String> defined = new java.util.HashSet<>();
        while (m.find()) defined.add(m.group(1));
        check("引用的规则都有定义", defined.containsAll(java.util.Arrays.asList("root", "item1", "item2", "item3", "bool", "kind", "deadline", "conf", "title", "evidence", "short", "note", "ch")));
        boolean threw = false;
        try { LocalLlmCore.judgeGrammar(0); } catch (IllegalArgumentException e) { threw = true; }
        check("零项拒绝", threw);
        check("单项语法", LocalLlmCore.judgeGrammar(1).contains("item1") && !LocalLlmCore.judgeGrammar(1).contains("item2"));

        check("本机每批 3 条", LocalLlmCore.BATCH == 3);
        check("输出上限放得进上下文", LocalLlmCore.JUDGE_MAX_TOKENS < LocalLlmCore.CONTEXT / 2);
        check("建议上限 1 GB、硬上限 1.5 GB", LocalLlmCore.SOFT_LIMIT_BYTES == 1L << 30 && LocalLlmCore.HARD_LIMIT_BYTES == 1536L << 20);
        check("大小文字", "1.00 GB".equals(LocalLlmCore.sizeText(1L << 30)) && "940 MB".equals(LocalLlmCore.sizeText(940L << 20)));
        check("推荐地址为 https", LocalLlmCore.RECOMMENDED_URL.startsWith("https://") && LocalLlmCore.RECOMMENDED_URL.endsWith(".gguf"));
        System.out.println("PASS " + count);
    }
}
