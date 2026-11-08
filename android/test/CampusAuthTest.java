package com.nopalou;

/** Authentication timing decisions independent of Android UI or a real account. */
public class CampusAuthTest {
    static int n;
    static void check(String name,boolean ok) { if (!ok) throw new AssertionError(name); n++; System.out.println("PASS "+name); }
    public static void main(String[] args) {
        CampusAuth a=new CampusAuth();
        check("CAS redirects are pending rather than expired",a.observe("redirecting",0)==CampusAuth.Decision.WAIT);
        check("visible central form gets a settling window",a.observe("central_login",100)==CampusAuth.Decision.WAIT);
        check("short transient form never causes another login",a.observe("central_login",1000)==CampusAuth.Decision.WAIT && a.observe("ready",1200)==CampusAuth.Decision.READY);
        check("new form gets its own settling window",a.observe("central_login",6000)==CampusAuth.Decision.WAIT);
        check("only settled central form requires one shared reauthentication",a.observe("central_login",11000)==CampusAuth.Decision.CENTRAL_EXPIRED);
        check("service password page is an unresolved handoff, not another account",a.observe("service_login",12000)==CampusAuth.Decision.SERVICE_ENTRY);
        check("school challenge stays a challenge",a.observe("challenge",900000)==CampusAuth.Decision.CHALLENGE);
        check("explicit SSO buttons and links trigger handoff",a.observe("sso_link",900001)==CampusAuth.Decision.HANDOFF && a.observe("sso_button",900002)==CampusAuth.Decision.HANDOFF);
        a.reset();
        check("reset discards stale expiration timing",a.observe("central_login",1000000)==CampusAuth.Decision.WAIT);
        check("unknown content is not classified as expired credentials",a.observe("unavailable",1000010)==CampusAuth.Decision.UNAVAILABLE);
        System.out.println(n+" checks passed");
    }
}
