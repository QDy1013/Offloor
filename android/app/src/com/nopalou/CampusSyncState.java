package com.nopalou;

/** Progress represents processed work, never a claim that every service succeeded. */
final class CampusSyncState {
    static int percent(int done,int total,boolean completed) {
        if (completed) return 100;
        if (total<=0) return 0;
        return (int)Math.min(99,Math.max(0,100L*done/total));
    }
    static boolean visible(boolean active,boolean pending,long finished,long now) {
        return active || pending || (finished>0 && now>=finished && now-finished<6000);
    }
}
