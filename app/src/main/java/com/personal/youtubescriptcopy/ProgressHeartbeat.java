package com.personal.youtubescriptcopy;

import java.util.Locale;

/** Pure-Java progress ticker so elapsed-time behavior can be tested without Android time. */
final class ProgressHeartbeat {
    static final long DEFAULT_INTERVAL_MS = 5_000L;

    enum State { IDLE, GEMINI_STARTED, HEARTBEAT_RUNNING, COMPLETED, FAILED, CANCELLED }

    interface Clock { long elapsedRealtime(); }

    interface Scheduler {
        Cancellable schedule(Runnable runnable, long delayMs);
    }

    interface Cancellable { void cancel(); }

    interface Display {
        void show(String text);
        void cancel();
    }

    private final Clock clock;
    private final Scheduler scheduler;
    private final Display display;
    private final long intervalMs;

    private long generation;
    private long startedAt;
    private int attempt = 1;
    private int maxAttempts = 1;
    private State state = State.IDLE;
    private Cancellable scheduled;

    ProgressHeartbeat(Clock clock, Scheduler scheduler, Display display, long intervalMs) {
        this.clock = clock;
        this.scheduler = scheduler;
        this.display = display;
        this.intervalMs = intervalMs;
    }

    synchronized void start(long requestGeneration) {
        stopLocked(State.CANCELLED);
        generation = requestGeneration;
        startedAt = clock.elapsedRealtime();
        attempt = 1;
        maxAttempts = 1;
        state = State.GEMINI_STARTED;
        display.cancel();
        display.show("Gemini 전사 시작…");
        state = State.HEARTBEAT_RUNNING;
        scheduleNextLocked(requestGeneration);
    }

    synchronized void updateAttempt(long requestGeneration, int current, int maximum) {
        if (state != State.HEARTBEAT_RUNNING || generation != requestGeneration) {
            return;
        }
        attempt = Math.max(1, current);
        maxAttempts = Math.max(attempt, maximum);
    }

    synchronized void stop(long requestGeneration, State terminalState) {
        if (state == State.IDLE || generation != requestGeneration) {
            return;
        }
        stopLocked(terminalState);
    }

    synchronized void destroy() {
        stopLocked(State.CANCELLED);
    }

    synchronized State state() { return state; }

    private void scheduleNextLocked(long requestGeneration) {
        scheduled = scheduler.schedule(() -> tick(requestGeneration), intervalMs);
    }

    private void tick(long requestGeneration) {
        synchronized (this) {
            if (state != State.HEARTBEAT_RUNNING || generation != requestGeneration) {
                return;
            }
            long seconds = Math.max(0L, (clock.elapsedRealtime() - startedAt) / 1_000L);
            String text = String.format(Locale.ROOT, "Gemini 전사 중… %d초", seconds);
            if (attempt > 1) {
                text += String.format(Locale.ROOT, " · %d/%d", attempt, maxAttempts);
            }
            display.cancel();
            display.show(text);
            scheduleNextLocked(requestGeneration);
        }
    }

    private void stopLocked(State terminalState) {
        if (scheduled != null) {
            scheduled.cancel();
            scheduled = null;
        }
        if (state != State.IDLE) {
            state = terminalState;
            display.cancel();
        }
        state = State.IDLE;
    }
}
