package com.nopalou;

import java.net.URI;
import java.util.Locale;

/** Official landing pages, not invented private API endpoints. */
final class CampusServices {
    static final class Service {
        final String id, title, detail, entry;
        final boolean academic;
        Service(String id, String title, String detail, String entry, boolean academic) {
            this.id=id; this.title=title; this.detail=detail; this.entry=entry; this.academic=academic;
        }
    }
    static final String PORTAL = "https://ecampus.nwpu.edu.cn/main.html";
    // Registered portal CAS entry observed in the existing NWPU login implementation.
    static final String PORTAL_SSO = "https://uis.nwpu.edu.cn/cas/login?service=https%3A%2F%2Fecampus.nwpu.edu.cn%2F%3Fpath%3Dhttps%253A%252F%252Fecampus.nwpu.edu.cn%252Fmain.html%2523%252FIndex";
    static final String HOME = "https://jwxt.nwpu.edu.cn/student/home";
    static final String NOTICES = "https://xygg.nwpu.edu.cn/xygg/xngg.htm";
    static final Service[] ALL = {
        new Service("profile", "我的学籍", "姓名、专业、年级与班级", "https://jwxt.nwpu.edu.cn/student/for-std/student-portrait", true),
        new Service("program", "培养方案", "课程模块与学分要求", HOME, true),
        new Service("completion", "学业进度", "已修学分与未完成课程", HOME, true),
        new Service("selection", "选课结果", "已选课程与选课状态", HOME, true),
        new Service("evaluation", "课程评教", "本人可见的评教任务", HOME, true),
        new Service("card", "一卡通", "余额与消费账单", "https://yktapp.nwpu.edu.cn/", false),
        new Service("electric", "宿舍电量", "选择自己的宿舍，查询剩余电量", "https://yktapp.nwpu.edu.cn/", false),
        new Service("network", "校园网", "余额、流量与套餐", "https://zizhu.nwpu.edu.cn/", false),
        new Service("library", "我的借阅", "借阅书目与应还日期", "https://tushuguan.nwpu.edu.cn/", false),
        new Service("seats", "座位预约", "个人预约与签到状态 · 需要校园网", "https://ic.nwpu.edu.cn/", false),
        new Service("classroom", "空闲教室", "按日期和节次查询自习教室", "https://idle-classroom.nwpu.edu.cn/ui/", false),
        new Service("sports", "体育场馆", "场地信息与本人预约", PORTAL, false),
        new Service("affairs", "综测与奖助", "本人综测与申请进度", "https://xgpt.nwpu.edu.cn/", false),
        new Service("notices", "校园公告", "学校最新通知与公告", NOTICES, false),
        new Service("calendar", "校历与校车", "校历、放假与校车时刻", NOTICES, false)
    };
    static Service byId(String id) { for (Service s : ALL) if (s.id.equals(id)) return s; return null; }
    static boolean allowed(String value) {
        try {
            URI u = new URI(value); String h = u.getHost();
            return "https".equals(u.getScheme()) && u.getUserInfo() == null && (u.getPort() == -1 || u.getPort() == 443) && h != null &&
                    (h.equals("nwpu.edu.cn") || h.endsWith(".nwpu.edu.cn"));
        } catch (Exception e) { return false; }
    }
    static boolean readUrl(String value) {
        if (!allowed(value) || value.length() > 2000) return false;
        try {
            URI u = new URI(value);
            String p = java.net.URLDecoder.decode(u.getPath(), "UTF-8").toLowerCase(Locale.ROOT), q = u.getRawQuery();
            if (p.matches(".*(?:^|/)(?:logout|delete|remove|submit|save|pay|payment|recharge)(?:/.*|$)")) return false;
            String params = (q == null ? "" : q) + "&" + (u.getRawFragment() == null ? "" : u.getRawFragment());
            params = java.net.URLDecoder.decode(params, "UTF-8").toLowerCase(Locale.ROOT);
            return !params.matches(".*(?:ticket|token|password|session|secret|cookie|action|operation|method)=.*");
        } catch (Exception e) { return false; }
    }
    static String status(String state) {
        switch (state) {
            case "ok": return "已同步";
            case "login": return "统一认证未完成";
            case "binding": return "需要选择宿舍";
            case "network": return "连接失败";
            case "unavailable": return "请打开校方页面同步";
            case "unsupported": return "页面暂不支持";
            default: return "待同步";
        }
    }
}
