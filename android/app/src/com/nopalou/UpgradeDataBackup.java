package com.nopalou;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** A private snapshot before this version's first data write; never deletes prior snapshots. */
final class UpgradeDataBackup {
    private static String ready;
    static synchronized void beforeWrite(Context context) throws IOException {
        Context app=context.getApplicationContext();
        if (app==null) app=context;
        try {
            android.content.pm.PackageInfo info=app.getPackageManager().getPackageInfo(app.getPackageName(),0);
            long version=Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;
            File destination=new File(app.getNoBackupFilesDir(),"upgrade-snapshots/v"+version);
            String key=destination.getAbsolutePath();
            if (key.equals(ready)) return;
            preserve(app.getFilesDir(),new File(app.getApplicationInfo().dataDir,"shared_prefs"),destination);
            ready=key;
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("更新前的数据备份未完成，保留原存档",e); }
    }
    private static void preserve(File files,File preferences,File destination) throws IOException {
        File complete=new File(destination,"complete");
        if (complete.isFile()) return;
        File pending=new File(destination,"pending");
        if (!pending.isDirectory() && !pending.mkdirs()) throw new IOException("无法创建备份临时目录");
        copyDirectory(files,new File(destination,"files"),pending);
        copyDirectory(preferences,new File(destination,"shared_prefs"),pending);
        File marker=File.createTempFile("complete-",".part",pending);
        try {
            try (FileOutputStream out=new FileOutputStream(marker)) { out.write(1); out.getFD().sync(); }
            if (!marker.renameTo(complete)) throw new IOException("无法完成更新备份");
        } finally { if (marker.exists()) marker.delete(); }
    }
    private static void copyDirectory(File source,File destination,File pending) throws IOException {
        if (!source.exists()) return;
        File[] children=source.listFiles();
        if (children==null) throw new IOException("无法读取原数据目录");
        if (!destination.isDirectory() && !destination.mkdirs()) throw new IOException("无法创建更新备份");
        for (File child:children) {
            File target=new File(destination,child.getName());
            if (child.isDirectory()) { copyDirectory(child,target,pending); continue; }
            // Raw names such as cards.dat.bak are user data here, not AtomicFile metadata.
            File temporary=File.createTempFile("snapshot-",".part",pending);
            try {
                try (FileInputStream in=new FileInputStream(child); FileOutputStream out=new FileOutputStream(temporary)) {
                    byte[] buffer=new byte[16384]; int n;
                    while ((n=in.read(buffer))!=-1) out.write(buffer,0,n);
                    out.getFD().sync();
                }
                if (target.exists() && !target.delete()) throw new IOException("无法替换未完成的备份文件");
                if (!temporary.renameTo(target)) throw new IOException("无法完成备份文件");
            } finally { if (temporary.exists()) temporary.delete(); }
        }
    }
}
