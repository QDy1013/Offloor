package com.nopalou;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** App-private campus snapshots. No credentials or full server response bodies are stored. */
final class CampusStore {
    private static final java.util.List<java.lang.ref.WeakReference<Runnable>> observers=new java.util.ArrayList<>();
    private static final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
    static synchronized void observe(Runnable callback) { unobserve(callback); observers.add(new java.lang.ref.WeakReference<>(callback)); }
    static synchronized void unobserve(Runnable callback) { observers.removeIf(ref -> ref.get()==null || ref.get()==callback); }
    private static void changed() { for (java.lang.ref.WeakReference<Runnable> ref:observers) { Runnable callback=ref.get(); if (callback!=null) main.post(callback); } }
    static synchronized void signalChanged() { changed(); }
    private static AtomicFile file(Context c) { return new AtomicFile(new File(c.getFilesDir(), "campus.json")); }
    static synchronized CampusData load(Context c) throws Exception {
        AtomicFile f = file(c);
        if (!f.getBaseFile().exists() && !new File(f.getBaseFile() + ".bak").exists()) {
            try (java.io.InputStream in = c.getAssets().open("campus-public.json")) { return read(in); }
            catch (java.io.FileNotFoundException e) { return null; }
        }
        try (java.io.InputStream in = f.openRead()) { return read(in); }
    }
    private static CampusData read(java.io.InputStream in) throws Exception {
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n;
            while ((n=in.read(b)) != -1) { if (out.size() + n > CampusData.MAX_BYTES) throw new java.io.IOException("校园存档过大"); out.write(b, 0, n); }
            return new CampusData(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }
    static synchronized CampusData update(Context c, String fresh, String expectedOwner) throws Exception {
        CampusData old = load(c), next = CampusData.merge(old, fresh, System.currentTimeMillis(), expectedOwner);
        byte[] bytes = next.encode().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > CampusData.MAX_BYTES) throw new java.io.IOException("校园存档过大");
        AtomicFile f=file(c); FileOutputStream out=null;
        try { out=f.startWrite(); out.write(bytes); f.finishWrite(out); }
        catch (Exception e) { if (out != null) f.failWrite(out); throw e; }
        changed(); return next;
    }
    static synchronized CampusData bind(Context c,String fresh) throws Exception {
        String key=CampusData.accountKey(new org.json.JSONObject(fresh).getString("identity"));
        CampusData old=null; try { old=load(c); } catch (Exception ignored) { }
        if (old==null || !key.equals(old.owner)) clearAll(c);
        return update(c,fresh,null);
    }
    static synchronized Academic updateAcademic(Context c,String fresh,String owner,String run) throws Exception {
        CampusData current=load(c);
        if (current==null || !owner.equals(current.owner) || !CampusAutoRefresh.owns(c,run,owner)) throw new java.io.IOException("账号或读取任务已变化");
        Academic result=AcademicStore.update(c,fresh); changed(); return result;
    }
    static synchronized void clearAll(Context c) { AcademicStore.clear(c); TimetableStore.clear(c); clear(c); }
    static synchronized void clear(Context c) { file(c).delete(); c.getSharedPreferences("campus-binding", Context.MODE_PRIVATE).edit().clear().apply(); changed(); }
}
