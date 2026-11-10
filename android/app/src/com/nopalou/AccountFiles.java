package com.nopalou;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** 校园快照与课表、成绩使用同一账号边界；写入新账号后，旧快照即使未删除也不会展示。 */
final class AccountFiles {
    static final String OWNER = "_offloorOwner";
    private static AtomicFile file(Context c, String name) { return new AtomicFile(new File(c.getFilesDir(),name)); }
    private static boolean exists(AtomicFile file) { return file.getBaseFile().exists() || new File(file.getBaseFile()+".bak").exists(); }
    private static String read(AtomicFile file) throws Exception {
        try(InputStream in=file.openRead()) { return new String(StateFormat.read(in),StandardCharsets.UTF_8); }
    }
    static String current(Context c) throws Exception {
        AtomicFile campus=file(c,"campus.json");
        return exists(campus) ? new JSONObject(read(campus)).optString("owner","") : "";
    }
    static boolean visible(JSONObject json,String owner) {
        return !json.has(OWNER) || owner.equals(json.optString(OWNER));
    }
    static String protect(Context c,String encoded) throws Exception {
        return new JSONObject(encoded).put(OWNER,current(c)).toString();
    }
    /** 首次迁移旧快照，必须先于账号切换；不覆盖已经存在的所有者。 */
    static void stamp(Context c,String name,String owner) throws Exception {
        AtomicFile file=file(c,name); if(!exists(file)) return;
        JSONObject json=new JSONObject(read(file)); if(json.has(OWNER)) return;
        byte[] bytes=json.put(OWNER,owner).toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream out=null;
        try { out=file.startWrite();out.write(bytes);file.finishWrite(out); }
        catch(Exception e) { if(out!=null)file.failWrite(out);throw e; }
    }
}
