package com.nopalou;

/** Durable refresh timing; opening a page never creates a second active batch. */
final class CampusAutoPolicy {
    static final long LEASE=30*60_000L, SUCCESS_INTERVAL=6*60*60_000L, RETRY_INTERVAL=10*60_000L, CANCEL_INTERVAL=2*60_000L;
    static boolean due(long now,long started,boolean active,long next,long previousVersion,long version) {
        if (active && now>=started && now-started<LEASE) return false;
        if (previousVersion!=version) return true;
        return next<=0 || now>=next || next-now>SUCCESS_INTERVAL;
    }
    static boolean fullReadAfterLogin(String school,boolean authOnly) { return "nwpu".equals(school) && !authOnly; }
}
