package com.nopalou;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** User-initiated update: one tap downloads the matching APK; installation stays with Android. */
public class UpdateActivity extends Activity {
    private static final Object DOWNLOAD_LOCK=new Object();
    private static final String APK="application/vnd.android.package-archive";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private DownloadManager manager;
    private UpdateCatalog target;
    private TextView headline,detail,version,button;
    private ProgressBar progress;
    private long id=-1;
    private boolean busy,verified,resumed,autoInstall;

    static String version(Activity a) {
        try { return a.getPackageManager().getPackageInfo(a.getPackageName(),0).versionName; } catch (Exception e) { return ""; }
    }
    private long installedVersion() throws Exception { return code(getPackageManager().getPackageInfo(getPackageName(),0)); }
    static long code(PackageInfo p) { return Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode; }
    static int signatureFlags() { return Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES; }
    static String certificate(PackageInfo p) throws Exception {
        Signature[] signatures=Build.VERSION.SDK_INT>=28 && p.signingInfo!=null?p.signingInfo.getApkContentsSigners():p.signatures;
        if (signatures==null || signatures.length!=1) throw new IOException("安装签名暂不支持");
        return UpdateCatalog.hex(MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
    }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b); Ui.apply(this); prefs=getSharedPreferences("app-update",MODE_PRIVATE); manager=(DownloadManager)getSystemService(DOWNLOAD_SERVICE); build();
        id=prefs.getLong("download_id",-1);
        try { target=new UpdateCatalog(prefs.getString("target",""),getPackageName(),installedVersion()); } catch (Exception ignored) { target=null; id=-1; }
        if (id>0 && target!=null && (b!=null || activeDownload(id))) poll(); else fetchAndDownload();
    }
    private int dp(float n) { return Ui.dp(this,n); }
    private void build() {
        LinearLayout root=CourseUi.column(this); root.setBackgroundColor(Ui.BG); setContentView(root); Ui.systemBars(this,root);
        LinearLayout bar=new LinearLayout(this); bar.setPadding(dp(4),dp(8),dp(12),dp(8)); bar.setGravity(Gravity.CENTER_VERTICAL); root.addView(bar);
        bar.addView(CourseUi.icon(this,"prev","返回",this::finish)); bar.addView(CourseUi.text(this,"应用更新",21,Ui.TEXT,true));
        LinearLayout card=CourseUi.column(this); card.setPadding(dp(24),dp(28),dp(24),dp(28)); Ui.cardSurface(card,22);
        LinearLayout.LayoutParams c=new LinearLayout.LayoutParams(-1,-2); c.setMargins(dp(16),dp(24),dp(16),0); root.addView(card,c);
        card.addView(CourseUi.text(this,"Offloor",28,Ui.TEXT,true)); CourseUi.gap(card,10);
        version=CourseUi.text(this,"当前版本 "+version(this),13,Ui.SUB,false); card.addView(version); CourseUi.gap(card,28);
        headline=CourseUi.text(this,"正在获取最新版…",19,Ui.TEXT,true); card.addView(headline); CourseUi.gap(card,10);
        detail=CourseUi.text(this,"下载会由系统继续执行，离开此页面也不影响。",13,Ui.SUB,false); detail.setLineSpacing(dp(4),1); card.addView(detail); CourseUi.gap(card,22);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setIndeterminate(true); card.addView(progress,CourseUi.row(dp(8))); CourseUi.gap(card,22);
        button=CourseUi.action(this,"正在准备",true,this::primary); button.setEnabled(false); card.addView(button,CourseUi.row(dp(50)));
        TextView notes=CourseUi.text(this,"安装更新由系统确认，应用数据会保留。",12,Ui.SUB,false); notes.setPadding(dp(22),dp(20),dp(22),0); root.addView(notes);
    }
    private boolean activeDownload(long value) {
        if (manager==null) return false;
        try (Cursor c=manager.query(new DownloadManager.Query().setFilterById(value))) {
            if (c==null || !c.moveToFirst()) return false;
            int s=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            return s==DownloadManager.STATUS_PENDING || s==DownloadManager.STATUS_RUNNING || s==DownloadManager.STATUS_PAUSED;
        } catch (Exception e) { return false; }
    }
    private File downloadFile(UpdateCatalog t) throws Exception {
        File directory=getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory==null) throw new IOException("下载存储暂不可用，请稍后重试");
        return new File(directory,"offloor-update-"+t.sha256+".apk");
    }
    static String metadata() throws Exception {
        String address=UpdateCatalog.LATEST+"?t="+System.currentTimeMillis();
        for (int redirects=0;redirects<4;redirects++) {
            if (!UpdateCatalog.metadataUrl(address)) throw new IOException("更新地址不受支持");
            HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();
            connection.setConnectTimeout(15000); connection.setReadTimeout(20000); connection.setUseCaches(false); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept","application/json"); connection.setRequestProperty("Cache-Control","no-cache");
            try {
                int status=connection.getResponseCode();
                if (status==301 || status==302 || status==307 || status==308) { address=new URL(new URL(address),connection.getHeaderField("Location")).toString(); continue; }
                if (status!=200) throw new IOException(status==404?"当前更新渠道尚未发布安装包":"更新服务暂不可用，请稍后重试");
                try (java.io.InputStream in=connection.getInputStream(); java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
                    byte[] b=new byte[4096]; int n; while ((n=in.read(b))!=-1) { if (out.size()+n>100000) throw new IOException("更新信息过大"); out.write(b,0,n); }
                    return new String(out.toByteArray(),StandardCharsets.UTF_8);
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("更新地址跳转异常，请稍后重试");
    }
    private void fetchAndDownload() {
        if (busy) return; busy=true; verified=false; button.setEnabled(false); progress.setIndeterminate(true); headline.setText("正在获取最新版…");
        io.execute(() -> {
            try {
                UpdateCatalog next=new UpdateCatalog(metadata(),getPackageName(),installedVersion());
                String currentCertificate=certificate(getPackageManager().getPackageInfo(getPackageName(),signatureFlags()));
                if (!next.certificate.equals(currentCertificate)) throw new IOException("此更新包无法覆盖当前安装签名，请联系开发者发布同签名版本");
                long nextId;
                synchronized (DOWNLOAD_LOCK) {
                    long old=prefs.getLong("download_id",-1); String oldHash=prefs.getString("sha256","");
                    if (next.sha256.equals(oldHash) && (activeDownload(old) || completed(old))) nextId=old;
                    else {
                        if (manager==null) throw new IOException("系统下载管理器不可用");
                        if (old>0) manager.remove(old);
                        File file=downloadFile(next); if (file.exists() && !file.delete()) throw new IOException("旧安装包暂时无法替换，请稍后重试");
                        DownloadManager.Request request=new DownloadManager.Request(Uri.parse(next.url));
                        request.setTitle("Offloor "+next.versionName+" 安装包"); request.setDescription("下载完成后可打开安装更新"); request.setMimeType(APK);
                        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        request.setAllowedOverMetered(true); request.setAllowedOverRoaming(false);
                        request.setDestinationInExternalFilesDir(this,Environment.DIRECTORY_DOWNLOADS,file.getName());
                        nextId=manager.enqueue(request);
                        if (nextId<0) throw new IOException("下载未能开始，请重试");
                    }
                    if (!prefs.edit().putLong("download_id",nextId).putString("sha256",next.sha256).putString("target",next.json.toString()).commit()) throw new IOException("下载信息保存失败，请重试");
                }
                runOnUiThread(() -> { if (isDestroyed()) return; busy=false; target=next; id=nextId; version.setText("当前 "+version(this)+"  ·  最新 "+next.versionName); poll(); });
            } catch (Exception e) { fail(e); }
        });
    }
    private boolean completed(long value) {
        if (manager==null || value<0) return false;
        try (Cursor c=manager.query(new DownloadManager.Query().setFilterById(value))) {
            return c!=null && c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))==DownloadManager.STATUS_SUCCESSFUL;
        } catch (Exception e) { return false; }
    }
    private void poll() {
        if (isDestroyed() || busy || verified || target==null || manager==null || id<0) return;
        try (Cursor c=manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (c==null || !c.moveToFirst()) throw new IOException("下载任务已移除，请重新下载");
            int state=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long got=c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            if (state==DownloadManager.STATUS_SUCCESSFUL) { verifyDownload(false); return; }
            if (state==DownloadManager.STATUS_FAILED) { forget(id); throw new IOException("下载失败，请检查网络或存储后重试"); }
            headline.setText(state==DownloadManager.STATUS_PAUSED?"正在等待网络…":"正在下载最新版");
            detail.setText(String.format(java.util.Locale.CHINA,"%.1f / %.1f MB",Math.max(0,got)/1048576.0,target.size/1048576.0));
            progress.setIndeterminate(false); progress.setProgress((int)Math.min(100,Math.max(0,got)*100/target.size));
            button.setText("下载中"); button.setEnabled(false);
            if (resumed) handler.postDelayed(poller,600);
        } catch (Exception e) { fail(e); }
    }
    private final Runnable poller=this::poll;
    private void verifyDownload(boolean install) {
        if (busy || target==null) return; busy=true; headline.setText("正在校验安装包…"); button.setEnabled(false);
        UpdateCatalog expected=target; long downloadId=id;
        io.execute(() -> {
            try {
                File file=downloadFile(expected);
                try (java.io.InputStream in=new java.io.FileInputStream(file)) { expected.verify(in); }
                PackageInfo archive=getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),signatureFlags());
                if (archive==null) throw new IOException("安装包无法识别，请重新下载");
                expected.verifyIdentity(archive.packageName,code(archive),certificate(archive));
                String currentCertificate=certificate(getPackageManager().getPackageInfo(getPackageName(),signatureFlags()));
                if (!currentCertificate.equals(expected.certificate) || expected.versionCode<installedVersion()) throw new IOException("安装包与当前版本不兼容");
                Uri uri=manager.getUriForDownloadedFile(downloadId); if (uri==null || !"content".equals(uri.getScheme())) throw new IOException("安装包已移除，请重新下载");
                runOnUiThread(() -> { if (isDestroyed()) return; busy=false; verified=true; headline.setText("最新版安装包已下载"); detail.setText("版本 "+expected.versionName+" · 校验通过\n"+expected.notes); progress.setIndeterminate(false); progress.setProgress(100); button.setEnabled(true); button.setText("安装更新"); if (install) install(uri); });
            } catch (Exception e) {
                forget(downloadId); verified=false; fail(e);
            }
        });
    }
    private void primary() {
        if (busy) return;
        if (verified) verifyDownload(true); else fetchAndDownload();
    }
    private void forget(long failedId) {
        synchronized (DOWNLOAD_LOCK) {
            if (manager!=null && failedId>0) manager.remove(failedId);
            // A callback from a destroyed page must not erase a newer page's download task.
            if (prefs.getLong("download_id",-1)==failedId) prefs.edit().remove("download_id").apply();
            if (id==failedId) id=-1;
        }
    }
    private void install(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT>=26 && !getPackageManager().canRequestPackageInstalls()) {
                autoInstall=true; startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())),72); return;
            }
            Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,APK).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(android.content.ClipData.newRawUri("Offloor安装包",uri)); startActivity(intent);
        } catch (ActivityNotFoundException e) { headline.setText("安装包已下载"); detail.setText("系统没有可用的安装程序，可从系统下载列表打开。"); }
        catch (Exception e) { headline.setText("安装包已保留"); detail.setText("暂时无法打开系统安装程序，请稍后再试。"); }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request==72 && autoInstall) { autoInstall=false; if (Build.VERSION.SDK_INT<26 || getPackageManager().canRequestPackageInstalls()) verifyDownload(true); else detail.setText("安装权限未开启，安装包已保留，可稍后继续安装。"); }
    }
    private void fail(Exception error) {
        runOnUiThread(() -> { if (isDestroyed()) return; busy=false; verified=false; progress.setIndeterminate(false); headline.setText("暂时没有完成下载");
            detail.setText(error instanceof IOException || error instanceof IllegalArgumentException?error.getMessage():"更新暂不可用，请检查网络后重试"); button.setEnabled(true); button.setText("重新下载最新版"); });
    }
    @Override public void onResume() { super.onResume(); resumed=true; if (target!=null && !busy && !verified) poll(); }
    @Override public void onPause() { resumed=false; handler.removeCallbacks(poller); super.onPause(); }
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); io.shutdown(); super.onDestroy(); }
}
