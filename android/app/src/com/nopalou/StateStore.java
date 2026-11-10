package com.nopalou;

import java.io.*;

/** 两份有效存档与损坏副本；文件 IO 可在 JVM 注入故障验收。 */
final class StateStore {
    interface Validator { void validate(byte[] bytes) throws Exception; }
    static final class Loaded {
        final byte[] bytes;
        final boolean recovered, blocked;
        final String message;
        Loaded(byte[] bytes, boolean recovered, boolean blocked, String message) {
            this.bytes = bytes; this.recovered = recovered; this.blocked = blocked; this.message = message;
        }
    }
    final File base, backup, pending;
    private volatile boolean blocked;
    StateStore(File base) {
        this.base = base; this.backup = new File(base.getPath() + ".backup");
        this.pending = new File(base.getPath() + ".new");
    }
    boolean blocked() { return blocked; }

    Loaded load(Validator validator) {
        boolean found = false;
        File[] files = {base, backup, new File(base.getPath() + ".bak"), pending};
        for (File file : files) {
            if (!file.exists()) continue;
            found = true;
            try {
                byte[] bytes = read(file); validator.validate(bytes);
                blocked = false;
                boolean recovered = !file.equals(base);
                return new Loaded(bytes, recovered, false, recovered ? "原存档不可用，已读取有效备份。损坏文件已保留。" : null);
            } catch (Exception error) {
                if (file.equals(base)) {
                    try { preserveCorrupt(); } catch (Exception ignored) { /* 原件仍保留，禁止无确认覆盖 */ }
                }
            }
        }
        blocked = found;
        return new Loaded(null, false, blocked, found ? "存档无法完整读取，已暂停覆盖保存。请在存档与恢复中处理，原文件仍保留。" : null);
    }

    void allowRebuild() throws Exception {
        preserveCorrupt();
        blocked = false;
    }

    void write(byte[] bytes, Validator validator) throws Exception {
        if (blocked) throw new IOException("存档异常尚未确认，不能覆盖");
        validator.validate(bytes);
        File directory = base.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法建立存档目录");
        if (base.exists()) {
            byte[] previous = null;
            try { previous = read(base); validator.validate(previous); }
            catch (Exception corrupt) { preserveCorrupt(); previous = null; }
            if (previous != null) {
                File staging = new File(backup.getPath() + ".new");
                syncWrite(staging, previous);
                replace(staging, backup);
            }
        }
        syncWrite(pending, bytes);
        validator.validate(read(pending));
        replace(pending, base);
    }

    private void preserveCorrupt() throws Exception {
        if (!base.exists() || base.length() > StateFormat.MAX_BYTES) return;
        byte[] bytes = read(base);
        File copy = new File(base.getPath() + ".corrupt-" + StateFormat.digest(bytes).substring(0, 16));
        if (!copy.exists()) syncWrite(copy, bytes);
    }
    static byte[] read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) { return StateFormat.read(in); }
    }
    static void syncWrite(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); out.getFD().sync(); }
    }
    private static void replace(File from, File to) throws IOException {
        if (from.renameTo(to)) return;
        // Windows 不替换现有目标。主存档有已同步的有效备份，备份替换时主存档仍在。
        if (to.exists() && !to.delete()) throw new IOException("无法替换存档文件");
        if (!from.renameTo(to)) throw new IOException("无法提交存档文件");
    }
}
