package com.nopalou;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** 不可导出的 AndroidKeyStore 密钥；迁移成功提交后才删除旧明文。 */
final class AiSecrets {
    private static final String ALIAS = "outfloor.ai.credentials.v1";
    private static String cachedCipher, cachedPlain;
    private static synchronized SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }
    static String encode(String value) throws Exception {
        return value.isEmpty() ? "" : Base64.encodeToString(SecretCodec.encrypt(key(), value), Base64.NO_WRAP);
    }
    static synchronized String read(SharedPreferences prefs) throws Exception {
        String encrypted = prefs.getString("key_cipher", "");
        if (!encrypted.isEmpty()) {
            if(encrypted.equals(cachedCipher) && cachedPlain!=null) return cachedPlain;
            String plain=SecretCodec.decrypt(key(), Base64.decode(encrypted, Base64.NO_WRAP));
            cachedCipher=encrypted;cachedPlain=plain;return plain;
        }
        cachedCipher=cachedPlain=null;
        String legacy = prefs.getString("key", "").trim();
        if (legacy.isEmpty()) return "";
        String migrated = encode(legacy);
        if (!prefs.edit().putString("key_cipher", migrated).remove("key").commit()) throw new Exception("迁移未保存");
        cachedCipher=migrated;cachedPlain=legacy;
        return legacy;
    }
}
