package com.nopalou;

/** ISO/IEC 18004 原生二维码矩阵生成回归（32 位数字/单号、UTF-8、跨版本多块交织及定位符校对）。 */
public final class Step67Test {
    private static int checks;

    private static void check(String label, boolean ok) {
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + label);
        checks++;
    }

    private static boolean hasFinder(boolean[][] m, int top, int left) {
        for (int r = 0; r < 7; r++) {
            for (int c = 0; c < 7; c++) {
                boolean expected = (r == 0 || r == 6 || c == 0 || c == 6 || (r >= 2 && r <= 4 && c >= 2 && c <= 4));
                if (m[top + r][left + c] != expected) return false;
            }
        }
        return true;
    }

    public static void main(String[] args) {
        // 1. 官网 18 位核验码 objid 用 Level.L 精确落在 Version 2 (25x25)；32 位单号用 Level.M 落在 Version 3 (29x29)
        boolean[][] objidL = QrCode.encode("5013261121bf1cc533", QrCode.Level.L);
        boolean[][] guid = QrCode.encode("74dc9fb46fbd4ce496c9e4dbfda15103");
        boolean[][] digits = QrCode.encode("12345678901234567890123456789012");
        check("18-char objid with Level.L uses compact 25x25 version 2 matrix",
                objidL.length == 25 && hasFinder(objidL, 0, 0) && hasFinder(objidL, 18, 0) && hasFinder(objidL, 0, 18) && objidL[17][8]);
        check("32-char bus order uses compact 29x29 version 3 matrix",
                guid.length == 29 && guid[0].length == 29 && digits.length == 29);
        check("three 7x7 finder patterns and dark module sit at exact ISO positions",
                hasFinder(digits, 0, 0) && hasFinder(digits, 22, 0) && hasFinder(digits, 0, 22) && digits[21][8]);

        // 2. 边界长度与版本切换（V1 -> V4 -> V8 -> V10 16-bit 长度模式）
        check("version scales automatically across byte lengths",
                QrCode.encode("12345").length == 21
                        && QrCode.encode("A".repeat(55)).length == 33
                        && QrCode.encode("A".repeat(145)).length == 49
                        && QrCode.encode("A".repeat(205)).length == 57);

        // 3. UTF-8 中文支持（10 个汉字占 30 字节，落在 Version 3 = 29x29）
        boolean[][] utf8 = QrCode.encode("西北工业大学校车核验");
        check("UTF-8 multi-byte string encodes cleanly", utf8.length == 29 && hasFinder(utf8, 0, 0));

        // 4. 空串和超长输入保护
        boolean emptyRejected = false;
        try { QrCode.encode(""); } catch (IllegalArgumentException e) { emptyRejected = true; }
        boolean longRejected = false;
        try { QrCode.encode("X".repeat(214)); } catch (IllegalArgumentException e) { longRejected = true; }
        check("empty and oversized payloads fail closed", emptyRejected && longRejected);

        System.out.println(checks + " qr code checks passed");
    }
}
