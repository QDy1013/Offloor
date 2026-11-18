package com.nopalou;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;

/** Coordinates portal home balance extraction and catalog discovery without cutting off async widgets. */
final class CampusPortalScan {
    long enteredAt, catalogAt, changedAt;
    boolean catalogClicked;
    String snapshot = "";
    final JSONObject modules = new JSONObject();

    void reset() {
        enteredAt = 0; catalogAt = 0; changedAt = 0; catalogClicked = false; snapshot = "";
        while (modules.length() > 0) {
            Iterator<String> it = modules.keys();
            if (!it.hasNext()) break;
            modules.remove(it.next());
        }
    }

    enum Step { WAIT, CLICK_CATALOG, DONE }

    Step observe(JSONObject r, long now) {
        if (enteredAt == 0) enteredAt = now;
        mergeModules(r.optJSONObject("portalModules"));
        boolean gotBalances = modules.has("network") && modules.has("card");
        JSONArray apps = r.optJSONArray("apps");
        JSONObject routes = r.optJSONObject("routes");
        boolean empty = (apps == null || apps.length() == 0) && (routes == null || routes.length() == 0);
        boolean busy = r.optBoolean("busy");
        String current = String.valueOf(apps) + routes + modules;
        if (!current.equals(snapshot)) { snapshot = current; changedAt = now; }
        if (changedAt == 0) changedAt = now;
        long inPortal = now - enteredAt;
        if (!catalogClicked) {
            if (!gotBalances && (inPortal < 2500 || busy || now - changedAt < 500) && inPortal < 5500) return Step.WAIT;
            catalogClicked = true;
            catalogAt = now;
            return Step.CLICK_CATALOG;
        }
        if ((empty || busy || now - changedAt < 350) && now - catalogAt < 3500 && inPortal < 8500) return Step.WAIT;
        return Step.DONE;
    }

    void mergeModules(JSONObject pm) {
        if (pm == null) return;
        for (Iterator<String> it = pm.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject m = pm.optJSONObject(k);
            if (m != null && "ok".equals(m.optString("state"))) {
                try { modules.put(k, m); } catch (Exception ignored) { }
            }
        }
    }
}
