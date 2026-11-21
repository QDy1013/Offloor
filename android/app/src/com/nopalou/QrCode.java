package com.nopalou;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 标准 ISO/IEC 18004 QR Code 矩阵编码器（Byte 模式，支持 L / M 级纠错与 Version 1–10）。
 * 纯 Java 实现，无外部依赖，供离线核验二维码展示。
 */
final class QrCode {
    enum Level { L, M }

    private static final int[] GF_EXP = new int[512];
    private static final int[] GF_LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            GF_EXP[i] = x;
            GF_LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11d;
        }
        for (int i = 255; i < 512; i++) GF_EXP[i] = GF_EXP[i - 255];
    }

    // [totalDataBytes, ecPerBlock, g1Blocks, g1DataBytes, g2Blocks, g2DataBytes] for Version 1..10
    private static final int[][] SPEC_L = {
            null,
            {19, 7, 1, 19, 0, 0},
            {34, 10, 1, 34, 0, 0},
            {55, 15, 1, 55, 0, 0},
            {80, 20, 1, 80, 0, 0},
            {108, 26, 1, 108, 0, 0},
            {136, 18, 2, 68, 0, 0},
            {156, 20, 2, 78, 0, 0},
            {194, 24, 2, 97, 0, 0},
            {232, 30, 2, 116, 0, 0},
            {274, 18, 2, 68, 2, 69}
    };

    private static final int[][] SPEC_M = {
            null,
            {16, 10, 1, 16, 0, 0},
            {28, 16, 1, 28, 0, 0},
            {44, 26, 1, 44, 0, 0},
            {64, 18, 2, 32, 0, 0},
            {86, 24, 2, 43, 0, 0},
            {108, 16, 4, 27, 0, 0},
            {124, 18, 4, 31, 0, 0},
            {154, 22, 2, 38, 2, 39},
            {182, 22, 3, 36, 2, 37},
            {216, 26, 4, 43, 1, 44}
    };

    private static final int[][] ALIGNMENT = {
            null,
            {},
            {6, 18},
            {6, 22},
            {6, 26},
            {6, 30},
            {6, 34},
            {6, 22, 38},
            {6, 24, 42},
            {6, 26, 46},
            {6, 28, 50}
    };

    private QrCode() { }

    static boolean[][] encode(String text) {
        return encode(text, Level.M);
    }

    /** 将字符串按 UTF-8 编码成正方形二维码模块矩阵（true 表示深色模块）。 */
    static boolean[][] encode(String text, Level level) {
        if (text == null || text.isEmpty()) throw new IllegalArgumentException("empty qr text");
        Level ecc = level == null ? Level.M : level;
        int[][] table = ecc == Level.L ? SPEC_L : SPEC_M;
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        int version = pickVersion(raw.length, table);
        int[] spec = table[version];
        byte[] dataBytes = buildDataBytes(raw, version, spec[0]);
        byte[] allCodewords = interleave(dataBytes, spec);

        int size = version * 4 + 17;
        boolean[][] modules = new boolean[size][size];
        boolean[][] function = new boolean[size][size];

        placeFunctionPatterns(modules, function, version, size);
        placeCodewords(modules, function, allCodewords, size);

        int bestMask = 0;
        int bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] trial = copy(modules);
            applyMask(trial, function, mask, size);
            drawFormatInfo(trial, ecc, mask, size);
            if (version >= 7) drawVersionInfo(trial, version, size);
            int penalty = penalty(trial, size);
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                bestMask = mask;
            }
        }
        applyMask(modules, function, bestMask, size);
        drawFormatInfo(modules, ecc, bestMask, size);
        if (version >= 7) drawVersionInfo(modules, version, size);
        return modules;
    }

    private static int pickVersion(int byteLen, int[][] table) {
        for (int v = 1; v <= 10; v++) {
            int headerBits = v <= 9 ? 12 : 20;
            if (headerBits + byteLen * 8 <= table[v][0] * 8) return v;
        }
        throw new IllegalArgumentException("qr text too long: " + byteLen);
    }

    private static byte[] buildDataBytes(byte[] raw, int version, int totalBytes) {
        BitBuffer buf = new BitBuffer(totalBytes * 8);
        buf.append(0x4, 4); // Byte mode
        buf.append(raw.length, version <= 9 ? 8 : 16);
        for (byte b : raw) buf.append(b & 0xFF, 8);

        int capBits = totalBytes * 8;
        int term = Math.min(4, capBits - buf.bitLen);
        buf.append(0, term);
        if ((buf.bitLen & 7) != 0) buf.append(0, 8 - (buf.bitLen & 7));

        for (int pad = 0; buf.bitLen < capBits; pad++) {
            buf.append((pad & 1) == 0 ? 0xEC : 0x11, 8);
        }
        return buf.bytes;
    }

    private static byte[] interleave(byte[] data, int[] spec) {
        int ecLen = spec[1], g1 = spec[2], d1 = spec[3], g2 = spec[4], d2 = spec[5];
        int blocks = g1 + g2;
        byte[][] dataBlocks = new byte[blocks][];
        byte[][] ecBlocks = new byte[blocks][];
        byte[] divisor = rsDivisor(ecLen);

        int offset = 0;
        for (int b = 0; b < blocks; b++) {
            int len = b < g1 ? d1 : d2;
            dataBlocks[b] = Arrays.copyOfRange(data, offset, offset + len);
            ecBlocks[b] = rsRemainder(dataBlocks[b], divisor);
            offset += len;
        }

        int maxData = Math.max(d1, g2 > 0 ? d2 : d1);
        byte[] out = new byte[spec[0] + blocks * ecLen];
        int k = 0;
        for (int col = 0; col < maxData; col++) {
            for (int b = 0; b < blocks; b++) {
                if (col < dataBlocks[b].length) out[k++] = dataBlocks[b][col];
            }
        }
        for (int col = 0; col < ecLen; col++) {
            for (int b = 0; b < blocks; b++) {
                out[k++] = ecBlocks[b][col];
            }
        }
        return out;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] res = new byte[degree];
        res[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                int v = gfMul(res[j] & 0xFF, root);
                if (j + 1 < degree) v ^= (res[j + 1] & 0xFF);
                res[j] = (byte) v;
            }
            root = gfMul(root, 0x02);
        }
        return res;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        int deg = divisor.length;
        byte[] res = new byte[deg];
        for (byte datum : data) {
            int factor = (datum ^ res[0]) & 0xFF;
            System.arraycopy(res, 1, res, 0, deg - 1);
            res[deg - 1] = 0;
            for (int j = 0; j < deg; j++) {
                res[j] ^= (byte) gfMul(divisor[j] & 0xFF, factor);
            }
        }
        return res;
    }

    private static int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return GF_EXP[GF_LOG[a] + GF_LOG[b]];
    }

    private static void placeFunctionPatterns(boolean[][] m, boolean[][] f, int version, int size) {
        placeFinder(m, f, 0, 0, size);
        placeFinder(m, f, size - 7, 0, size);
        placeFinder(m, f, 0, size - 7, size);

        int[] pos = ALIGNMENT[version];
        for (int r : pos) {
            for (int c : pos) {
                if (!f[r][c]) placeAlignment(m, f, r, c);
            }
        }

        for (int i = 8; i < size - 8; i++) {
            boolean dark = (i & 1) == 0;
            setFunc(m, f, 6, i, dark);
            setFunc(m, f, i, 6, dark);
        }

        // Format info slots around finders + fixed dark module
        for (int i = 0; i <= 8; i++) {
            if (i != 6) { f[8][i] = true; f[i][8] = true; }
        }
        for (int i = size - 8; i < size; i++) f[8][i] = true;
        for (int i = size - 7; i < size; i++) f[i][8] = true;
        setFunc(m, f, size - 8, 8, true);

        if (version >= 7) {
            for (int i = 0; i < 6; i++) {
                for (int j = 0; j < 3; j++) {
                    f[i][size - 11 + j] = true;
                    f[size - 11 + j][i] = true;
                }
            }
        }
    }

    private static void placeFinder(boolean[][] m, boolean[][] f, int row, int col, int size) {
        for (int dr = -1; dr <= 7; dr++) {
            int r = row + dr;
            if (r < 0 || r >= size) continue;
            for (int dc = -1; dc <= 7; dc++) {
                int c = col + dc;
                if (c < 0 || c >= size) continue;
                boolean dark = dr >= 0 && dr <= 6 && dc >= 0 && dc <= 6
                        && (dr == 0 || dr == 6 || dc == 0 || dc == 6 || (dr >= 2 && dr <= 4 && dc >= 2 && dc <= 4));
                setFunc(m, f, r, c, dark);
            }
        }
    }

    private static void placeAlignment(boolean[][] m, boolean[][] f, int row, int col) {
        for (int dr = -2; dr <= 2; dr++) {
            for (int dc = -2; dc <= 2; dc++) {
                int dist = Math.max(Math.abs(dr), Math.abs(dc));
                setFunc(m, f, row + dr, col + dc, dist != 1);
            }
        }
    }

    private static void setFunc(boolean[][] m, boolean[][] f, int r, int c, boolean dark) {
        m[r][c] = dark;
        f[r][c] = true;
    }

    private static void placeCodewords(boolean[][] m, boolean[][] f, byte[] codewords, int size) {
        int bitIdx = 0;
        int totalBits = codewords.length * 8;
        int right = size - 1;
        while (right >= 1) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int col = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int row = upward ? (size - 1 - vert) : vert;
                    if (!f[row][col] && bitIdx < totalBits) {
                        int b = codewords[bitIdx >>> 3] & 0xFF;
                        m[row][col] = ((b >>> (7 - (bitIdx & 7))) & 1) != 0;
                        bitIdx++;
                    }
                }
            }
            right -= 2;
        }
    }

    private static void applyMask(boolean[][] m, boolean[][] f, int mask, int size) {
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (f[r][c]) continue;
                boolean inv;
                switch (mask) {
                    case 0: inv = ((r + c) & 1) == 0; break;
                    case 1: inv = (r & 1) == 0; break;
                    case 2: inv = (c % 3) == 0; break;
                    case 3: inv = ((r + c) % 3) == 0; break;
                    case 4: inv = (((r / 2) + (c / 3)) & 1) == 0; break;
                    case 5: inv = ((r * c) % 2 + (r * c) % 3) == 0; break;
                    case 6: inv = ((((r * c) % 2) + ((r * c) % 3)) & 1) == 0; break;
                    default: inv = ((((r + c) % 2) + ((r * c) % 3)) & 1) == 0; break;
                }
                if (inv) m[r][c] = !m[r][c];
            }
        }
    }

    private static void drawFormatInfo(boolean[][] m, Level level, int mask, int size) {
        int data = ((level == Level.L ? 1 : 0) << 3) | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ (((rem >>> 9) & 1) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) m[8][i] = bit(bits, 14 - i);
        m[8][7] = bit(bits, 8);
        m[8][8] = bit(bits, 7);
        m[7][8] = bit(bits, 6);
        for (int i = 0; i <= 5; i++) m[5 - i][8] = bit(bits, 5 - i);

        for (int i = 0; i < 8; i++) m[8][size - 1 - i] = bit(bits, i);
        for (int i = 8; i < 15; i++) m[size - 15 + i][8] = bit(bits, i);
    }

    private static void drawVersionInfo(boolean[][] m, int version, int size) {
        int rem = version;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ (((rem >>> 11) & 1) * 0x1F25);
        int bits = (version << 12) | rem;
        for (int i = 0; i < 18; i++) {
            boolean dark = bit(bits, i);
            int a = size - 11 + (i % 3);
            int b = i / 3;
            m[a][b] = dark;
            m[b][a] = dark;
        }
    }

    private static boolean bit(int val, int idx) { return ((val >>> idx) & 1) != 0; }

    private static boolean[][] copy(boolean[][] src) {
        boolean[][] out = new boolean[src.length][];
        for (int i = 0; i < src.length; i++) out[i] = src[i].clone();
        return out;
    }

    private static int penalty(boolean[][] m, int size) {
        int score = 0;
        // N1: 5+ consecutive same-color modules in rows/cols
        for (int r = 0; r < size; r++) {
            int runRow = 1, runCol = 1;
            for (int c = 1; c < size; c++) {
                if (m[r][c] == m[r][c - 1]) runRow++;
                else { if (runRow >= 5) score += 3 + (runRow - 5); runRow = 1; }
                if (m[c][r] == m[c - 1][r]) runCol++;
                else { if (runCol >= 5) score += 3 + (runCol - 5); runCol = 1; }
            }
            if (runRow >= 5) score += 3 + (runRow - 5);
            if (runCol >= 5) score += 3 + (runCol - 5);
        }
        // N2: 2x2 same-color blocks
        for (int r = 0; r < size - 1; r++) {
            for (int c = 0; c < size - 1; c++) {
                boolean v = m[r][c];
                if (v == m[r][c + 1] && v == m[r + 1][c] && v == m[r + 1][c + 1]) score += 3;
            }
        }
        // N3: 1:1:3:1:1 finder-like pattern with 4 light border modules
        for (int r = 0; r < size; r++) {
            for (int c = 0; c <= size - 11; c++) {
                if (matchesFinderLikeRow(m, r, c)) score += 40;
                if (matchesFinderLikeCol(m, r, c)) score += 40;
            }
        }
        // N4: dark ratio deviation from 50%
        int dark = 0;
        for (int r = 0; r < size; r++) for (int c = 0; c < size; c++) if (m[r][c]) dark++;
        int total = size * size;
        int k = Math.abs(dark * 20 - total * 10) / total;
        score += k * 10;
        return score;
    }

    private static boolean matchesFinderLikeRow(boolean[][] m, int r, int c) {
        return (m[r][c] && !m[r][c + 1] && m[r][c + 2] && m[r][c + 3] && m[r][c + 4] && !m[r][c + 5] && m[r][c + 6]
                && !m[r][c + 7] && !m[r][c + 8] && !m[r][c + 9] && !m[r][c + 10])
                || (!m[r][c] && !m[r][c + 1] && !m[r][c + 2] && !m[r][c + 3]
                && m[r][c + 4] && !m[r][c + 5] && m[r][c + 6] && m[r][c + 7] && m[r][c + 8] && !m[r][c + 9] && m[r][c + 10]);
    }

    private static boolean matchesFinderLikeCol(boolean[][] m, int c, int r) {
        return (m[r][c] && !m[r + 1][c] && m[r + 2][c] && m[r + 3][c] && m[r + 4][c] && !m[r + 5][c] && m[r + 6][c]
                && !m[r + 7][c] && !m[r + 8][c] && !m[r + 9][c] && !m[r + 10][c])
                || (!m[r][c] && !m[r + 1][c] && !m[r + 2][c] && !m[r + 3][c]
                && m[r + 4][c] && !m[r + 5][c] && m[r + 6][c] && m[r + 7][c] && m[r + 8][c] && !m[r + 9][c] && m[r + 10][c]);
    }

    private static final class BitBuffer {
        final byte[] bytes;
        int bitLen;

        BitBuffer(int capacityBits) { bytes = new byte[(capacityBits + 7) >>> 3]; }

        void append(int val, int len) {
            for (int i = len - 1; i >= 0; i--) {
                if (((val >>> i) & 1) != 0) {
                    bytes[bitLen >>> 3] |= (byte) (1 << (7 - (bitLen & 7)));
                }
                bitLen++;
            }
        }
    }
}
