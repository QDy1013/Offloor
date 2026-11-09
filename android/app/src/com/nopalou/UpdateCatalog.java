package com.nopalou;

import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Locale;

/** Public update catalog. Package, digest and immutable download path must agree. */
final class UpdateCatalog {
    static final String ROOT="https://raw.githubusercontent.com/QDy1013/Offloor/updates/";
    static final String LATEST=ROOT+"latest.json";
    static final long MAX_APK=200L*1024*1024;
    final String packageName,versionName,sha256,url,certificate,notes;
    final long versionCode,size;
    final JSONObject json;
    static String filename(String packageName) {
        switch (packageName) {
            case "com.nopalou": return "offloor.apk";
            case "com.nopalou.preview": return "offloor-preview.apk";
            case "com.nopalou.timetabletest": return "offloor-timetable-test.apk";
            default: throw new IllegalArgumentException("当前版本没有对应的更新渠道");
        }
    }
    UpdateCatalog(String raw,String installedPackage,long installedVersion) throws Exception {
        if (raw==null || raw.length()>100_000) throw new IOException("更新信息过大");
        JSONObject root=new JSONObject(raw);
        Object schema=root.get("schema");
        if (!(schema instanceof Number) || ((Number)schema).doubleValue()!=1) throw new IOException("更新信息格式暂不支持");
        JSONObject packages=root.getJSONObject("packages"), j=packages.optJSONObject(installedPackage);
        if (j==null) throw new IOException("当前安装渠道尚未发布可覆盖安装的更新包");
        packageName=installedPackage; filename(packageName);
        Object code=j.get("versionCode");
        if (!(code instanceof Number) || ((Number)code).doubleValue()!=((Number)code).longValue()) throw new IOException("更新版本号无效");
        versionCode=((Number)code).longValue();
        if (versionCode<installedVersion || versionCode<1 || versionCode>Integer.MAX_VALUE) throw new IOException("线上安装包比当前版本旧，请稍后再试");
        versionName=j.getString("versionName");
        if (versionName.isEmpty() || versionName.length()>60) throw new IOException("更新版本名称无效");
        sha256=j.getString("sha256"); certificate=j.getString("certificateSha256");
        if (!sha256.matches("[a-f0-9]{64}") || !certificate.matches("[a-f0-9]{64}")) throw new IOException("更新校验信息无效");
        Object bytes=j.get("size");
        if (!(bytes instanceof Number) || ((Number)bytes).doubleValue()!=((Number)bytes).longValue()) throw new IOException("安装包大小不受支持");
        size=((Number)bytes).longValue(); if (size<1 || size>MAX_APK) throw new IOException("安装包大小不受支持");
        url=j.getString("url");
        String expected=ROOT+"apks/"+sha256+"/"+filename(packageName);
        if (!url.equals(expected)) throw new IOException("安装包来源与当前版本不匹配");
        notes=j.optString("notes",""); if (notes.length()>3000) throw new IOException("更新说明过长");
        json=new JSONObject().put("schema",1).put("packages",new JSONObject().put(packageName,j));
    }
    static boolean metadataUrl(String url) {
        try { URI u=new URI(url); return LATEST.equals(u.getScheme()+"://"+u.getRawAuthority()+u.getRawPath()) && u.getFragment()==null && u.getUserInfo()==null; }
        catch (Exception e) { return false; }
    }
    static String hex(byte[] bytes) { StringBuilder s=new StringBuilder(); for (byte b:bytes) s.append(String.format(Locale.ROOT,"%02x",b&255)); return s.toString(); }
    void verify(InputStream in) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[16384]; long n=0; int read;
        while ((read=in.read(buffer))!=-1) { n+=read; if (n>size || n>MAX_APK) throw new IOException("安装包大小与更新信息不一致"); digest.update(buffer,0,read); }
        if (n!=size || !sha256.equals(hex(digest.digest()))) throw new IOException("安装包校验失败，请重新下载");
    }
    void verifyIdentity(String actualPackage,long actualVersion,String actualCertificate) throws IOException {
        if (!packageName.equals(actualPackage) || versionCode!=actualVersion || !certificate.equals(actualCertificate)) throw new IOException("安装包版本或签名不匹配，请重新下载");
    }
}
