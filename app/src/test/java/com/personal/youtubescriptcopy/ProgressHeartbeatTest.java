package com.personal.youtubescriptcopy;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProgressHeartbeatTest {
    @Test
    public void doesNotScheduleBeforeGeminiStarts() {
        Fixture fixture = new Fixture();
        assertEquals(0, fixture.scheduler.pending());
        assertTrue(fixture.display.events.isEmpty());
    }

    @Test
    public void startAndHeartbeatUseElapsedTimeWithoutRealWaiting() {
        Fixture fixture = new Fixture();
        fixture.heartbeat.start(7L);
        assertEquals("show:Gemini 전사 시작…", fixture.display.last());

        fixture.clock.now = 5_000L;
        fixture.scheduler.runNext();
        assertEquals("show:Gemini 전사 중… 5초", fixture.display.last());

        fixture.clock.now = 10_000L;
        fixture.scheduler.runNext();
        assertEquals("show:Gemini 전사 중… 10초", fixture.display.last());
    }

    @Test
    public void continuationAttemptDoesNotResetElapsedTime() {
        Fixture fixture = new Fixture();
        fixture.heartbeat.start(9L);
        fixture.clock.now = 45_000L;
        fixture.heartbeat.updateAttempt(9L, 2, 4);
        fixture.scheduler.runNext();

        assertEquals("show:Gemini 전사 중… 45초 · 2/4", fixture.display.last());
    }

    @Test
    public void eachUpdateCancelsPreviousToastBeforeShowingNext() {
        Fixture fixture = new Fixture();
        fixture.heartbeat.start(3L);
        fixture.clock.now = 5_000L;
        fixture.scheduler.runNext();

        int size = fixture.display.events.size();
        assertEquals("cancel", fixture.display.events.get(size - 2));
        assertEquals("show:Gemini 전사 중… 5초", fixture.display.events.get(size - 1));
    }

    @Test
    public void completionStopsPendingUpdatesAndCancelsToast() {
        Fixture fixture = new Fixture();
        fixture.heartbeat.start(4L);
        fixture.heartbeat.stop(4L, ProgressHeartbeat.State.COMPLETED);
        int eventsAfterStop = fixture.display.events.size();

        fixture.clock.now = 10_000L;
        fixture.scheduler.runAll();

        assertEquals(eventsAfterStop, fixture.display.events.size());
        assertEquals(ProgressHeartbeat.State.IDLE, fixture.heartbeat.state());
    }

    @Test
    public void failureAndCancellationStopPendingUpdates() {
        for (ProgressHeartbeat.State terminal : new ProgressHeartbeat.State[] {
                ProgressHeartbeat.State.FAILED, ProgressHeartbeat.State.CANCELLED
        }) {
            Fixture fixture = new Fixture();
            fixture.heartbeat.start(4L);
            fixture.heartbeat.stop(4L, terminal);
            int eventsAfterStop = fixture.display.events.size();
            fixture.clock.now = 10_000L;
            fixture.scheduler.runAll();
            assertEquals(eventsAfterStop, fixture.display.events.size());
            assertEquals(ProgressHeartbeat.State.IDLE, fixture.heartbeat.state());
        }
    }

    @Test
    public void destroyPreventsQueuedHeartbeatFromDisplaying() {
        Fixture fixture = new Fixture();
        fixture.heartbeat.start(4L);
        fixture.heartbeat.destroy();
        int eventsAfterDestroy = fixture.display.events.size();
        fixture.clock.now = 10_000L;
        fixture.scheduler.runAll();
        assertEquals(eventsAfterDestroy, fixture.display.events.size());
    }

    @Test
    public void oldGenerationCannotUpdateNewRequest() {
        Fixture fixture = new Fixture();
        fixture.heartbeat.start(1L);
        fixture.heartbeat.stop(1L, ProgressHeartbeat.State.CANCELLED);
        fixture.heartbeat.start(2L);
        fixture.heartbeat.updateAttempt(1L, 4, 4);
        fixture.clock.now = 5_000L;
        fixture.scheduler.runAll();

        assertFalse(fixture.display.last().contains("4/4"));
        assertTrue(fixture.display.last().contains("5초"));
    }

    private static final class Fixture {
        private final FakeClock clock = new FakeClock();
        private final ManualScheduler scheduler = new ManualScheduler();
        private final RecordingDisplay display = new RecordingDisplay();
        private final ProgressHeartbeat heartbeat = new ProgressHeartbeat(
                clock, scheduler, display, 5_000L
        );
    }

    private static final class FakeClock implements ProgressHeartbeat.Clock {
        private long now;
        @Override public long elapsedRealtime() { return now; }
    }

    private static final class ManualScheduler implements ProgressHeartbeat.Scheduler {
        private final Queue<Scheduled> queue = new ArrayDeque<>();

        @Override
        public ProgressHeartbeat.Cancellable schedule(Runnable runnable, long delayMs) {
            Scheduled scheduled = new Scheduled(runnable);
            queue.add(scheduled);
            return () -> scheduled.cancelled = true;
        }

        int pending() { return queue.size(); }

        void runNext() {
            Scheduled scheduled = queue.poll();
            if (scheduled != null && !scheduled.cancelled) {
                scheduled.runnable.run();
            }
        }

        void runAll() {
            int initial = queue.size();
            for (int index = 0; index < initial; index++) {
                runNext();
            }
        }

        private static final class Scheduled {
            private final Runnable runnable;
            private boolean cancelled;
            private Scheduled(Runnable runnable) { this.runnable = runnable; }
        }
    }

    private static final class RecordingDisplay implements ProgressHeartbeat.Display {
        private final List<String> events = new ArrayList<>();
        @Override public void show(String text) { events.add("show:" + text); }
        @Override public void cancel() { events.add("cancel"); }
        String last() { return events.get(events.size() - 1); }
    }
}
