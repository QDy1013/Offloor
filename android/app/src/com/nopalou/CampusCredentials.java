package com.nopalou;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** NWPU credentials encrypted with a non-exportable, app-specific Android Keystore key. */
final class CampusCredentials {
    private static final String ALIAS = "nwpu-login-v1";
    static final class Login {
        final String account, password, owner;
        Login(String a, String p, String o) { account=a; password=p; owner=o; }
    }
    private static SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    static synchronized void save(Context c,String account,String password,String owner) throws Exception {
        if (account.isEmpty() || password.isEmpty() || owner.isEmpty()) throw new IllegalArgumentException();
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] plain=new JSONObject().put("account",account).put("password",password).put("owner",owner).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] encrypted;
        try { encrypted=cipher.doFinal(plain); } finally { java.util.Arrays.fill(plain,(byte)0); }
        if (!c.getSharedPreferences("campus-credentials",0).edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
                .putString("data",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit()) throw new java.io.IOException("凭据保存失败");
    }
    static synchronized Login load(Context c) {
        try {
            android.content.SharedPreferences p=c.getSharedPreferences("campus-credentials",0);
            if (!p.contains("data")) return null;
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(p.getString("iv",""),Base64.NO_WRAP)));
            byte[] plain=cipher.doFinal(Base64.decode(p.getString("data",""),Base64.NO_WRAP));
            try { JSONObject o=new JSONObject(new String(plain,java.nio.charset.StandardCharsets.UTF_8)); return new Login(o.getString("account"),o.getString("password"),o.getString("owner")); }
            finally { java.util.Arrays.fill(plain,(byte)0); }
        } catch (Exception e) { return null; }
    }
    static synchronized void clear(Context c) {
        c.getSharedPreferences("campus-credentials",0).edit().clear().commit();
        CampusRefreshJob.cancel(c);
    }
}
