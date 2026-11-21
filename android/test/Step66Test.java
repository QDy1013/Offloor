package com.nopalou;

/** 缴费入口统一认证地址、支付 Scheme 路由与微信 H5 Referer 判定回归。 */
public final class Step66Test {
    private static int checks;

    private static void check(String label, boolean ok) {
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + label);
        checks++;
    }

    public static void main(String[] args) {
        // 1. 电费缴费走一卡通统一身份认证入口，避免裸访根域名 401/空白
        String electricUrl = CampusPay.url(CampusPay.ELECTRIC);
        check("electric pay entry points to yktapp CAS login",
                electricUrl.startsWith("https://yktapp.nwpu.edu.cn/berserker-auth/cas/login/supwisdom")
                        && electricUrl.contains("targetUrl=") && electricUrl.contains("plat"));
        check("network pay entry points to zizhu",
                "https://zizhu.nwpu.edu.cn/".equals(CampusPay.url(CampusPay.NETWORK)));

        // 2. 支付与外部跳转方案分级路由（校车收银台与官方缴费页共用）
        check("cashier keeps third-party https gateway inside WebView",
                CampusPay.route("https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb", true) == CampusPay.Route.STAY);
        check("campus pay hands non-school https URL off to system browser",
                CampusPay.route("https://openapi.alipay.com/gateway.do", false) == CampusPay.Route.OPEN_URI);
        check("wechat pay deeplink opens external app even inside cashier",
                CampusPay.route("weixin://wap/pay?prepayid=wx1", true) == CampusPay.Route.OPEN_URI);
        check("alipay deeplink opens external app",
                CampusPay.route("alipays://platformapi/startapp?appId=20000125", true) == CampusPay.Route.OPEN_URI);
        check("unionpay deeplink opens external app",
                CampusPay.route("uppay://uppayservice/?style=token", false) == CampusPay.Route.OPEN_URI);
        check("intent scheme routes to intent parser",
                CampusPay.route("intent://platformapi/startapp#Intent;scheme=alipays;package=com.eg.android.AlipayGphone;end", true)
                        == CampusPay.Route.OPEN_INTENT);
        check("inert or local file schemes never open externally",
                CampusPay.route("about:blank", true) == CampusPay.Route.IGNORE
                        && CampusPay.route("file:///etc/passwd", true) == CampusPay.Route.IGNORE
                        && CampusPay.route("content://media/external", false) == CampusPay.Route.IGNORE
                        && CampusPay.route("", true) == CampusPay.Route.IGNORE);

        // 3. 微信 H5 支付网关 Referer 检测与应用友好名称映射
        check("wx.tenpay.com triggers Referer protection",
                CampusPay.needsPayReferer("https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=1")
                        && CampusPay.needsPayReferer("https://pay.wx.tenpay.com/test"));
        check("ordinary hosts do not falsely match Referer check",
                !CampusPay.needsPayReferer("https://wx.tenpay.com.evil.test/")
                        && !CampusPay.needsPayReferer("https://hq-bus.nwpu.edu.cn/h5/"));
        check("friendly payment app names distinguish wechat, alipay and unionpay",
                "微信".equals(CampusPay.payAppName("weixin://wap/pay?prepayid=1"))
                        && "支付宝".equals(CampusPay.payAppName("intent://#Intent;package=com.eg.android.AlipayGphone;end"))
                        && "云闪付".equals(CampusPay.payAppName("uppay://uppayservice/")));

        System.out.println(checks + " payment routing checks passed");
    }
}
