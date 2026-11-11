package com.nopalou;

import java.util.ArrayDeque;
import java.util.Queue;

/** Deterministic checks without an Android device or real notifications. */
public final class NotifReconnectTest {
    private static int checks;
    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }
    static final class Host implements NotifReconnect.Host {
        boolean access = true, connected, failRequest;
        int requests, changes;
        final Queue<Runnable> tasks = new ArrayDeque<>();
        public boolean accessEnabled() { return access; }
        public boolean connected() { return connected; }
        public void rebind() { requests++; if (failRequest) throw new SecurityException(); }
        public void later(Runnable task, long delayMs) { tasks.add(task); }
        public void changed() { changes++; }
        public void log(String kind, String detail) { }
        void next() { tasks.remove().run(); }
        void drain() { while (!tasks.isEmpty()) next(); }
    }
    public static void main(String[] args) {
        Host h = new Host(); NotifReconnect r = new NotifReconnect(h);
        r.start(false);
        check("initial request waits for real connection", h.requests == 1 && r.running());
        r.start(true); r.start(false);
        check("duplicate starts do not multiply retries", h.requests == 1 && h.tasks.size() == 1);
        h.next();
        check("one bounded retry without resetting listener", h.requests == 2 && r.running());
        h.next();
        check("timeout stops misleading connecting state", r.state() == NotifReconnect.State.FAILED && !r.running());
        r.start(false);
        check("foreground refresh does not restart failed operation", h.requests == 2);
        r.start(true);
        check("explicit retry allowed after failure", h.requests == 3 && r.running());
        h.connected = true; r.connected(); h.drain();
        check("connection callback cancels pending work", r.state() == NotifReconnect.State.IDLE && h.requests == 3);
        r.start(false);
        check("healthy listener is never rebound", h.requests == 3 && h.tasks.isEmpty());

        h = new Host(); r = new NotifReconnect(h); h.access = false; r.start(false);
        check("no permission means no bind", h.requests == 0 && r.state() == NotifReconnect.State.NEEDS_ACCESS);
        h.access = true; r.start(false); h.access = false; h.drain();
        check("revoked access cancels retries", h.requests == 1 && !r.running());
        h.access = true; r.start(false);
        check("new authorization resumes recovery", h.requests == 2 && r.running());

        h = new Host(); r = new NotifReconnect(h); r.start(false); h.connected = true; h.drain();
        check("platform connection skips extra request", h.requests == 1 && !r.running());
        h.connected = false; r.start(false);
        check("later disconnect starts a fresh operation", h.requests == 2 && r.running());

        h = new Host(); r = new NotifReconnect(h); h.failRequest = true; r.start(false);
        check("bind errors become visible failure", r.state() == NotifReconnect.State.FAILED && h.tasks.isEmpty());
        h = new Host(); r = new NotifReconnect(h); r.start(false); h.failRequest = true; h.drain();
        check("retry exception ends operation", h.requests == 2 && r.state() == NotifReconnect.State.FAILED);

        h = new Host(); r = new NotifReconnect(h); r.start(false); r.connected(); r.start(false);
        h.next();
        check("stale callback cannot retry a new operation", h.requests == 2 && r.running());
        h.connected = true; r.connected(); h.drain();
        check("connection cancels all old callbacks", h.requests == 2 && r.state() == NotifReconnect.State.IDLE);

        h = new Host(); r = new NotifReconnect(h); r.start(false); h.drain();
        h.connected = true; r.connected();
        check("late connection after timeout still succeeds", r.state() == NotifReconnect.State.IDLE && !r.running());
        h.connected = false; r.start(false); h.next(); h.access = false; h.drain();
        check("revoked permission before deadline stops recovery", r.state() == NotifReconnect.State.NEEDS_ACCESS);
        System.out.println(checks + " reconnect checks passed");
    }
}
