package com.nopalou;

/** Distinguishes a transient CAS handoff from a settled, expired shared login. */
final class CampusAuth {
    static final long CENTRAL_FORM_GRACE = 5_000;
    private long centralFormSince = -1;
    enum Decision { READY, WAIT, CHALLENGE, HANDOFF, SERVICE_ENTRY, CENTRAL_EXPIRED, UNAVAILABLE }
    Decision observe(String phase,long now) {
        if ("central_login".equals(phase)) {
            if (centralFormSince<0) centralFormSince=now;
            return now-centralFormSince>=CENTRAL_FORM_GRACE?Decision.CENTRAL_EXPIRED:Decision.WAIT;
        }
        centralFormSince=-1;
        switch (phase) {
            case "redirecting": return Decision.WAIT;
            case "challenge": return Decision.CHALLENGE;
            case "sso_link": case "sso_button": return Decision.HANDOFF;
            case "service_login": return Decision.SERVICE_ENTRY;
            case "ready": return Decision.READY;
            default: return Decision.UNAVAILABLE;
        }
    }
    void reset() { centralFormSince=-1; }
}
