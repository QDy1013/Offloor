package com.nopalou;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Bounded, atomic storage; a cancelled or failed import never replaces the last timetable. */
final class TimetableStore {
    private static AtomicFile file(Context c) {
        return new AtomicFile(new File(c.getFilesDir(), "timetable.json"));
    }
    static synchronized Timetable load(Context c) throws Exception {
        AtomicFile f = file(c);
        if (!f.getBaseFile().exists() && !new File(f.getBaseFile() + ".bak").exists()) return null;
        try (java.io.InputStream in = f.openRead(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > Timetable.MAX_BYTES) throw new java.io.IOException("课表存档过大");
                out.write(buffer, 0, n);
            }
            String encoded=new String(out.toByteArray(), StandardCharsets.UTF_8);
            if(!AccountFiles.visible(new org.json.JSONObject(encoded),AccountFiles.current(c))) return null;
            return new Timetable(encoded);
        }
    }
    static synchronized void save(Context c, Timetable t) throws Exception {
        byte[] bytes = AccountFiles.protect(c,t.encode()).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Timetable.MAX_BYTES) throw new java.io.IOException("课表过大");
        Timetable previous = null;
        try { previous = load(c); } catch (Exception ignored) { }
        AtomicFile f = file(c);
        FileOutputStream out = null;
        try {
            out = f.startWrite();
            out.write(bytes);
            f.finishWrite(out);
        } catch (Exception e) {
            if (out != null) f.failWrite(out);
            throw e;
        }
        // 基线是“上一次保存的课表”，不是第一次导入的那份
        CampusChanges.timetable(c, previous, t);
        TodayWidget.refresh(c);
    }
    static synchronized void clear(Context c) { file(c).delete(); TodayWidget.refresh(c); }
    static synchronized void stampOwner(Context c,String owner) throws Exception { AccountFiles.stamp(c,"timetable.json",owner); }
}
