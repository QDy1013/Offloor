package com.nopalou;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/** 校车“我的车票”状态合并、退票/过期清理、账号隔离与跨日操作回归。 */
public final class CampusBusTicketTest {
    private static int checks;

    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }

    private static JSONObject ticketJson(String id, String date, String time, String route,
                                         String order, String status, String fee, boolean paid, boolean payable) throws Exception {
        return new JSONObject().put("id", id).put("date", date).put("time", time).put("route", route)
                .put("order", order).put("status", status).put("fee", fee).put("paid", paid).put("payable", payable);
    }

    public static void main(String[] args) throws Exception {
        String today = "2026-11-21";
        String tomorrow = "2026-11-22";
        String dayAfter = "2026-11-23";
        String yesterday = "2026-11-20";

        // 1. 多日扫描结果合并 + 排序 + 过期清理 + 失败日期缓存保留 + 成功日退票覆盖
        JSONArray initScan = new JSONArray()
                .put(new JSONObject().put("date", yesterday).put("ok", true).put("tickets",
                        new JSONArray().put(ticketJson("old", yesterday, "08:00", "友谊→长安", "O0", "待核验", "4", true, false))))
                .put(new JSONObject().put("date", tomorrow).put("ok", true).put("tickets",
                        new JSONArray().put(ticketJson("t2", tomorrow, "14:30", "长安→友谊", "O2", "待核验", "4", true, false))))
                .put(new JSONObject().put("date", today).put("ok", true).put("tickets",
                        new JSONArray().put(ticketJson("t1", today, "09:00", "友谊→长安", "O1", "待支付", "4", false, true))));
        List<CampusBusTicket.Ticket> list = CampusBusTicket.mergeScanResults(null, initScan, today);
        check("scan excludes past tickets and sorts by date then time",
                list.size() == 2 && "O1".equals(list.get(0).order) && "O2".equals(list.get(1).order));

        // 2. 第二次扫描：今天退票（ok=true且为空），明天断网（ok=false 保留缓存）
        JSONArray nextScan = new JSONArray()
                .put(new JSONObject().put("date", today).put("ok", true).put("tickets", new JSONArray()))
                .put(new JSONObject().put("date", tomorrow).put("ok", false).put("tickets", new JSONArray()));
        list = CampusBusTicket.mergeScanResults(list, nextScan, today);
        check("cancelled day clears ticket while failed scan keeps cached tomorrow ticket",
                list.size() == 1 && "O2".equals(list.get(0).order));

        // 3. 单班次查单与下单立即更新状态（且同订单状态跃迁待支付 -> 待核验不被旧值挡住）
        JSONObject tripT1 = new JSONObject().put("id", "t1").put("time", "09:00").put("route", "友谊→长安");
        list = CampusBusTicket.upsertFromQuote(list, tripT1, today,
                new JSONObject().put("order", "O1").put("status", "待支付").put("fee", "4").put("paid", false).put("payable", true), today);
        check("upsert adds pending ticket in front of tomorrow",
                list.size() == 2 && "O1".equals(list.get(0).order) && list.get(0).payable);

        list = CampusBusTicket.upsertFromQuote(list, tripT1, today,
                new JSONObject().put("order", "O1").put("status", "待核验").put("fee", "4").put("paid", true).put("payable", false), today);
        check("paid update promotes same order to paid",
                list.size() == 2 && list.get(0).paid && !list.get(0).payable);

        // 校方查单无订单时（如已退票）立刻移除
        list = CampusBusTicket.upsertFromQuote(list, tripT1, today,
                new JSONObject().put("order", "").put("status", "").put("canSubmit", true), today);
        check("empty order from quote removes cancelled ticket immediately",
                list.size() == 1 && "O2".equals(list.get(0).order));

        // 4. 同日同时间不同路线按 id 精确区分不串单
        JSONObject tripMorningA = new JSONObject().put("id", "busA").put("time", "08:00").put("route", "友谊→长安");
        JSONObject tripMorningB = new JSONObject().put("id", "busB").put("time", "08:00").put("route", "长安→友谊");
        list = CampusBusTicket.upsertFromQuote(list, tripMorningA, today,
                new JSONObject().put("order", "OA").put("status", "待核验").put("fee", "4").put("paid", true).put("payable", false), today);
        list = CampusBusTicket.upsertFromQuote(list, tripMorningB, today,
                new JSONObject().put("order", "OB").put("status", "待支付").put("fee", "4").put("paid", false).put("payable", true), today);
        check("same time opposite routes match strictly by trip id",
                "OA".equals(CampusBusTicket.findForTrip(list, today, tripMorningA).order)
                        && "OB".equals(CampusBusTicket.findForTrip(list, today, tripMorningB).order)
                        && CampusBusTicket.findForTrip(list, dayAfter, tripMorningA) == null);

        // 5. 账号哈希隔离存储，切号后不泄漏旧账号车票
        String stored = CampusBusTicket.encodeStorage("owner-hash-a", list);
        List<CampusBusTicket.Ticket> sameOwner = CampusBusTicket.decodeStorage(stored, "owner-hash-a", today);
        List<CampusBusTicket.Ticket> otherOwner = CampusBusTicket.decodeStorage(stored, "owner-hash-b", today);
        check("storage roundtrips for matching owner and rejects other accounts",
                sameOwner.size() == 3 && otherOwner.isEmpty());

        // 6. 已取消/已过期文案过滤与友好日期
        check("inactive statuses filtered cleanly",
                CampusBusTicket.isInactiveStatus("已取消")
                        && CampusBusTicket.isInactiveStatus("已过期")
                        && !CampusBusTicket.isInactiveStatus("待核验"));
        String[] three = {today, tomorrow, dayAfter};
        check("friendly date renders today/tomorrow/dayAfter and fallback date",
                "今天".equals(CampusBusTicket.friendlyDate(today, three))
                        && "明天".equals(CampusBusTicket.friendlyDate(tomorrow, three))
                        && "后天".equals(CampusBusTicket.friendlyDate(dayAfter, three))
                        && "11月25日".equals(CampusBusTicket.friendlyDate("2026-11-25", three)));

        // 7. 核验码 objid / 上车地点 / extraTickets 融合与单次复核不丢 qrCode
        JSONObject richScan = new JSONObject().put("date", today).put("ok", true).put("tickets",
                new JSONArray().put(new JSONObject()
                        .put("id", "t14").put("date", today).put("time", "14:00")
                        .put("route", "友谊到长安").put("from", "友谊校区").put("to", "长安校区")
                        .put("place", "友谊校区南门口").put("order", "ORDER32")
                        .put("qrCode", "5013261121bf1cc533").put("status", "待核验").put("fee", "5")
                        .put("paid", true).put("payable", false)));
        JSONArray extra = new JSONArray().put(new JSONObject()
                .put("id", "").put("date", "").put("time", "18:00")
                .put("route", "长安到友谊").put("order", "ORDER_EXTRA")
                .put("qrCode", "5013261125aa112233").put("status", "待支付").put("fee", "5")
                .put("payRemainMin", 9).put("paid", false).put("payable", true));
        List<CampusBusTicket.Ticket> rich = CampusBusTicket.mergeScanResults(null, new JSONArray().put(richScan), extra, today);
        check("merge keeps matched date ticket first and appends extra ticket",
                rich.size() == 2 && rich.get(0).hasQrCode() && "5013261121bf1cc533".equals(rich.get(0).qrCode)
                        && "友谊校区南门口".equals(rich.get(0).place) && "已预约".equals(CampusBusTicket.friendlyDate(rich.get(1).date, three)));

        JSONObject trip14 = new JSONObject().put("id", "t14").put("time", "14:00").put("route", "友谊到长安");
        rich = CampusBusTicket.upsertFromQuote(rich, trip14, today,
                new JSONObject().put("order", "ORDER32").put("status", "待核验").put("fee", "5").put("paid", true).put("payable", false), today);
        check("single quote update preserves rich qrCode and boarding place",
                "5013261121bf1cc533".equals(rich.get(0).qrCode) && "友谊校区南门口".equals(rich.get(0).place));

        CampusBusTicket.Ticket missingQr = new CampusBusTicket.Ticket("t2", today, "15:00", "友谊到长安", "", "", "", "ONLY_ORDER", "", "", "", "待核验", "5", -1, true, false);
        check("ticket without real objid qrCode never fakes a QR code from order number",
                !missingQr.hasQrCode());

        System.out.println(checks + " bus ticket checks passed");
    }
}
