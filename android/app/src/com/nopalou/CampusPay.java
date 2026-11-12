package com.nopalou;

/**
 * 缴费入口只负责“把用户送到校方官方缴费页面”，不代收、不代扣、不保存任何支付信息。
 * 真正的金额输入、密码校验和扣款都在校方页面里由本人完成。
 */
final class CampusPay {
    static final String ELECTRIC = "electric", NETWORK = "network";
    /** 一卡通“交电费”所在站点：先到首页，再从页面里的“交电费”进入，避免直接拼接支付参数。 */
    static final String ELECTRIC_URL = "https://yktapp.nwpu.edu.cn/";
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
}
