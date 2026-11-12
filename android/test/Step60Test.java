package com.nopalou;

/** 电费/网费金额换算与阈值判断的纯逻辑回归；不联网、不读校方数据。 */
public final class Step60Test {
    private static int checks;

    private static void check(String label, boolean ok) {
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + label);
        checks++;
    }

    public static void main(String[] args) {
        // ---------- 电价换算 ----------
        check("four kWh at the default price is two yuan", Math.abs(CampusBalance.yuanFromKwh(4.0, 0.5) - 2.0) < 0.001);
        check("money keeps two decimal places", "¥2.00".equals(CampusBalance.money(CampusBalance.yuanFromKwh(4.0, 0.5))));
        check("a repeating decimal still rounds to cents", "¥0.33".equals(CampusBalance.money(CampusBalance.yuanFromKwh(1.0, 1.0 / 3))));
        check("dirty floating point never leaks into the label",
                !CampusBalance.money(CampusBalance.yuanFromKwh(22.0, 0.5)).contains("000000"));
        check("custom price changes the amount", Math.abs(CampusBalance.yuanFromKwh(10.0, 0.6) - 6.0) < 0.001);

        // ---------- 不猜数据 ----------
        check("missing kWh stays missing", CampusBalance.yuanFromKwh(null, 0.5) == null);
        check("negative kWh is rejected", CampusBalance.yuanFromKwh(-1.0, 0.5) == null);
        check("NaN and infinity are rejected",
                CampusBalance.yuanFromKwh(Double.NaN, 0.5) == null && CampusBalance.yuanFromKwh(Double.POSITIVE_INFINITY, 0.5) == null);
        check("an impossible price yields no amount", CampusBalance.yuanFromKwh(10.0, 0) == null
                && CampusBalance.yuanFromKwh(10.0, -1) == null && CampusBalance.yuanFromKwh(10.0, 999) == null);
        check("unreadable electricity never shows zero yuan", "电费暂未读到".equals(CampusBalance.electricValue(null, 0.5)));
        check("unreadable network balance never shows zero yuan", "余额暂未读到".equals(CampusBalance.networkValue(null)));

        // ---------- 显示文案 ----------
        check("electric card shows money", "¥11.00".equals(CampusBalance.electricValue(22.0, 0.5)));
        check("electric note keeps the kWh and the price", "22.0 度 · 按 ¥0.50/度".equals(CampusBalance.electricNote(22.0, 0.5)));
        check("network card shows money", "¥13.40".equals(CampusBalance.networkValue(13.4)));

        // ---------- 阈值判断 ----------
        check("electric under twenty yuan is low", CampusBalance.electricLow(30.0, 0.5, 20.0));
        check("electric at exactly twenty yuan is not low", !CampusBalance.electricLow(40.0, 0.5, 20.0));
        check("electric above twenty yuan is not low", !CampusBalance.electricLow(60.0, 0.5, 20.0));
        check("network under twenty yuan is low", CampusBalance.networkLow(19.99, 20.0));
        check("network at exactly twenty yuan is not low", !CampusBalance.networkLow(20.0, 20.0));
        check("unreadable electricity is never low", !CampusBalance.electricLow(null, 0.5, 20.0));
        check("unreadable network balance is never low", !CampusBalance.networkLow(null, 20.0));
        check("an absurd threshold is ignored", !CampusBalance.networkLow(5.0, -1) && !CampusBalance.networkLow(5.0, 20000));

        // ---------- 提醒正文 ----------
        check("electric alert shows money, kWh and the threshold",
                "约 ¥15.00（30.0 度），低于 ¥20.00，记得交电费".equals(CampusBalance.electricAlert(30.0, 0.5, 20.0)));
        check("network alert shows the balance and the threshold",
                "余额 ¥8.00，低于 ¥20.00，记得交网费".equals(CampusBalance.networkAlert(8.0, 20.0)));
        check("an unreadable value produces no alert text",
                CampusBalance.electricAlert(null, 0.5, 20.0).isEmpty() && CampusBalance.networkAlert(null, 20.0).isEmpty());

        // ---------- 设置项校验 ----------
        check("the default price is a usable price", CampusBalance.validPrice(CampusBalance.DEFAULT_PRICE));
        check("both default thresholds are twenty yuan",
                CampusBalance.DEFAULT_ELECTRIC == 20.0 && CampusBalance.DEFAULT_NETWORK == 20.0);
        check("price bounds are enforced",
                !CampusBalance.validPrice(0.01) && CampusBalance.validPrice(0.05) && CampusBalance.validPrice(20.0) && !CampusBalance.validPrice(20.01));
        check("threshold zero is allowed but negative is not",
                CampusBalance.validThreshold(0) && !CampusBalance.validThreshold(-1) && !CampusBalance.validThreshold(10000));

        System.out.println(checks + " balance checks passed");
    }
}
