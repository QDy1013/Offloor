package com.nopalou;

import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Independent update channel, rollback, package identity and file integrity boundaries. */
public class UpdateCatalogTest {
    static int n;
    static final String PKG="com.nopalou.preview", CERT="a".repeat(64);
    static final byte[] BYTES="Synthetic APK bytes for update integrity checks".getBytes(StandardCharsets.UTF_8);
    static String digest() throws Exception { return UpdateCatalog.hex(MessageDigest.getInstance("SHA-256").digest(BYTES)); }
    static JSONObject item(String pkg) throws Exception {
        return new JSONObject().put("versionCode",22).put("versionName","0.22").put("sha256",digest()).put("certificateSha256",CERT).put("size",BYTES.length)
                .put("url",UpdateCatalog.ROOT+"apks/"+digest()+"/"+UpdateCatalog.filename(pkg)).put("notes","Synthetic notes");
    }
    static String catalog(String pkg,JSONObject item) throws Exception { return new JSONObject().put("schema",1).put("packages",new JSONObject().put(pkg,item)).toString(); }
    static void check(String label,boolean ok) { if (!ok) throw new AssertionError(label); n++; System.out.println("PASS "+label); }
    interface Action { void run() throws Exception; }
    static void rejects(String label,Action action) throws Exception { try { action.run(); } catch (Exception e) { check(label,true); return; } throw new AssertionError(label); }
    public static void main(String[] args) throws Exception {
        UpdateCatalog t=new UpdateCatalog(catalog(PKG,item(PKG)),PKG,21);
        check("select matching variant",t.packageName.equals(PKG) && t.versionCode==22);
        check("home notice appears for a newer compatible version",t.shouldNotify(21,CERT));
        check("home notice stays hidden for the installed version",!t.shouldNotify(22,CERT));
        check("home notice stays hidden after upgrading beyond cached metadata",!t.shouldNotify(23,CERT));
        check("home notice never offers a different signing identity",!t.shouldNotify(21,"b".repeat(64)));
        check("same version may be downloaded again",new UpdateCatalog(catalog(PKG,item(PKG)),PKG,22).versionCode==22);
        rejects("downgrade rejected",() -> new UpdateCatalog(catalog(PKG,item(PKG)),PKG,23));
        rejects("another package is never used as fallback",() -> new UpdateCatalog(catalog(PKG,item(PKG)),"com.nopalou",1));
        rejects("unknown app ID has no implicit update channel",() -> UpdateCatalog.filename("unknown.app"));
        rejects("mismatched variant filename rejected",() -> new UpdateCatalog(catalog(PKG,item(PKG).put("url",UpdateCatalog.ROOT+"apks/"+digest()+"/offloor.apk")),PKG,1));
        rejects("external APK origin rejected",() -> new UpdateCatalog(catalog(PKG,item(PKG).put("url","https://evil.test/offloor-preview.apk")),PKG,1));
        rejects("wrong digest path rejected",() -> new UpdateCatalog(catalog(PKG,item(PKG).put("url",UpdateCatalog.ROOT+"apks/"+"b".repeat(64)+"/offloor-preview.apk")),PKG,1));
        rejects("malformed digest rejected",() -> new UpdateCatalog(catalog(PKG,item(PKG).put("sha256","bad")),PKG,1));
        rejects("fractional version rejected",() -> new UpdateCatalog(catalog(PKG,item(PKG).put("versionCode",22.5)),PKG,1));
        rejects("fractional byte count rejected",() -> new UpdateCatalog(catalog(PKG,item(PKG).put("size",20.5)),PKG,1));
        rejects("unbounded download rejected",() -> new UpdateCatalog(catalog(PKG,item(PKG).put("size",UpdateCatalog.MAX_APK+1)),PKG,1));
        check("metadata HTTPS endpoint and cache key accepted",UpdateCatalog.metadataUrl(UpdateCatalog.LATEST+"?t=123"));
        check("metadata untrusted host, port and downgrade rejected",!UpdateCatalog.metadataUrl(UpdateCatalog.LATEST.replace("https:","http:")) && !UpdateCatalog.metadataUrl("https://raw.githubusercontent.com.evil.test/QDy1013/Offloor/updates/latest.json") && !UpdateCatalog.metadataUrl("https://raw.githubusercontent.com:8443/QDy1013/Offloor/updates/latest.json"));
        t.verify(new ByteArrayInputStream(BYTES)); check("exact downloaded bytes verified",true);
        rejects("truncated download rejected",() -> t.verify(new ByteArrayInputStream(java.util.Arrays.copyOf(BYTES,BYTES.length-1))));
        byte[] bad=BYTES.clone(); bad[0]^=1;
        rejects("same-size corrupt download rejected",() -> t.verify(new ByteArrayInputStream(bad)));
        rejects("oversized stream stopped",() -> t.verify(new ByteArrayInputStream(new byte[BYTES.length+1])));
        t.verifyIdentity(PKG,22,CERT); check("archive package version and signer accepted",true);
        rejects("archive package substitution rejected",() -> t.verifyIdentity("com.nopalou",22,CERT));
        rejects("archive version substitution rejected",() -> t.verifyIdentity(PKG,23,CERT));
        rejects("archive signer substitution rejected",() -> t.verifyIdentity(PKG,22,"b".repeat(64)));
        if (args.length>0) {
            String raw=java.nio.file.Files.readString(java.nio.file.Paths.get(args[0]));
            JSONObject p=new JSONObject(raw).getJSONObject("packages");
            long expected=args.length>1?Long.parseLong(args[1]):22;
            for (java.util.Iterator<String> it=p.keys();it.hasNext();) { String pkg=it.next(); UpdateCatalog published=new UpdateCatalog(raw,pkg,1); check("real publication metadata accepted for "+pkg,published.versionCode==expected); }
        }
        System.out.println(n+" checks passed");
    }
}
