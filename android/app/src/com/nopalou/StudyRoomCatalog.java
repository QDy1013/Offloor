package com.nopalou;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local room directory. Availability and personal bookings always come from the active session. */
final class StudyRoomCatalog {
    private static final int MAX_ROOMS = 120;
    private static final long MAX_AGE = 7L * 24 * 60 * 60 * 1000;
    final JSONArray rows;
    final long at;

    StudyRoomCatalog(JSONArray rows, long at) {
        this.rows = rows;
        this.at = at;
    }

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getCacheDir(), "study-room-catalog.json"));
    }

    static synchronized StudyRoomCatalog load(Context context, String owner) {
        try {
            JSONObject snapshot = new JSONObject(new String(file(context).readFully(), StandardCharsets.UTF_8));
            long at = snapshot.getLong("at"), now = System.currentTimeMillis();
            if (owner.isEmpty() || !owner.equals(snapshot.optString("owner")) || at > now || now - at >= MAX_AGE) return null;
            return new StudyRoomCatalog(snapshot.getJSONArray("rows"), at);
        } catch (Exception ignored) {
            return null;
        }
    }

    static StudyRoomCatalog fromQuery(JSONArray rooms, long at) {
        JSONArray directory = new JSONArray();
        String[] fields = {"name", "location", "kind", "campus", "min", "max"};
        try {
            for (int i = 0; i < rooms.length() && directory.length() < MAX_ROOMS; i++) {
                JSONObject room = rooms.optJSONObject(i);
                if (room == null) continue;
                JSONObject entry = new JSONObject();
                for (String field : fields) entry.put(field, room.opt(field));
                directory.put(entry);
            }
        } catch (Exception ignored) { }
        return directory.length() == 0 ? null : new StudyRoomCatalog(directory, at);
    }

    static synchronized void save(Context context, String owner, StudyRoomCatalog catalog) {
        if (owner.isEmpty()) return;
        AtomicFile target = file(context);
        FileOutputStream output = null;
        try {
            JSONObject snapshot = new JSONObject().put("owner", owner).put("at", catalog.at).put("rows", catalog.rows);
            output = target.startWrite();
            output.write(snapshot.toString().getBytes(StandardCharsets.UTF_8));
            target.finishWrite(output);
        } catch (Exception ignored) {
            if (output != null) target.failWrite(output);
        }
    }
}
