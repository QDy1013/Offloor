package com.nopalou;

/**
 * 缴费入口只负责“把用户送到校方官方缴费页面”，不代收、不代扣、不保存任何支付信息。
 * 真正的金额输入、密码校验和扣款都在校方页面里由本人完成。
 */
final class CampusPay {
    enum Route { STAY, OPEN_URI, OPEN_INTENT, IGNORE }

    static final String ELECTRIC = "electric", NETWORK = "network";
    /** 一卡通“交电费”统一认证入口：直接访问根路径会未登录报错，需先走 CAS 认证落地到 /plat 首页，再进入“交电费”。 */
    static final String ELECTRIC_URL = "https://yktapp.nwpu.edu.cn/berserker-auth/cas/login/supwisdom?targetUrl=https%3A%2F%2Fyktapp.nwpu.edu.cn%2Fplat";
    /** 网络自助服务：充值/缴费入口在本人登录后的页面里。 */
    static final String NETWORK_URL = "https://zizhu.nwpu.edu.cn/";

    private CampusPay() { }

    static boolean known(String id) { return ELECTRIC.equals(id) || NETWORK.equals(id); }

    static String url(String id) { return NETWORK.equals(id) ? NETWORK_URL : ELECTRIC.equals(id) ? ELECTRIC_URL : ""; }

    static String title(String id) {
        return NETWORK.equals(id) ? "交网费" : ELECTRIC.equals(id) ? "交电费" : "缴费";
    }

    /** 页面上方的说明，避免用户误以为钱是在 App 里扣的。 */
    static String note(String id) {
        String what = NETWORK.equals(id) ? "网费" : "电费";
        return "在下方校方官方页面完成" + what + "缴纳。本应用不代收、不代扣，也不会保存你的支付信息或卡号；"
                + "缴费结果以校方系统为准，返回后可在校园页重新读取余额核对。";
    }

    /** 提取合法的 URI scheme（小写），格式不合法时返回空串。 */
    static String scheme(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        int colon = trimmed.indexOf(':');
        if (colon <= 0) return "";
        String raw = trimmed.substring(0, colon);
        char first = raw.charAt(0);
        if (!((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z'))) return "";
        for (int i = 1; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '-' || c == '.';
            if (!ok) return "";
        }
        return raw.toLowerCase(java.util.Locale.ROOT);
    }

    /** 根据 URL 决定是在当前 WebView 加载、拉起支付/外部应用，还是直接忽略伪协议。 */
    static Route route(String url, boolean stayHttpsInWebView) {
        String s = scheme(url);
        if (s.isEmpty() || "about".equals(s) || "javascript".equals(s)
                || "data".equals(s) || "blob".equals(s) || "file".equals(s) || "content".equals(s)) {
            return Route.IGNORE;
        }
        if ("intent".equals(s)) return Route.OPEN_INTENT;
        if ("https".equals(s)) return stayHttpsInWebView ? Route.STAY : Route.OPEN_URI;
        return Route.OPEN_URI;
    }

    /** 提取 URL 的小写 host（去掉端口与路径）。 */
    static String host(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        int schemeEnd = trimmed.indexOf("://");
        if (schemeEnd < 0) return "";
        int start = schemeEnd + 3;
        int end = trimmed.length();
        for (int i = start; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        int at = trimmed.lastIndexOf('@', end - 1);
        if (at >= start) start = at + 1;
        String authority = trimmed.substring(start, end);
        int colon = authority.lastIndexOf(':');
        if (colon > 0) authority = authority.substring(0, colon);
        return authority.toLowerCase(java.util.Locale.ROOT);
    }

    /** 微信 H5 支付网关需要携带 Referer 才不会报“商家参数格式有误”。 */
    static boolean needsPayReferer(String url) {
        if (!"https".equals(scheme(url))) return false;
        String h = host(url);
        return "wx.tenpay.com".equals(h) || h.endsWith(".wx.tenpay.com");
    }

    /** 解析失败或未安装目标应用时给出可理解的应用名称。 */
    static String payAppName(String url) {
        String lower = url == null ? "" : url.toLowerCase(java.util.Locale.ROOT);
        String s = scheme(lower);
        if ("weixin".equals(s) || lower.contains("com.tencent.mm") || lower.contains("scheme=weixin")) return "微信";
        if ("alipays".equals(s) || "alipay".equals(s) || lower.contains("com.eg.android.alipaygphone") || lower.contains("scheme=alipay")) return "支付宝";
        if ("uppay".equals(s) || "upwrp".equals(s) || lower.contains("com.unionpay")) return "云闪付";
        if ("http".equals(s) || "https".equals(s)) return "系统浏览器";
        return "支付或外部应用";
    }
}
