package com.nopalou;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * “AI 识别”设置：任意 OpenAI 兼容接口（默认阿里云百炼 / 通义千问）。
 * 密钥只存在本机私有存储里；没填密钥时整条上一级链路都不启用，应用退回纯本机识别。
 * 也可以改用端侧小模型（backend = local）：消息不出手机，不需要密钥、不计每日额度，但不支持截图识别。
 */
final class AiConfig {
    static final String PREFS = "ai";
    static final String DEFAULT_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    static final String DEFAULT_MODEL = "qwen-plus";
    static final String DEFAULT_VISION = "qwen-vl-plus";
    /** 有网就处理（攒 20 秒一批）。 */
    static final String MODE_NOW = "now";
    /** 只在充电时批量处理，省电省流量。 */
    static final String MODE_CHARGING = "charging";
    /** 复核走云端 OpenAI 兼容接口。 */
    static final String BACKEND_CLOUD = "cloud";
    /** 复核走手机上的端侧小模型（llama.cpp）。 */
    static final String BACKEND_LOCAL = "local";
    /** 云端每批消息数。 */
    static final int CLOUD_BATCH = 8;

    final boolean enabled;
    final String base, key, model, vision, mode, backend;
    /** 是否使用端侧小模型。 */
    final boolean local;
    final String keyError;
    final Context context;
    final int dailyLimit;

    private AiConfig(Context c) {
        context = c.getApplicationContext() == null ? c : c.getApplicationContext();
        SharedPreferences p = prefs(context);
        String secret = "", issue = "";
        try { secret = AiSecrets.read(p); }
        catch (Exception e) { issue = "密钥无法从系统密钥库读取，请重新填写并保存"; }
        key = secret; keyError = issue;
        dailyLimit = AiBudgetCore.limit(p.getInt("daily_limit", 30));
        enabled = p.getBoolean("enabled", false);
        base = trimBase(p.getString("base", DEFAULT_BASE));
        model = orDefault(p.getString("model", DEFAULT_MODEL), DEFAULT_MODEL);
        // 图片模型可以留空：所选服务不支持看图时只做文字复核
        vision = p.getString("vision", DEFAULT_VISION).trim();
        mode = MODE_CHARGING.equals(p.getString("mode", MODE_NOW)) ? MODE_CHARGING : MODE_NOW;
        backend = BACKEND_LOCAL.equals(p.getString("backend", BACKEND_CLOUD)) ? BACKEND_LOCAL : BACKEND_CLOUD;
        local = BACKEND_LOCAL.equals(backend);
    }

    static AiConfig load(Context c) {
        return new AiConfig(c);
    }

    static SharedPreferences prefs(Context c) {
        Context app = c.getApplicationContext();
        return (app == null ? c : app).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 云端：开关打开、填了密钥、地址是 https 才算可用。本机：开关打开、模型文件就绪、推理库可在这台手机上运行。 */
    boolean ready() {
        if (local) return enabled && LocalModelStore.present(context) && LocalLlm.supported();
        return enabled && !key.isEmpty() && base.startsWith("https://");
    }

    static boolean ready(Context c) {
        return load(c).ready();
    }

    /** 能做截图识别：可用且填了图片模型。 */
    boolean visionReady() {
        return !local && ready() && !vision.isEmpty();
    }

    /** 每次复核请求最多带几条消息。 */
    int batchSize() {
        return local ? LocalLlmCore.BATCH : CLOUD_BATCH;
    }

    static void save(Context c, boolean enabled, String backend, String base, String key, String model, String vision, String mode, int dailyLimit) {
        final String encrypted;
        try { encrypted = AiSecrets.encode(key == null ? "" : key.trim()); }
        catch (Exception e) { throw new IllegalStateException("系统密钥库暂时不可用，设置未保存，请稍后重试"); }
        boolean saved = prefs(c).edit()
                .putBoolean("enabled", enabled)
                .putString("base", trimBase(base))
                .putString("key_cipher", encrypted).remove("key")
                .putInt("daily_limit", AiBudgetCore.limit(dailyLimit))
                .putString("model", orDefault(model, DEFAULT_MODEL))
                .putString("vision", vision == null ? "" : vision.trim())
                .putString("mode", MODE_CHARGING.equals(mode) ? MODE_CHARGING : MODE_NOW)
                .putString("backend", BACKEND_LOCAL.equals(backend) ? BACKEND_LOCAL : BACKEND_CLOUD)
                .commit();
        if (!saved) throw new IllegalStateException("设置保存失败，请重试");
    }

    static String trimBase(String b) {
        String v = b == null || b.trim().isEmpty() ? DEFAULT_BASE : b.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        if (v.endsWith("/chat/completions")) v = v.substring(0, v.length() - "/chat/completions".length());
        return v;
    }

    private static String orDefault(String v, String d) {
        return v == null || v.trim().isEmpty() ? d : v.trim();
    }

    /** 设置页上的一句话状态。 */
    static String summary(Context c) {
        AiConfig a = load(c);
        if (!a.enabled) return "未开启 · 只用本机规则识别";
        if (a.local) {
            if (!LocalLlm.supported()) return "本机模型不可用：" + LocalLlm.unavailableReason();
            if (!LocalModelStore.present(c)) return LocalModelStore.downloading(c) ? "本机模型下载中，完成后自动启用" : "已选本机模型，但还没有下载或导入模型文件";
        } else {
            if (!a.keyError.isEmpty()) return a.keyError;
            if (!a.ready()) return "已开启但还没填好接口";
        }
        if (AiQueue.storageBlocked(c)) return "队列存档异常 · 可先清空队列后重试，本机卡片不受影响";
        int n = AiQueue.size(c);
        int failed = AiQueue.failedCount(c);
        if (a.local) {
            return "本机模型 · " + LocalModelStore.name(c) + " · " + (MODE_CHARGING.equals(a.mode) ? "充电时批量复核" : "随时复核")
                    + (n > 0 ? " · 排队 " + n + " 条" : " · 无待处理请求")
                    + (failed > 0 ? " · " + failed + " 条失败，请查看原因" : "")
                    + "\n消息只在手机上处理，不计每日请求上限";
        }
        return (MODE_CHARGING.equals(a.mode) ? "充电时批量复核" : "有网即复核")
                + (n > 0 ? " · 排队 " + n + " 条" : " · 无待处理请求")
                + (failed > 0 ? " · " + failed + " 条失败，请查看原因" : "")
                + "\n" + AiBudget.summary(c, a.dailyLimit);
    }
}
