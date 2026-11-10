package com.nopalou;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 带版本和认证标签的密钥密文。SecretKey 在 Android 上由系统密钥库提供。 */
final class SecretCodec {
    private static final byte[] AAD = "outfloor-ai-key-v1".getBytes(StandardCharsets.UTF_8);
    static byte[] encrypt(SecretKey key, String value) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(AAD);
        byte[] iv = cipher.getIV(), body = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[2 + iv.length + body.length];
        out[0] = 1; out[1] = (byte) iv.length;
        System.arraycopy(iv, 0, out, 2, iv.length);
        System.arraycopy(body, 0, out, 2 + iv.length, body.length);
        return out;
    }
    static String decrypt(SecretKey key, byte[] value) throws GeneralSecurityException {
        if (value == null || value.length < 30 || value.length > 65536 || value[0] != 1 || value[1] != 12)
            throw new GeneralSecurityException("密钥密文格式无效");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOfRange(value, 2, 14)));
        cipher.updateAAD(AAD);
        return new String(cipher.doFinal(value, 14, value.length - 14), StandardCharsets.UTF_8);
    }
}
