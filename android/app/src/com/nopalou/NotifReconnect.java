package com.nopalou;

/** Bounded notification recovery; requests are not proof of a connected listener. */
final class NotifReconnect {
    enum State { IDLE, CONNECTING, NEEDS_ACCESS, FAILED }
    interface Host {
        boolean accessEnabled();
        boolean connected();
        void rebind();
        void later(Runnable task, long delayMs);
        void changed();
        void log(String kind, String detail);
    }
    private final Host host;
    private State state = State.IDLE;
    private int generation;

    NotifReconnect(Host host) { this.host = host; }
    State state() { return state; }
    boolean running() { return state == State.CONNECTING; }

    void start(boolean manual) {
        if (host.connected()) { connected(); return; }
        if (!host.accessEnabled()) { finish(State.NEEDS_ACCESS); return; }
        if (running() || (state == State.FAILED && !manual)) return;
        int run = ++generation;
        state = State.CONNECTING;
        host.changed();
        if (!request()) return;
        host.later(() -> {
            if (!pending(run)) return;
            // Retry without disabling or unbinding the service; a slow system bind may still be in flight.
            if (request()) host.later(() -> {
                if (!pending(run)) return;
                host.log("重连", "通知读取恢复超时，请在系统通知使用权中关闭再开启 Offloor");
                finish(State.FAILED);
            }, 8000L);
        }, 4000L);
    }

    private boolean request() {
        try {
            host.rebind();
            host.log("重连", "已请求绑定通知服务，等待系统连接确认");
            return true;
        } catch (RuntimeException e) {
            host.log("出错", "通知服务重连：" + e.getClass().getSimpleName());
            finish(State.FAILED);
            return false;
        }
    }

    private boolean pending(int run) {
        if (generation != run || !running()) return false;
        if (host.connected()) { connected(); return false; }
        if (!host.accessEnabled()) { finish(State.NEEDS_ACCESS); return false; }
        return true;
    }

    void connected() { finish(State.IDLE); }
    private void finish(State next) {
        generation++;
        if (state == next) return;
        state = next;
        host.changed();
    }
}
