package com.nopalou;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * 校车“我的车票”纯逻辑模型：融合官网 GetMyAppointment（核验二维码 objid/发车点）与按日班次匹配，
 * 负责状态合并、退票/过期清理、账号哈希隔离与行匹配。不依赖 android.* 包。
 */
final class CampusBusTicket {
    private static final TimeZone SHANGHAI = TimeZone.getTimeZone("Asia/Shanghai");

    static final class Ticket {
        final String id, date, time, route, from, to, place, order, qrCode, prepay, bookedAt, status, fee;
        final int payRemainMin;
        final boolean paid, payable;

        Ticket(String id, String date, String time, String route, String from, String to, String place,
               String order, String qrCode, String prepay, String bookedAt, String status, String fee,
               int payRemainMin, boolean paid, boolean payable) {
            this.id = safe(id);
            this.date = safe(date);
            this.time = safe(time);
            this.route = safe(route);
            this.from = safe(from);
            this.to = safe(to);
            this.place = safe(place);
            this.order = safe(order);
            this.qrCode = safe(qrCode);
            this.prepay = safe(prepay);
            this.bookedAt = safe(bookedAt);
            this.status = safe(status);
            this.fee = safe(fee);
            this.payRemainMin = payRemainMin;
            this.paid = paid;
            this.payable = payable;
        }

        boolean valid() {
            if (!date.isEmpty() && !isDate(date)) return false;
            if (!isTime(time)) return false;
            if (order.isEmpty() && qrCode.isEmpty()) return false;
            if (paid || payable) return true;
            return !status.isEmpty() && !isInactiveStatus(status);
        }

        boolean hasQrCode() { return paid && !qrCode.isEmpty(); }

        String identity() { return !order.isEmpty() ? order : ("obj:" + qrCode); }

        String key() {
            if (!date.isEmpty()) return date + "|" + (id.isEmpty() ? (time + "|" + route + "|" + identity()) : id);
            return "nodate|" + identity();
        }

        String displayRoute() {
            if (!from.isEmpty() && !to.isEmpty()) return from + " → " + to;
            return route;
        }

