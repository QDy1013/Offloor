package com.nopalou;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 端侧小模型：liboffloor_llm.so（llama.cpp）的 Java 入口。不依赖 Android 类，电脑上也能加载本机版做冒烟测试。
 * 一个实例对应一次加载好的模型；同一实例的调用串行执行。用完必须 close() 释放约 1 GB 内存映射。
 */
final class LocalLlm implements AutoCloseable {
    static final String LIBRARY = "offloor_llm";

    private static Boolean loaded;
    private static String unavailable = "";

    private volatile long handle;
    /** 释放与取消互斥：取消可能来自系统叫停任务的线程，不能碰到已释放的句柄。 */
    private final Object freeLock = new Object();

    private LocalLlm(long handle) { this.handle = handle; }

    /** 推理库能否在这台设备上使用；不能时 {@link #unavailableReason()} 给出原因。 */
    static synchronized boolean supported() {
        if (loaded != null) return loaded;
        String arch = System.getProperty("os.arch", "");
        boolean android = System.getProperty("java.vm.vendor", "").toLowerCase(java.util.Locale.ROOT).contains("android")
                || System.getProperty("java.vendor", "").toLowerCase(java.util.Locale.ROOT).contains("android");
        if (android && !cpuHasDotProd()) {
            unavailable = "这台手机的处理器不支持本机模型所需的指令集（需要 ARMv8.2 dotprod）";
            return loaded = false;
        }
        try {
            System.loadLibrary(LIBRARY);
            loaded = true;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            unavailable = android && !arch.contains("aarch64") && !arch.contains("arm64")
                    ? "本机模型只支持 64 位 ARM 手机"
                    : "这个安装包没有带本机推理库";
            loaded = false;
        }
        return loaded;
    }

    static synchronized String unavailableReason() {
        supported();
        return unavailable;
    }

    /** 只在 Android 上调用：读 /proc/cpuinfo 的 Features 行。读不到时不拦，交给加载结果判断。 */
    private static boolean cpuHasDotProd() {
        File info = new File("/proc/cpuinfo");
        if (!info.canRead()) return true;
        try (BufferedReader r = new BufferedReader(new FileReader(info))) {
            String line;
            boolean sawFeatures = false;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("Features")) continue;
                sawFeatures = true;
                if ((" " + line + " ").contains(" asimddp ")) return true;
            }
            return !sawFeatures;
        } catch (IOException e) {
            return true;
        }
    }

    static LocalLlm open(File model, int threads) throws IOException {
        if (!supported()) throw new IOException(unavailable);
        if (!model.isFile()) throw new IOException("还没有下载或导入本机模型");
        long h = nativeLoad(model.getAbsolutePath().getBytes(StandardCharsets.UTF_8), LocalLlmCore.CONTEXT, threads);
        if (h == 0) throw new IOException("本机模型加载失败：文件可能不完整，或不是 llama.cpp 支持的 GGUF 模型");
        return new LocalLlm(h);
    }

    /** 推理线程数：大核数通常是总核数的一半，用一半核既快又不至于让手机明显卡顿。 */
    static int defaultThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(4, cores / 2));
    }

    synchronized String generate(String system, String user, String grammar, int maxTokens) throws IOException {
        if (handle == 0) throw new IOException("本机模型已释放");
        try {
            byte[] out = nativeGenerate(handle, LocalLlmCore.utf8(system), LocalLlmCore.utf8(user),
                    grammar == null ? null : LocalLlmCore.utf8(grammar), maxTokens);
            return out == null ? "" : new String(out, StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    int contextSize() { return handle == 0 ? 0 : nativeContext(handle); }

    /** 可从别的线程调用：让正在进行的生成尽快停下（系统叫停后台任务时用）。 */
    void cancel() {
        synchronized (freeLock) {
            if (handle != 0) nativeCancel(handle);
        }
    }

    @Override
    public synchronized void close() {
        synchronized (freeLock) {
            if (handle != 0) {
                nativeFree(handle);
                handle = 0;
            }
        }
    }

    private static native long nativeLoad(byte[] path, int nCtx, int nThreads);
    private static native int nativeContext(long handle);
    private static native byte[] nativeGenerate(long handle, byte[] system, byte[] user, byte[] grammar, int maxTokens);
    private static native void nativeCancel(long handle);
    private static native void nativeFree(long handle);
}
