package io.github.carefreebit.jvmd.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One thread from a HotSpot thread dump. */
public final class ThreadInfo {

    private final String name;
    private final long id;
    private final boolean daemon;
    private final int priority;
    private final String osState;
    private final long cpuMillis;
    private final double elapsedSec;
    private final String tid;
    private final String nid;

    private String state;
    private final List<StackFrame> frames = new ArrayList<>();
    private LockRef waitingToLock;
    private final List<LockRef> locked = new ArrayList<>();
    private LockRef waitingOn;
    private LockRef parking;

    public ThreadInfo(String name, long id, boolean daemon, int priority, String osState,
                      long cpuMillis, double elapsedSec, String tid, String nid, String state) {
        this.name = name;
        this.id = id;
        this.daemon = daemon;
        this.priority = priority;
        this.osState = osState == null ? "" : osState;
        this.cpuMillis = cpuMillis;
        this.elapsedSec = elapsedSec;
        this.tid = tid;
        this.nid = nid;
        this.state = state == null || state.isEmpty() ? mapOsState(this.osState) : state;
    }

    private static String mapOsState(String osState) {
        String s = osState.toLowerCase();
        if (s.contains("runnable") || s.contains("running")) {
            return "RUNNABLE";
        }
        if (s.contains("waiting") || s.contains("object wait") || s.contains("sleeping")
                || s.contains("parking") || s.contains("parker")) {
            return "WAITING";
        }
        if (s.contains("blocked")) {
            return "BLOCKED";
        }
        return "UNKNOWN";
    }

    public String name() {
        return name;
    }

    public long id() {
        return id;
    }

    public boolean daemon() {
        return daemon;
    }

    public int priority() {
        return priority;
    }

    public String osState() {
        return osState;
    }

    public long cpuMillis() {
        return cpuMillis;
    }

    public double elapsedSec() {
        return elapsedSec;
    }

    public String tid() {
        return tid;
    }

    public String nid() {
        return nid;
    }

    public String state() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public List<StackFrame> frames() {
        return Collections.unmodifiableList(frames);
    }

    public void addFrame(StackFrame frame) {
        frames.add(frame);
    }

    public LockRef waitingToLock() {
        return waitingToLock;
    }

    public void setWaitingToLock(LockRef ref) {
        this.waitingToLock = ref;
    }

    public List<LockRef> locked() {
        return Collections.unmodifiableList(locked);
    }

    public void addLocked(LockRef ref) {
        locked.add(ref);
    }

    public LockRef waitingOn() {
        return waitingOn;
    }

    public void setWaitingOn(LockRef ref) {
        this.waitingOn = ref;
    }

    public LockRef parking() {
        return parking;
    }

    public void setParking(LockRef ref) {
        this.parking = ref;
    }

    public StackFrame topFrame() {
        return frames.isEmpty() ? null : frames.get(0);
    }

    public String topFrameDisplay() {
        StackFrame f = topFrame();
        return f == null ? "(no stack)" : f.display();
    }

    /** First {@code n} frame display strings, joined by {@code <-}. */
    public String fingerprint(int n) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(n, frames.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(" <- ");
            }
            sb.append(frames.get(i).display());
        }
        return sb.toString();
    }

    /** True when the thread is sitting in a known idle primitive rather than doing work. */
    public boolean isIdle() {
        StackFrame top = topFrame();
        if (top == null) {
            return true;
        }
        String key = (top.className() + "." + top.methodName()).toLowerCase();
        String file = top.fileName() == null ? "" : top.fileName().toLowerCase();
        return key.contains("unsafe.park")
                || key.contains("locksupport.park")
                || key.contains("object.wait")
                || key.contains("thread.sleep")
                || key.contains("thread.yield")
                || key.contains("epollwait")
                || key.contains("epoll.wait")
                || key.contains("poll0")
                || key.contains("poll.")
                || key.contains("select0")
                || key.contains("accept0")
                || key.contains("socketinputstream.read")
                || key.contains("socketdispatcher")
                || key.contains("nio.selector")
                || file.contains("epollarraywrapper")
                || key.contains("futex")
                || key.contains("condition.await")
                || key.contains("waitforreferencependinglist");
    }

    @Override
    public String toString() {
        return "\"" + name + "\" #" + id + " " + state;
    }
}
