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
            try (java.io.InputStream in = c.getAssets().open("campus-public.json")) { return busFallback(c,CampusScope.compact(read(in))); }
            catch (java.io.FileNotFoundException e) { return null; }
        }
        CampusData raw; try (java.io.InputStream in = f.openRead()) { raw=read(in); }
        CampusData selected=busFallback(c,CampusScope.compact(raw));
        if (!selected.encode().equals(raw.encode())) write(c,selected);
        return selected;
    }
    private static CampusData read(java.io.InputStream in) throws Exception {
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n;
            while ((n=in.read(b)) != -1) { if (out.size() + n > CampusData.MAX_BYTES) throw new java.io.IOException("校园存档过大"); out.write(b, 0, n); }
            return new CampusData(new String(out.toByteArray(), StandardCharsets.UTF_8));
        }
    }
    static synchronized CampusData update(Context c, String fresh, String expectedOwner) throws Exception {
        CampusData old = load(c), next = busFallback(c,CampusScope.compact(CampusData.merge(old, fresh, System.currentTimeMillis(), expectedOwner)));
        write(c,next); changed(); CampusChanges.campus(c,next); return next;
    }
    private static void write(Context c,CampusData next) throws Exception {
        byte[] bytes = next.encode().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > CampusData.MAX_BYTES) throw new java.io.IOException("校园存档过大");
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
        CampusData old=null; try { old=load(c); } catch (Exception ignored) { }
        // 只有确实换了账号（旧存档有主人且不是这个人）才连课表、成绩一起清；
        // 旧存档没有主人（首次、或用户只清了校园数据）时只清校园部分，课表和成绩留着，
        // 否则“清空校园数据 → 再同步但这次没读到课表”会把课表弄丢。
        boolean otherAccount=old!=null && old.owner!=null && !old.owner.isEmpty() && !key.equals(old.owner);
        if (otherAccount) clearAll(c);
        else if (old==null || !key.equals(old.owner)) clear(c);
        return update(c,fresh,null);
    }
    static synchronized Academic updateAcademic(Context c,String fresh,String owner,String run) throws Exception {
        CampusData current=load(c);
        if (current==null || !owner.equals(current.owner) || !CampusAutoRefresh.owns(c,run,owner)) throw new java.io.IOException("账号或读取任务已变化");
        Academic result=AcademicStore.update(c,fresh); changed(); return result;
    }
    static synchronized void clearAll(Context c) { CampusCredentials.clear(c); AcademicStore.clear(c); TimetableStore.clear(c); clear(c); }
    static synchronized void clear(Context c) { file(c).delete(); c.getSharedPreferences("campus-binding", Context.MODE_PRIVATE).edit().clear().apply(); changed(); }
}
