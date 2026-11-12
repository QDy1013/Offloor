package com.nopalou;

import java.util.Locale;

/**
 * 余额换算与提醒判断：只做数学，不碰界面和存储，方便离线测试。
 *
 * 校方电费页只给剩余电量（度），没有金额；这里按“电价 × 度数”换成钱，
 * 让电费和网费共用同一套“低于 X 元提醒”的口径。电价可以改，默认 0.5 元/度。
 */
final class CampusBalance {
    /** 默认电价，元/度。校方账单没有单价时用它换算。 */
    static final double DEFAULT_PRICE = 0.5;
    /** 电费、网费默认提醒阈值，元。 */
    static final double DEFAULT_ELECTRIC = 20.0, DEFAULT_NETWORK = 20.0;
    /** 电价只接受这个区间，避免把度数误填成电价后算出离谱金额。 */
    static final double MIN_PRICE = 0.05, MAX_PRICE = 20.0;
    /** 阈值上限，超过就当作误填。 */
    static final double MAX_THRESHOLD = 10000.0;

    private CampusBalance() { }

    static boolean validPrice(double price) { return price >= MIN_PRICE && price <= MAX_PRICE; }

    static boolean validThreshold(double value) { return value >= 0 && value < MAX_THRESHOLD; }

    /** 度数换成金额；读不到电量时返回 null，绝不补零。 */
    static Double yuanFromKwh(Double kwh, double price) {
        if (kwh == null || kwh.isNaN() || kwh.isInfinite() || kwh < 0 || !validPrice(price)) return null;
        return round(kwh * price);
    }

    /** 金额按分对齐，避免 11.000000000000002 这种脏数字显示出去。 */
    static double round(double value) { return Math.round(value * 100.0) / 100.0; }

    static String money(Double yuan) {
        if (yuan == null) return "";
        return String.format(Locale.ROOT, "¥%.2f", round(yuan));
    }

    static String kWh(Double kwh) {
        if (kwh == null) return "";
        return String.format(Locale.ROOT, "%.1f 度", kwh);
    }

    /** 账户卡的金额：读不到就说读不到，不显示 0 元。 */
    static String electricValue(Double kwh, double price) {
        Double yuan = yuanFromKwh(kwh, price);
        return yuan == null ? "电费暂未读到" : money(yuan);
    }

    /** 金额下面的补充说明：保留度数，并说清是按什么电价折算的。 */
    static String electricNote(Double kwh, double price) {
        if (kwh == null) return "";
        return String.format(Locale.ROOT, "%s · 按 %s/度", kWh(kwh), money(price));
    }

    /** 网费直接是金额，读不到同样不补零。 */
    static String networkValue(Double yuan) { return yuan == null ? "余额暂未读到" : money(yuan); }

    /** 电费是否低于阈值（按金额比较）。 */
    static boolean electricLow(Double kwh, double price, double thresholdYuan) {
        Double yuan = yuanFromKwh(kwh, price);
        return yuan != null && validThreshold(thresholdYuan) && yuan < thresholdYuan;
    }

    static boolean networkLow(Double yuan, double thresholdYuan) {
        return yuan != null && validThreshold(thresholdYuan) && yuan < thresholdYuan;
    }

    /** 电费提醒正文：钱和度数都给，避免用户对不上校方页面。 */
    static String electricAlert(Double kwh, double price, double thresholdYuan) {
        Double yuan = yuanFromKwh(kwh, price);
        if (yuan == null) return "";
        return "约 " + money(yuan) + "（" + kWh(kwh) + "），低于 " + money(thresholdYuan) + "，记得交电费";
    }

    static String networkAlert(Double yuan, double thresholdYuan) {
        if (yuan == null) return "";
        return "余额 " + money(yuan) + "，低于 " + money(thresholdYuan) + "，记得交网费";
    }
}
