package com.nopalou;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 端侧模型文件：放在应用专属目录（卸载即删，不需要存储权限），只保留一份 local.gguf。
 * 来源二选一：系统下载管理器下载（默认只用 Wi-Fi），或从手机文件里导入。
 * 写入都先落到临时文件，校验 GGUF 文件头和大小后再改名替换，半截文件不会被当成模型。
 */
final class LocalModelStore {
    private LocalModelStore() {}

    private static final String FILE = "local.gguf";
    private static final String PART = "download.part";
    private static final String KEY_NAME = "local_name";
    private static final String KEY_DOWNLOAD = "local_download_id";
    private static final String KEY_URL = "local_url";

    static File dir(Context c) {
        File external = c.getExternalFilesDir("models");
        File d = external != null ? external : new File(c.getFilesDir(), "models");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static File file(Context c) { return new File(dir(c), FILE); }

    private static SharedPreferences prefs(Context c) { return AiConfig.prefs(c); }

    /** 模型文件存在且文件头是 GGUF。 */
    static boolean present(Context c) {
        File f = file(c);
        return f.isFile() && f.length() > 0 && LocalLlmCore.isGguf(head(f));
    }

    static String name(Context c) {
        String n = prefs(c).getString(KEY_NAME, "");
        return n == null || n.isEmpty() ? FILE : n;
    }

    static long size(Context c) {
        File f = file(c);
        return f.isFile() ? f.length() : 0;
    }

    static String downloadUrl(Context c) {
        String u = prefs(c).getString(KEY_URL, LocalLlmCore.RECOMMENDED_URL);
        return u == null || u.trim().isEmpty() ? LocalLlmCore.RECOMMENDED_URL : u.trim();
    }

    static void delete(Context c) {
        cancelDownload(c);
        File f = file(c);
        if (f.exists() && !f.delete()) throw new IllegalStateException("模型文件删除失败，请稍后重试");
        prefs(c).edit().remove(KEY_NAME).apply();
    }

    /** 从文件导入：在后台线程调用。 */
    static void importFrom(Context c, Uri uri, String displayName) throws IOException {
        long declared = -1;
        try (ParcelFileDescriptor pfd = c.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd != null) declared = pfd.getStatSize();
        } catch (IOException | RuntimeException ignored) {
            // 有的文件提供方给不出大小，复制时再按实际字节数检查
        }
        if (declared > LocalLlmCore.HARD_LIMIT_BYTES) throw new IOException(tooLarge(declared));
        File tmp = new File(dir(c), "import.part");
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("无法读取所选文件");
            byte[] b = new byte[1 << 16];
            long total = 0;
            int n;
            while ((n = in.read(b)) != -1) {
                total += n;
                if (total > LocalLlmCore.HARD_LIMIT_BYTES) throw new IOException(tooLarge(total));
                out.write(b, 0, n);
            }
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        install(c, tmp, displayName == null || displayName.trim().isEmpty() ? FILE : displayName.trim());
    }

    /** 开始下载；返回 null 表示已交给系统下载管理器，否则是不能开始的原因。 */
    static String startDownload(Context c, String url, boolean allowMobileData) {
        String u = url == null ? "" : url.trim();
        if (!u.startsWith("https://")) return "下载地址必须是 https:// 开头";
        if (c.getExternalFilesDir("models") == null) return "手机存储暂不可用，可改用“从文件导入”";
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return "系统下载服务不可用，可改用“从文件导入”";
        cancelDownload(c);
        new File(dir(c), PART).delete();
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(u))
                .setTitle("楼外本机模型")
                .setDescription(LocalLlmCore.RECOMMENDED_URL.equals(u) ? LocalLlmCore.RECOMMENDED_NAME : "自定义 GGUF 模型")
                .setDestinationInExternalFilesDir(c, "models", PART)
                .setAllowedOverMetered(allowMobileData)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        long id = dm.enqueue(r);
        prefs(c).edit().putLong(KEY_DOWNLOAD, id).putString(KEY_URL, u).apply();
        return null;
    }

    static void cancelDownload(Context c) {
        long id = prefs(c).getLong(KEY_DOWNLOAD, -1);
        if (id < 0) return;
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm != null) dm.remove(id);
        prefs(c).edit().remove(KEY_DOWNLOAD).apply();
    }

    static boolean downloading(Context c) { return prefs(c).getLong(KEY_DOWNLOAD, -1) >= 0; }

    /**
     * 查看下载进度；下载完成时校验并装好。返回给设置页显示的一句话，没有进行中的下载时返回空串。
     * 设置页每次刷新、复核任务开始时都会调用，所以不需要单独的广播接收器。
     */
    static String pollDownload(Context c) {
        long id = prefs(c).getLong(KEY_DOWNLOAD, -1);
        if (id < 0) return "";
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return "";
        try (Cursor q = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (q == null || !q.moveToFirst()) {
                prefs(c).edit().remove(KEY_DOWNLOAD).apply();
                return "下载已被取消";
            }
            int status = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long done = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                prefs(c).edit().remove(KEY_DOWNLOAD).apply();
                try {
                    String url = downloadUrl(c);
                    install(c, new File(dir(c), PART), url.substring(url.lastIndexOf('/') + 1));
                    return "下载完成，已装好本机模型";
                } catch (IOException e) {
                    return "下载的文件不可用：" + e.getMessage();
                }
            }
            if (status == DownloadManager.STATUS_FAILED) {
                prefs(c).edit().remove(KEY_DOWNLOAD).apply();
                dm.remove(id);
                return "下载失败，请检查网络或下载地址后重试";
            }
            if (total > LocalLlmCore.HARD_LIMIT_BYTES) {
                cancelDownload(c);
                return "已停止下载：" + tooLarge(total);
            }
            String progress = total > 0 ? (done * 100 / total) + "%（" + LocalLlmCore.sizeText(done) + " / " + LocalLlmCore.sizeText(total) + "）"
                    : LocalLlmCore.sizeText(done);
            if (status == DownloadManager.STATUS_PAUSED) return "下载已暂停，等待 Wi-Fi 或网络恢复 · " + progress;
            return "正在下载 " + progress;
        } catch (RuntimeException e) {
            return "无法读取下载进度";
        }
    }

    private static void install(Context c, File tmp, String name) throws IOException {
        try {
            if (!tmp.isFile()) throw new IOException("文件不存在");
            if (tmp.length() > LocalLlmCore.HARD_LIMIT_BYTES) throw new IOException(tooLarge(tmp.length()));
            if (!LocalLlmCore.isGguf(head(tmp))) throw new IOException("不是 GGUF 模型文件（llama.cpp 格式）");
            File target = file(c);
            if (!tmp.renameTo(target)) {
                target.delete();
                if (!tmp.renameTo(target)) throw new IOException("模型文件保存失败");
            }
            prefs(c).edit().putString(KEY_NAME, name).apply();
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    private static String tooLarge(long bytes) {
        return "文件 " + LocalLlmCore.sizeText(bytes) + "，超过 " + LocalLlmCore.sizeText(LocalLlmCore.HARD_LIMIT_BYTES)
                + " 上限，手机上难以运行，请换更小的量化版本";
    }

    private static byte[] head(File f) {
        byte[] b = new byte[8];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) return null;
                off += n;
            }
            return b;
        } catch (IOException e) {
            return null;
        }
    }
}
