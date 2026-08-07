package com.personal.youtubescriptcopy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TranscriptRaceCoordinatorTest {
    private ExecutorService executor;
    private ManualDelayScheduler scheduler;
    private RecordingListener listener;

    @Before
    public void setUp() {
        executor = Executors.newFixedThreadPool(4);
        scheduler = new ManualDelayScheduler();
        listener = new RecordingListener();
    }

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    @Test
    public void fastDefuddleSuccessNeverStartsGemini() throws Exception {
        FakeCall defuddle = FakeCall.ready(success("defuddle"));
        FakeCall gemini = FakeCall.ready(success("gemini"));
        coordinator(defuddle, gemini).start(defuddle, gemini);

        listener.awaitWinner();
        scheduler.fire();

        assertEquals(TranscriptRaceCoordinator.Source.DEFUDDLE, listener.winner);
        assertEquals(1L, gemini.started.getCount());
        assertEquals(1, listener.winnerCount.get());
    }

    @Test
    public void fastDefuddleFailureStartsGeminiWithoutWaitingForDelay() throws Exception {
        FakeCall defuddle = FakeCall.ready(failure());
        FakeCall gemini = FakeCall.blocked(success("gemini"));
        coordinator(defuddle, gemini).start(defuddle, gemini);

        assertTrue(listener.geminiStarted.await(1, TimeUnit.SECONDS));
        assertEquals(TranscriptRaceCoordinator.GeminiStartReason.DEFUDDLE_TERMINAL,
                listener.geminiReason);
        gemini.release();
        listener.awaitWinner();
        assertEquals(TranscriptRaceCoordinator.Source.GEMINI, listener.winner);
    }

    @Test
    public void delayStartsGeminiWhileDefuddleKeepsRunning() throws Exception {
        FakeCall defuddle = FakeCall.blocked(success("defuddle"));
        FakeCall gemini = FakeCall.blocked(success("gemini"));
        coordinator(defuddle, gemini).start(defuddle, gemini);
        assertTrue(defuddle.started.await(1, TimeUnit.SECONDS));

        scheduler.fire();

        assertTrue(listener.geminiStarted.await(1, TimeUnit.SECONDS));
        assertEquals(TranscriptRaceCoordinator.GeminiStartReason.DELAY_ELAPSED,
                listener.geminiReason);
        gemini.release();
        listener.awaitWinner();
        assertEquals(TranscriptRaceCoordinator.Source.GEMINI, listener.winner);
        assertTrue(defuddle.cancelled);
    }

    @Test
    public void defuddleWinsRaceAndCancelsGemini() throws Exception {
        FakeCall defuddle = FakeCall.blocked(success("defuddle"));
        FakeCall gemini = FakeCall.blocked(success("gemini"));
        coordinator(defuddle, gemini).start(defuddle, gemini);
        scheduler.fire();
        assertTrue(gemini.started.await(1, TimeUnit.SECONDS));

        defuddle.release();
        listener.awaitWinner();

        assertEquals(TranscriptRaceCoordinator.Source.DEFUDDLE, listener.winner);
        assertTrue(gemini.cancelled);
    }

    @Test
    public void oneFailureWaitsForOtherSuccess() throws Exception {
        FakeCall defuddle = FakeCall.blocked(failure());
        FakeCall gemini = FakeCall.blocked(success("gemini"));
        coordinator(defuddle, gemini).start(defuddle, gemini);
        scheduler.fire();
        defuddle.release();
        assertTrue(listener.defuddleFinished.await(1, TimeUnit.SECONDS));
        assertEquals(1L, listener.failed.getCount());
        gemini.release();
        listener.awaitWinner();
        assertEquals(TranscriptRaceCoordinator.Source.GEMINI, listener.winner);
    }

    @Test
    public void bothFailuresAreRequiredForFinalFailure() throws Exception {
        FakeCall defuddle = FakeCall.blocked(failure());
        FakeCall gemini = FakeCall.blocked(
                TranscriptRaceCoordinator.Result.incomplete(new IOException("incomplete"))
        );
        coordinator(defuddle, gemini).start(defuddle, gemini);
        scheduler.fire();
        defuddle.release();
        assertTrue(listener.defuddleFinished.await(1, TimeUnit.SECONDS));
        assertEquals(1L, listener.failed.getCount());
        gemini.release();
        assertTrue(listener.failed.await(1, TimeUnit.SECONDS));
        assertEquals(0, listener.winnerCount.get());
        assertEquals(TranscriptRaceCoordinator.ResultStatus.FAILURE,
                listener.defuddleFailure.status());
        assertEquals(TranscriptRaceCoordinator.ResultStatus.INCOMPLETE,
                listener.geminiFailure.status());
    }

    @Test
    public void simultaneousSuccessCompletesOnlyOnceAndLateCallbackIsIgnored() throws Exception {
        FakeCall defuddle = FakeCall.blocked(success("defuddle"));
        FakeCall gemini = FakeCall.blocked(success("gemini"));
        coordinator(defuddle, gemini).start(defuddle, gemini);
        scheduler.fire();
        assertTrue(gemini.started.await(1, TimeUnit.SECONDS));

        defuddle.release();
        gemini.release();
        listener.awaitWinner();

        assertEquals(1, listener.winnerCount.get());
        assertNull(listener.defuddleFailure);
        assertNull(listener.geminiFailure);
    }

    @Test
    public void cancellationStopsDelayAndBothCallsWithoutCompleting() throws Exception {
        FakeCall defuddle = FakeCall.blocked(success("defuddle"));
        FakeCall gemini = FakeCall.blocked(success("gemini"));
        TranscriptRaceCoordinator coordinator = coordinator(defuddle, gemini);
        coordinator.start(defuddle, gemini);
        assertTrue(defuddle.started.await(1, TimeUnit.SECONDS));

        coordinator.cancel();
        scheduler.fire();

        assertTrue(defuddle.cancelled);
        assertTrue(gemini.cancelled);
        assertEquals(1L, gemini.started.getCount());
        assertEquals(1, listener.cancelledCount.get());
        assertEquals(0, listener.winnerCount.get());
    }

    @Test
    public void missingGeminiPreservesDefuddleOnlyFailure() throws Exception {
        FakeCall defuddle = FakeCall.ready(failure());
        TranscriptRaceCoordinator coordinator = coordinator(defuddle, null);
        coordinator.start(defuddle, null);

        assertTrue(listener.failed.await(1, TimeUnit.SECONDS));
        assertEquals(1L, listener.geminiStarted.getCount());
        assertNull(listener.geminiFailure);
    }

    private TranscriptRaceCoordinator coordinator(FakeCall defuddle, FakeCall gemini) {
        return new TranscriptRaceCoordinator(executor, scheduler, 2_000L, listener);
    }

    private static TranscriptRaceCoordinator.Result success(String text) {
        return TranscriptRaceCoordinator.Result.success(text);
    }

    private static TranscriptRaceCoordinator.Result failure() {
        return TranscriptRaceCoordinator.Result.failure(new IOException("failed"));
    }

    private static final class FakeCall implements TranscriptRaceCoordinator.Call {
        private final TranscriptRaceCoordinator.Result result;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release;
        private volatile boolean cancelled;

        private FakeCall(TranscriptRaceCoordinator.Result result, boolean blocked) {
            this.result = result;
            release = new CountDownLatch(blocked ? 1 : 0);
        }

        static FakeCall ready(TranscriptRaceCoordinator.Result result) {
            return new FakeCall(result, false);
        }

        static FakeCall blocked(TranscriptRaceCoordinator.Result result) {
            return new FakeCall(result, true);
        }

        void release() {
            release.countDown();
        }

        @Override
        public TranscriptRaceCoordinator.Result execute() {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return TranscriptRaceCoordinator.Result.cancelled();
            }
            return cancelled ? TranscriptRaceCoordinator.Result.cancelled() : result;
        }

        @Override
        public void cancel() {
            cancelled = true;
            release.countDown();
        }
    }

    private static final class ManualDelayScheduler
            implements TranscriptRaceCoordinator.DelayScheduler {
        private Runnable runnable;
        private boolean cancelled;

        @Override
        public TranscriptRaceCoordinator.Cancellable schedule(Runnable value, long delayMs) {
            runnable = value;
            cancelled = false;
            return () -> cancelled = true;
        }

        void fire() {
            if (!cancelled && runnable != null) {
                runnable.run();
            }
        }
    }

    private static final class RecordingListener implements TranscriptRaceCoordinator.Listener {
        private final CountDownLatch geminiStarted = new CountDownLatch(1);
        private final CountDownLatch winnerLatch = new CountDownLatch(1);
        private final CountDownLatch failed = new CountDownLatch(1);
        private final CountDownLatch defuddleFinished = new CountDownLatch(1);
        private final AtomicInteger winnerCount = new AtomicInteger();
        private final AtomicInteger cancelledCount = new AtomicInteger();
        private final List<TranscriptRaceCoordinator.RequestState> states = new ArrayList<>();
        private volatile TranscriptRaceCoordinator.Source winner;
        private volatile TranscriptRaceCoordinator.GeminiStartReason geminiReason;
        private volatile TranscriptRaceCoordinator.Result defuddleFailure;
        private volatile TranscriptRaceCoordinator.Result geminiFailure;

        @Override
        public synchronized void onStateChanged(TranscriptRaceCoordinator.RequestState state) {
            states.add(state);
        }

        @Override
        public void onGeminiStarted(TranscriptRaceCoordinator.GeminiStartReason reason) {
            geminiReason = reason;
            geminiStarted.countDown();
        }

        @Override
        public void onCallFinished(
                TranscriptRaceCoordinator.Source source,
                TranscriptRaceCoordinator.Result result) {
            if (source == TranscriptRaceCoordinator.Source.DEFUDDLE) {
                defuddleFinished.countDown();
            }
        }

        @Override
        public void onWinner(TranscriptRaceCoordinator.Source source, String transcript) {
            winner = source;
            winnerCount.incrementAndGet();
            winnerLatch.countDown();
        }

        @Override
        public void onLoserCancellation(
                TranscriptRaceCoordinator.Source source, boolean requested) { }

        @Override
        public void onBothFailed(TranscriptRaceCoordinator.Result defuddle,
                                 TranscriptRaceCoordinator.Result gemini) {
            defuddleFailure = defuddle;
            geminiFailure = gemini;
            failed.countDown();
        }

        @Override
        public void onCancelled() {
            cancelledCount.incrementAndGet();
        }

        void awaitWinner() throws InterruptedException {
            assertTrue(winnerLatch.await(1, TimeUnit.SECONDS));
        }
    }
}
