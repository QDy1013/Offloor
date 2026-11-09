package com.nopalou;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;

/** 存档格式校验与摘要；不依赖 Android，可对截断和迁移做故障测试。 */
final class StateFormat {
    static final int MAX_BYTES = 32 * 1024 * 1024;
    private StateFormat() { }

    static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > MAX_BYTES) throw new IOException("存档超过支持的大小");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    static String digest(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    static byte[] seal(byte[] payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(payload);
        out.write(("CHECKSUM\t" + digest(payload) + "\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    static void validate(byte[] bytes) throws Exception {
        if (bytes.length > MAX_BYTES) throw new IOException("存档超过支持的大小");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (!text.startsWith("NPL1\n") && !text.startsWith("NPL1\r\n")) throw new IOException("存档文件头无效");
        int checksum = text.indexOf("\nCHECKSUM\t");
        boolean version2 = text.matches("(?s).*\nFORMAT\t2\r?\n.*");
        if (version2 && checksum < 0) throw new IOException("存档摘要缺失，可能未写完");
        if (checksum >= 0) {
            String value = text.substring(checksum + "\nCHECKSUM\t".length()).trim();
            byte[] payload = text.substring(0, checksum + 1).getBytes(StandardCharsets.UTF_8);
            if (!value.matches("[a-f0-9]{64}") || !value.equals(digest(payload))) throw new IOException("存档摘要不符");
            text = text.substring(0, checksum + 1);
        }
        int expected = -1, actual = 0, contexts = 0, expectedContexts = -2;
        boolean inCard = false; String id = null, kind = null;
        Set<String> ids = new HashSet<>(), fields = new HashSet<>();
        Set<String> numeric = new HashSet<>(Arrays.asList("deadline", "sourceTs", "removedAt", "lastChangeTs", "changeRevision"));
        Set<String> flags = new HashSet<>(Arrays.asList("deadlineUserSet", "needsConfirm", "updated", "done", "ai", "cancelled", "changeBase", "sourceTimeUncertain"));
        for (String line : text.split("\r?\n", -1)) {
            if (line.isEmpty() || "NPL1".equals(line)) continue;
            int tab = line.indexOf('\t');
            String key = tab < 0 ? line : line.substring(0, tab), value = tab < 0 ? "" : line.substring(tab + 1);
            if ("CARD".equals(key)) {
                if (inCard || expected < 0) throw new IOException("卡片记录顺序无效");
                inCard = true; id = kind = null; contexts = 0; expectedContexts = -2; fields.clear(); continue;
            }
            if ("END".equals(key)) {
                if (!inCard || Engine.blank(id) || Engine.blank(kind) || !ids.add(id)) throw new IOException("卡片编号缺失或重复");
                if (expectedContexts >= 0 && contexts != expectedContexts) throw new IOException("卡片上下文不完整");
                if (expectedContexts == -1 && contexts != 0) throw new IOException("空上下文包含记录");
                actual++; inCard = false; continue;
            }
            if (inCard) {
                if (!"CTX".equals(key) && !"HISTORY".equals(key) && !"PENDINGCHANGE".equals(key) && !fields.add(key)) throw new IOException("卡片字段重复");
                if ("id".equals(key)) id = Engine.unesc(value);
                else if ("kind".equals(key)) kind = Engine.unesc(value);
                else if (numeric.contains(key) && number(value) < 0) throw new IOException("卡片时间无效");
                else if (flags.contains(key) && !"0".equals(value) && !"1".equals(value)) throw new IOException("卡片状态无效");
                else if ("confidence".equals(key)) {
                    double v = Double.parseDouble(value);
                    if (!Double.isFinite(v) || v < 0 || v > 1) throw new IOException("卡片把握值无效");
                } else if ("CTXN".equals(key)) {
                    long count = number(value);
                    if (count < -1 || count > 10000) throw new IOException("上下文数量无效");
                    expectedContexts = (int) count;
                } else if ("CTX".equals(key)) contexts++;
            } else if ("N".equals(key)) {
                if (expected >= 0 || actual > 0) throw new IOException("卡片总数重复");
                long count = number(value);
                if (count < 0 || count > 100000) throw new IOException("卡片总数无效");
                expected = (int) count;
            } else if ("FORMAT".equals(key) && !"2".equals(value)) throw new IOException("存档版本暂不支持");
            else if ("SEQ".equals(key) && number(value) < 0) throw new IOException("编号序列无效");
        }
        if (inCard || expected < 0 || expected != actual) throw new IOException("存档卡片数量不符或记录未结束");
    }

    static long number(String value) throws IOException {
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { throw new IOException("存档数字字段无效", error); }
    }
}
