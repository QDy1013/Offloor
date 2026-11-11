package com.nopalou;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本机推理库冒烟测试：电脑上加载 liboffloor_llm（本机版）和随机权重的微型模型，
 * 检查加载、对话模板、语法约束生成、取消与释放。权重随机，所以只验格式不验内容。
 * 由 eval/test_local_llm_native.py 调用：java -Djava.library.path=<库目录> com.nopalou.LocalLlmSmoke <模型>
 */
public final class LocalLlmSmoke {
    static int count;
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); count++; System.out.println("PASS " + name); }

    public static void main(String[] args) throws Exception {
        File model = new File(args[0]);
        check("推理库可加载", LocalLlm.supported());
        boolean missing = false;
        try { LocalLlm.open(new File(model.getParentFile(), "不存在.gguf"), 2).close(); }
        catch (java.io.IOException expected) { missing = true; }
        check("缺少模型文件时报错而不崩溃", missing);
        File junk = File.createTempFile("not-a-model", ".gguf");
        java.nio.file.Files.write(junk.toPath(), "hello".getBytes("UTF-8"));
        boolean bad = false;
        try { LocalLlm.open(junk, 2).close(); } catch (java.io.IOException expected) { bad = true; }
        junk.delete();
        check("不是模型的文件加载失败而不崩溃", bad);

        try (LocalLlm llm = LocalLlm.open(model, 2)) {
            check("上下文长度按设置", llm.contextSize() == LocalLlmCore.CONTEXT);
            String ping = llm.generate("", "只回复 JSON：{\"ok\":true}", LocalLlmCore.PING_GRAMMAR, 20);
            check("测试连接的语法只允许 {\"ok\":true}", "{\"ok\":true}".equals(ping));

            String user = "{\"messages\":[{\"id\":\"1\",\"text\":\"周五前交实验报告📄\"},{\"id\":\"2\",\"text\":\"今晚吃啥\"},{\"id\":\"3\",\"text\":\"明早8点考试，带学生证\"}]}";
            long t0 = System.nanoTime();
            String out = llm.generate("你是复核模块。对每条消息判断是否要我做事，只输出 JSON。", user, LocalLlmCore.judgeGrammar(3), LocalLlmCore.JUDGE_MAX_TOKENS);
            System.out.println("用时 " + (System.nanoTime() - t0) / 1_000_000 + " ms，输出 " + out.length() + " 字");
            check("复核输出以 items 开头并闭合", out.startsWith("{\"items\":[") && out.endsWith("]}"));
            for (int i = 1; i <= 3; i++) check("第 " + i + " 项编号固定", out.contains("{\"id\":\"" + i + "\",\"todo\":"));
            Matcher m = Pattern.compile("\"deadline\":\"([^\"]*)\"").matcher(out);
            int deadlines = 0;
            while (m.find()) { deadlines++; check("截止时间为空或 yyyy-MM-dd HH:mm", m.group(1).isEmpty() || m.group(1).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")); }
            check("每项都有截止字段", deadlines == 3);
            check("输出可再次生成（上一轮缓存已清空）", llm.generate("", "只回复 JSON：{\"ok\":true}", LocalLlmCore.PING_GRAMMAR, 20).equals("{\"ok\":true}"));

            Thread stopper = new Thread(() -> { try { Thread.sleep(200); } catch (InterruptedException ignored) {} llm.cancel(); });
            stopper.start();
            boolean cancelled = false;
            try { llm.generate("", "写一段很长的话", null, 3000); } catch (java.io.IOException e) { cancelled = e.getMessage().contains("取消"); }
            stopper.join();
            check("生成中途可以取消", cancelled);
            check("取消后仍可继续使用", llm.generate("", "只回复 JSON：{\"ok\":true}", LocalLlmCore.PING_GRAMMAR, 20).equals("{\"ok\":true}"));
            boolean tooLong = false;
            StringBuilder huge = new StringBuilder();
            for (int i = 0; i < 6000; i++) huge.append("很长的群消息");
            try { llm.generate("", huge.toString(), null, 100); } catch (java.io.IOException e) { tooLong = e.getMessage().contains("上下文"); }
            check("超出上下文时明确报错", tooLong);
            llm.close();
            llm.close();
            check("重复释放安全", true);
        }
        System.out.println("PASS " + count);
    }
}
