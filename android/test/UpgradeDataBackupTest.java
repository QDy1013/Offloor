package com.nopalou;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/** JVM filesystem regression; pass a scratch directory, never an actual application's data. */
public class UpgradeDataBackupTest {
    private static int checks;
    private static void check(String name,boolean ok) {
        if (!ok) throw new AssertionError(name);
        checks++; System.out.println("PASS "+name);
    }
    private static void preserve(Path files,Path preferences,Path destination) throws Exception {
        Method method=UpgradeDataBackup.class.getDeclaredMethod("preserve",File.class,File.class,File.class);
        method.setAccessible(true);
        try { method.invoke(null,files.toFile(),preferences.toFile(),destination.toFile()); }
        catch (InvocationTargetException e) { throw (Exception)e.getCause(); }
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createDirectory(Path.of(args[0]).resolve("upgrade-backup-"+System.nanoTime()));
        Path files=Files.createDirectories(root.resolve("source/files"));
        Path prefs=Files.createDirectories(root.resolve("source/shared_prefs"));
        String[] names={"cards.dat","cards.dat.bak","cards.dat.new","campus.json","timetable.json","academic.json"};
        for (String name:names) Files.writeString(files.resolve(name),"original "+name);
        Files.createDirectories(files.resolve("nested"));
        Files.write(files.resolve("nested/raw.bin"),new byte[]{0,1,(byte)255,10});
        Files.writeString(prefs.resolve("settings.xml"),"synthetic settings");
        Files.writeString(prefs.resolve("campus-credentials.xml"),"synthetic encrypted value");
        Path destination=root.resolve("v29");
        preserve(files,prefs,destination);
        for (String name:names) check("raw snapshot retains "+name,Files.readString(destination.resolve("files/"+name)).equals("original "+name));
        check("nested binary data retained exactly",java.util.Arrays.equals(Files.readAllBytes(destination.resolve("files/nested/raw.bin")),new byte[]{0,1,(byte)255,10}));
        check("settings and encrypted credential bytes retained",Files.readString(destination.resolve("shared_prefs/settings.xml")).equals("synthetic settings") && Files.readString(destination.resolve("shared_prefs/campus-credentials.xml")).equals("synthetic encrypted value"));
        check("completion marker is created after the copy",Files.isRegularFile(destination.resolve("complete")));
        check("all source files remain untouched",Files.readString(files.resolve("cards.dat.bak")).equals("original cards.dat.bak") && Files.readString(prefs.resolve("settings.xml")).equals("synthetic settings"));
        Files.writeString(files.resolve("cards.dat"),"updated state");
        preserve(files,prefs,destination);
        check("later writes never replace the completed version snapshot",Files.readString(destination.resolve("files/cards.dat")).equals("original cards.dat"));
        Path next=root.resolve("v30"); preserve(files,prefs,next);
        check("a new version retains the prior version snapshot",Files.readString(next.resolve("files/cards.dat")).equals("updated state") && Files.readString(destination.resolve("files/cards.dat")).equals("original cards.dat"));
        Path retry=root.resolve("retry"); Files.createDirectories(retry);
        Files.writeString(retry.resolve("shared_prefs"),"blocked directory");
        boolean failed=false;
        try { preserve(files,prefs,retry); } catch (IOException expected) { failed=true; }
        check("failed backup has no completion marker",failed && !Files.exists(retry.resolve("complete")));
        check("failed backup leaves originals intact",Files.readString(files.resolve("cards.dat")).equals("updated state") && Files.readString(prefs.resolve("settings.xml")).equals("synthetic settings"));
        Files.delete(retry.resolve("shared_prefs")); preserve(files,prefs,retry);
        check("an incomplete backup can retry successfully",Files.isRegularFile(retry.resolve("complete")) && Files.readString(retry.resolve("shared_prefs/settings.xml")).equals("synthetic settings") && Files.readString(retry.resolve("files/cards.dat.bak")).equals("original cards.dat.bak"));
        System.out.println(checks+" checks passed");
    }
}