        JSONObject toTripJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("time", time);
                obj.put("route", route.isEmpty() ? displayRoute() : route);
                obj.put("open", false);
            } catch (Exception ignored) { }
            return obj;
        }

        JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("date", date);
                obj.put("time", time);
                obj.put("route", route);
                obj.put("from", from);
                obj.put("to", to);
                obj.put("place", place);
                obj.put("order", order);
                obj.put("qrCode", qrCode);
                obj.put("prepay", prepay);
                obj.put("bookedAt", bookedAt);
                obj.put("status", status);
                obj.put("fee", fee);
                obj.put("payRemainMin", payRemainMin);
                obj.put("paid", paid);
                obj.put("payable", payable);
            } catch (Exception ignored) { }
            return obj;
        }

        static Ticket fromJson(JSONObject obj) {
            if (obj == null) return null;
            return new Ticket(
                    obj.optString("id"),
                    obj.optString("date"),
                    obj.optString("time"),
                    obj.optString("route"),
                    obj.optString("from"),
                    obj.optString("to"),
                    obj.optString("place"),
                    obj.optString("order"),
                    obj.optString("qrCode"),
                    obj.optString("prepay"),
                    obj.optString("bookedAt"),
                    obj.optString("status"),
                    obj.optString("fee"),
                    obj.optInt("payRemainMin", -1),
                    obj.optBoolean("paid"),
                    obj.optBoolean("payable")
            );
        }
    }

    private CampusBusTicket() { }

    private static String safe(String s) { return s == null ? "" : s.trim(); }

    private static boolean isDate(String d) { return d != null && d.matches("\\d{4}-\\d{2}-\\d{2}"); }

    private static boolean isTime(String t) { return t != null && t.matches("([01]\\d|2[0-3]):[0-5]\\d"); }

    static boolean isInactiveStatus(String status) {
        if (status == null) return false;
        String s = status.trim();
        return s.contains("取消") || s.contains("过期") || s.contains("作废") || s.contains("失败") || s.contains("退");
    }

    static String[] threeDaysFrom(long nowMillis) {
        Calendar cal = Calendar.getInstance(SHANGHAI);
        cal.setTimeInMillis(nowMillis);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        fmt.setTimeZone(SHANGHAI);
        String[] days = new String[3];
        for (int i = 0; i < days.length; i++) {
            days[i] = fmt.format(new Date(cal.getTimeInMillis()));
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return days;
    }

    static String friendlyDate(String date, String[] threeDays) {
        if (date == null || date.trim().isEmpty()) return "已预约";
        if (!isDate(date)) return safe(date);
        if (threeDays != null) {
            if (threeDays.length > 0 && date.equals(threeDays[0])) return "今天";
            if (threeDays.length > 1 && date.equals(threeDays[1])) return "明天";
            if (threeDays.length > 2 && date.equals(threeDays[2])) return "后天";
        }
        try {
            int month = Integer.parseInt(date.substring(5, 7));
            int day = Integer.parseInt(date.substring(8, 10));
            return month + "月" + day + "日";
        } catch (Exception ignored) {
            return date;
        }
    }

    static List<Ticket> mergeScanResults(List<Ticket> current, JSONArray results, String todayDate) {
        return mergeScanResults(current, results, null, todayDate);
    }

    /** 合并多日扫描结果与未归并待乘车票：成功扫过的日期覆盖替换（同步退票），失败日保留现有缓存，过期旧票清理。 */
    static List<Ticket> mergeScanResults(List<Ticket> current, JSONArray results, JSONArray extraTickets, String todayDate) {
        Set<String> succeededDates = new HashSet<>();
        List<Ticket> scanned = new ArrayList<>();
        Set<String> placedIdentities = new HashSet<>();
        boolean anyDaySucceeded = false;

        if (results != null) {
            for (int i = 0; i < results.length(); i++) {
                JSONObject day = results.optJSONObject(i);
                if (day == null || !day.optBoolean("ok")) continue;
                String d = safe(day.optString("date"));
                if (!isDate(d)) continue;
                anyDaySucceeded = true;
                succeededDates.add(d);
                JSONArray items = day.optJSONArray("tickets");
                if (items == null) continue;
                for (int j = 0; j < items.length(); j++) {
                    Ticket t = Ticket.fromJson(items.optJSONObject(j));
                    if (t != null && t.valid() && !isBefore(t.date, todayDate)) {
                        scanned.add(t);
                        placedIdentities.add(t.identity());
                    }
                }
            }
        }

        Map<String, Ticket> merged = new LinkedHashMap<>();
        if (current != null) {
            for (Ticket old : current) {
                if (old == null || !old.valid() || isBefore(old.date, todayDate)) continue;
                if (old.date.isEmpty() && anyDaySucceeded) continue;
                if (succeededDates.contains(old.date)) continue;
                merged.put(old.key(), old);
            }
        }
        for (Ticket fresh : scanned) merged.put(fresh.key(), fresh);

        if (extraTickets != null) {
            for (int i = 0; i < extraTickets.length(); i++) {
                Ticket extra = Ticket.fromJson(extraTickets.optJSONObject(i));
                if (extra != null && extra.valid() && !placedIdentities.contains(extra.identity())) {
                    merged.put(extra.key(), extra);
                }
            }
        }
        return normalize(new ArrayList<>(merged.values()), todayDate);
    }

    /** 单次点开班次下单、准备支付单或重查订单后立即同步，且继承已有 qrCode / place 等富详情字段。 */
    static List<Ticket> upsertFromQuote(List<Ticket> current, JSONObject trip, String date, JSONObject quoteResult, String todayDate) {
        List<Ticket> base = normalize(current, todayDate);
        if (trip == null || quoteResult == null) return base;
        String safeDate = safe(date);
        if (!safeDate.isEmpty() && (!isDate(safeDate) || isBefore(safeDate, todayDate))) return base;

        String id = safe(trip.optString("id"));
        String time = safe(trip.optString("time"));
        String route = safe(trip.optString("route"));
        if (!isTime(time)) return base;

        Ticket previous = null;
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : base) {
            if (matches(t, safeDate, id, time, route)) previous = t;
            else out.add(t);
        }
        String order = safe(quoteResult.optString("order"));
        String status = safe(quoteResult.optString("status"));
        String fee = safe(quoteResult.optString("fee"));
        boolean paid = quoteResult.optBoolean("paid");
        boolean payable = quoteResult.optBoolean("payable");

        boolean sameOrder = previous != null && !order.isEmpty() && order.equals(previous.order);
        Ticket updated = new Ticket(
                id, safeDate, time, route,
                sameOrder ? previous.from : "",
                sameOrder ? previous.to : "",
                sameOrder ? previous.place : "",
                order,
                sameOrder ? previous.qrCode : "",
                sameOrder ? previous.prepay : "",
                sameOrder ? previous.bookedAt : "",
                status, fee,
                sameOrder ? previous.payRemainMin : -1,
                paid, payable
        );
        if (updated.valid()) out.add(updated);
        return normalize(out, todayDate);
    }

    static Ticket findForTrip(List<Ticket> tickets, String date, JSONObject trip) {
        if (tickets == null || trip == null || !isDate(date)) return null;
        String id = safe(trip.optString("id"));
        String time = safe(trip.optString("time"));
        String route = safe(trip.optString("route"));
        for (Ticket t : tickets) {
            if (matches(t, date, id, time, route)) return t;
        }
        return null;
    }

    private static boolean matches(Ticket t, String date, String id, String time, String route) {
        if (t == null) return false;
        if (!date.isEmpty() && !t.date.isEmpty()) {
            if (!date.equals(t.date)) return false;
            if (!id.isEmpty() && !t.id.isEmpty()) return id.equals(t.id);
            return time.equals(t.time) && (route.isEmpty() || t.route.isEmpty() || route.equals(t.route));
        }
        if (date.isEmpty() && t.date.isEmpty()) return time.equals(t.time) && route.equals(t.route);
        return false;
    }

    private static boolean isBefore(String date, String todayDate) {
        return isDate(date) && isDate(todayDate) && date.compareTo(todayDate) < 0;
    }

    private static List<Ticket> normalize(List<Ticket> raw, String todayDate) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        Map<String, Ticket> byKey = new LinkedHashMap<>();
        for (Ticket t : raw) {
            if (t == null || !t.valid() || isBefore(t.date, todayDate)) continue;
            byKey.put(t.key(), t);
        }
        Map<String, Ticket> byIdentity = new LinkedHashMap<>();
        for (Ticket t : byKey.values()) {
            byIdentity.put(t.identity(), t);
        }
        List<Ticket> list = new ArrayList<>(byIdentity.values());
        Collections.sort(list, (a, b) -> {
            if (a.date.isEmpty() != b.date.isEmpty()) return a.date.isEmpty() ? 1 : -1;
            int c = a.date.compareTo(b.date);
            if (c != 0) return c;
            c = a.time.compareTo(b.time);
            if (c != 0) return c;
            return a.route.compareTo(b.route);
        });
        return Collections.unmodifiableList(list);
    }

    /** 按账号哈希隔离编码，防止跨账号污染。 */
    static String encodeStorage(String owner, List<Ticket> tickets) {
        if (owner == null || owner.isEmpty()) return "";
        try {
            JSONArray arr = new JSONArray();
            if (tickets != null) {
                for (Ticket t : tickets) {
                    if (t != null && t.valid()) arr.put(t.toJson());
                }
            }
            return new JSONObject().put("owner", owner).put("tickets", arr).toString();
        } catch (Exception e) {
            return "";
        }
    }

    static List<Ticket> decodeStorage(String raw, String expectedOwner, String todayDate) {
        if (raw == null || raw.isEmpty() || expectedOwner == null || expectedOwner.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            JSONObject obj = new JSONObject(raw);
            if (!expectedOwner.equals(obj.optString("owner"))) return Collections.emptyList();
            JSONArray arr = obj.optJSONArray("tickets");
            if (arr == null) return Collections.emptyList();
            List<Ticket> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                Ticket t = Ticket.fromJson(arr.optJSONObject(i));
                if (t != null) list.add(t);
            }
            return normalize(list, todayDate);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
