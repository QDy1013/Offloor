package com.nopalou;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 校园同步脱敏自检报告（纯 Java）。
 * 仅输出状态枚举、计数、字段标签名与去掉查询参数的校方域名/路径，
 * 严禁包含学号、账号哈希、余额数值、姓名、宿舍房间、密码、Cookie 或任何令牌。
 */
final class CampusDiag {
    private static final String SENSITIVE_LABEL = ".*(?:密码|口令|验证码|身份证|证件|学号|姓名|宿舍|房间|楼栋|银行卡|卡号|token|ticket|cookie|secret|auth).*";

    private CampusDiag() { }

    static String report(CampusData data, String version, String device,
                         String syncPhase, String syncResult, int done, int total, String attention) {
        StringBuilder sb = new StringBuilder();
        sb.append("Offloor 校园同步脱敏自检\n");
        sb.append("版本：").append(safeText(version, "未知")).append(" · 设备：").append(safeText(device, "未知")).append('\n');
        boolean linked = data != null && data.owner != null && !data.owner.isEmpty();
        sb.append("账号绑定：").append(linked ? "已绑定" : "未绑定").append('\n');
        sb.append("同步状态：阶段=").append(safeText(syncPhase, "无"))
                .append(" · 结果=").append(safeText(syncResult, "无"))
                .append(" · 进度=").append(Math.max(0, done)).append('/').append(Math.max(0, total));
        if (attention != null && !attention.trim().isEmpty()) {
            sb.append(" · 待处理=").append(attention.trim());
        }
        sb.append('\n');
        for (CampusServices.Service s : CampusServices.CAMPUS) {
            JSONObject m = data == null ? null : data.module(s.id);
            sb.append("- ").append(s.id).append('(').append(s.title).append(")：");
            if (m == null) {
                sb.append("未同步\n");
                continue;
            }
            String state = m.optString("state", "未知");
            JSONArray fields = m.optJSONArray("fields");
            JSONArray tables = m.optJSONArray("tables");
            JSONArray links = m.optJSONArray("links");
            int fc = fields == null ? 0 : fields.length();
            int tc = tables == null ? 0 : tables.length();
            int lc = links == null ? 0 : links.length();
            sb.append("state=").append(state)
                    .append(" · fields=").append(fc)
                    .append(" · tables=").append(tc)
                    .append(" · links=").append(lc);
            if ("network".equals(s.id) || "card".equals(s.id)) {
                boolean hasBal = !CampusSummary.field(m, "余额|可用金额|剩余金额|卡内金额|主钱包").isEmpty();
                sb.append(" · 已读余额=").append(hasBal ? "是" : "否");
            } else if ("electric".equals(s.id)) {
                boolean hasElec = ChangeDiff.firstNumber(CampusSummary.field(m, "剩余电量|当前电量|电量余额")) != null;
                sb.append(" · 已读电量=").append(hasElec ? "是" : "否");
            }
            List<String> labels = fieldLabels(fields);
            if (!labels.isEmpty()) sb.append(" · 标签=").append(labels);
            String src = safeSource(m.optString("source", ""));
            if (!src.isEmpty()) sb.append(" · 来源=").append(src);
            long updated = m.optLong("updated", 0);
            long attempted = m.optLong("attempted", 0);
            if (updated > 0) sb.append(" · 更新=").append(fmtTime(updated));
            if (attempted > 0) sb.append(" · 尝试=").append(fmtTime(attempted));
            sb.append('\n');
        }
        sb.append("说明：本报告不含账号、密码、Cookie、令牌、余额数值、姓名或宿舍信息。");
        return sb.toString();
    }

    private static List<String> fieldLabels(JSONArray fields) {
        List<String> out = new ArrayList<>();
        if (fields == null) return out;
        for (int i = 0; i < fields.length() && out.size() < 8; i++) {
            JSONObject f = fields.optJSONObject(i);
            if (f == null) continue;
            String label = f.optString("label", "").replaceAll("\\s+", " ").trim();
            if (label.isEmpty() || label.length() > 24) continue;
            if (label.toLowerCase(Locale.ROOT).matches(SENSITIVE_LABEL)) continue;
            if (!out.contains(label)) out.add(label);
        }
        return out;
    }

    static String safeSource(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        try {
            URI u = new URI(raw.trim());
            String host = u.getHost();
            if (host == null || !(host.equals("nwpu.edu.cn") || host.endsWith(".nwpu.edu.cn"))) return "";
            String path = u.getPath() == null ? "" : u.getPath();
            return host + (path.length() > 80 ? path.substring(0, 80) : path);
        } catch (Exception e) {
            return "";
        }
    }

    private static String safeText(String s, String fallback) {
        if (s == null) return fallback;
        String clean = s.replaceAll("[\\r\\n\\t]+", " ").trim();
        return clean.isEmpty() ? fallback : clean.length() > 60 ? clean.substring(0, 60) : clean;
    }

    private static String fmtTime(long ms) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
        fmt.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return fmt.format(new Date(ms));
    }
}
