package com.nopalou;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Bounded, atomic storage for exams and grades; independent of timetable.json. */
final class AcademicStore {
    private static AtomicFile file(Context c) { return new AtomicFile(new File(c.getFilesDir(), "academic.json")); }
    static synchronized Academic load(Context c) throws Exception {
        AtomicFile f = file(c);
        if (!f.getBaseFile().exists() && !new File(f.getBaseFile() + ".bak").exists()) return null;
        try (java.io.InputStream in = f.openRead(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > Academic.MAX_BYTES) throw new java.io.IOException("考试成绩存档过大");
                out.write(buffer, 0, n);
            }
            String encoded=new String(out.toByteArray(), StandardCharsets.UTF_8);
            if(!AccountFiles.visible(new org.json.JSONObject(encoded),AccountFiles.current(c))) return null;
            return new Academic(encoded);
        }
    }
    /** Merges a fresh read into the saved copy and writes it; returns what was saved. */
    static synchronized Academic update(Context c, String fresh) throws Exception {
        Academic previous=load(c);
        Academic merged = Academic.merge(previous, fresh, System.currentTimeMillis());
        byte[] bytes = AccountFiles.protect(c,merged.encode()).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Academic.MAX_BYTES) throw new java.io.IOException("考试成绩过大");
        UpgradeDataBackup.beforeWrite(c);
        AtomicFile f = file(c); FileOutputStream out = null;
        try { out = f.startWrite(); out.write(bytes); f.finishWrite(out); }
        catch (Exception e) { if (out != null) f.failWrite(out); throw e; }
        CampusChanges.academic(c, previous, merged);
        return merged;
    }
    static synchronized void clear(Context c) { file(c).delete(); }
    static synchronized void stampOwner(Context c,String owner) throws Exception { AccountFiles.stamp(c,"academic.json",owner); }
}
