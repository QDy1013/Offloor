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
        CampusData raw=loadRaw(c);
        return raw==null?null:CampusScope.compact(busFallback(c,raw));
    }
    private static CampusData loadRaw(Context c) throws Exception {
        AtomicFile f = file(c);
        if (!f.getBaseFile().exists() && !new File(f.getBaseFile() + ".bak").exists()) {
            try (java.io.InputStream in = c.getAssets().open("campus-public.json")) { return read(in); }
            catch (java.io.FileNotFoundException e) { return null; }
        }
        try (java.io.InputStream in=f.openRead()) { return read(in); }
    }
    private static CampusData read(java.io.InputStream in) throws Exception {
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n;
            while ((n=in.read(b)) != -1) { if (out.size() + n > CampusData.MAX_BYTES) throw new java.io.IOException("校园存档过大"); out.write(b, 0, n); }
            return new CampusData(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }
    static synchronized CampusData update(Context c, String fresh, String expectedOwner) throws Exception {
        // Merge against the complete disk snapshot. Hiding a feature never deletes its stored data.
        CampusData old=loadRaw(c), next=busFallback(c,CampusData.merge(old,fresh,System.currentTimeMillis(),expectedOwner));
        CampusData selected=CampusScope.compact(next);
        write(c,next); changed(); CampusChanges.campus(c,selected); return selected;
    }
    private static void write(Context c,CampusData next) throws Exception {
        byte[] bytes = next.encode().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > CampusData.MAX_BYTES) throw new java.io.IOException("校园存档过大");
        UpgradeDataBackup.beforeWrite(c);
        AtomicFile f=file(c); FileOutputStream out=null;
        try { out=f.startWrite(); out.write(bytes); f.finishWrite(out); }
        catch (Exception e) { if (out != null) f.failWrite(out); throw e; }
    }
    private static CampusData busFallback(Context c,CampusData data) throws Exception {
        org.json.JSONObject existing=data.module("bus");
        if (existing!=null && existing.optJSONArray("tables")!=null && existing.getJSONArray("tables").length()>0) return data;
        try (java.io.InputStream in=c.getAssets().open("campus-bus.json")) {
            org.json.JSONObject json=new org.json.JSONObject(data.encode());
            org.json.JSONObject bus=new org.json.JSONObject(read(in).module("bus").toString());
            json.getJSONObject("modules").put("bus",bus); return new CampusData(json.toString());
        } catch (java.io.FileNotFoundException ignored) { return data; }
    }
    static synchronized CampusData bind(Context c,String fresh) throws Exception {
        String key=CampusData.accountKey(new org.json.JSONObject(fresh).getString("identity"));
        CampusData old=loadRaw(c);
        UpgradeDataBackup.beforeWrite(c);
        // 只有确实换了账号（旧存档有主人且不是这个人）才连课表、成绩一起清；
        // 旧存档没有主人（首次、或用户只清了校园数据）时只清校园部分，课表和成绩留着，
        // 否则“清空校园数据 → 再同步但这次没读到课表”会把课表弄丢。
        boolean otherAccount=old!=null && old.owner!=null && !old.owner.isEmpty() && !key.equals(old.owner);
        // 先完整校验，新数据无效时任何旧文件都不变。
        CampusData next=busFallback(c,CampusScope.compact(CampusData.merge(old,fresh,System.currentTimeMillis(),null)));
        String previousOwner=old==null || old.owner.isEmpty()?key:old.owner;
        AcademicStore.stampOwner(c,previousOwner); TimetableStore.stampOwner(c,previousOwner);
        write(c,next);
        // 新快照写入后，旧账号文件即使在清理中被杀进程，也会被账号边界隐藏。
        if (otherAccount) { AcademicStore.clear(c); TimetableStore.clear(c); }
        if (old==null || !key.equals(old.owner)) c.getSharedPreferences("campus-binding",Context.MODE_PRIVATE).edit().clear().apply();
        changed(); CampusChanges.campus(c,next); return next;
    }
    static synchronized Academic updateAcademic(Context c,String fresh,String owner,String run) throws Exception {
        CampusData current=load(c);
        if (current==null || !owner.equals(current.owner) || !CampusAutoRefresh.owns(c,run,owner)) throw new java.io.IOException("账号或读取任务已变化");
        Academic result=AcademicStore.update(c,fresh); changed(); return result;
    }
    static synchronized void clearAll(Context c) { CampusCredentials.clear(c); AcademicStore.clear(c); TimetableStore.clear(c); clear(c); }
    static synchronized void clear(Context c) { file(c).delete(); c.getSharedPreferences("campus-binding", Context.MODE_PRIVATE).edit().clear().apply(); changed(); }
}
