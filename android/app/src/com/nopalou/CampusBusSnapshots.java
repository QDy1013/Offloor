package com.nopalou;

import android.os.SystemClock;
import java.util.LinkedHashMap;
import org.json.JSONArray;

/** Recent read-only results for the current linked account, kept only in memory. */
final class CampusBusSnapshots {
    static final class Snapshot {
        final JSONArray trips;
        final long updated=System.currentTimeMillis();
        final long saved=SystemClock.elapsedRealtime();
        Snapshot(JSONArray trips) { this.trips=trips; }
    }

    private static final long MAX_AGE=10*60_000L;
    private static final LinkedHashMap<String,Snapshot> snapshots=new LinkedHashMap<>();
    private static String currentOwner="";

    private static void useOwner(String owner) {
        if (!owner.equals(currentOwner)) {
            snapshots.clear();
            currentOwner=owner;
        }
    }

    static Snapshot load(String owner,String date) {
        useOwner(owner);
        Snapshot snapshot=snapshots.get(date);
        if (snapshot!=null && SystemClock.elapsedRealtime()-snapshot.saved>MAX_AGE) {
            snapshots.remove(date);
            return null;
        }
        return snapshot;
    }

    static void save(String owner,String date,JSONArray trips) {
        if (owner.isEmpty()) return;
        useOwner(owner);
        snapshots.remove(date);
        snapshots.put(date,new Snapshot(trips));
        if (snapshots.size()>3) snapshots.remove(snapshots.keySet().iterator().next());
    }
}
