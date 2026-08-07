package com.personal.youtubescriptcopy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Selects the first valid transcript while keeping failures from ending the race early. */
final class TranscriptRaceCoordinator {
    enum Source { DEFUDDLE, GEMINI }

    enum ResultStatus { SUCCESS, FAILURE, CANCELLED, INCOMPLETE }

    enum RequestState {
        IDLE,
        RUNNING_DEFUDDLE,
        RUNNING_RACE,
        DEFUDDLE_WON,
        GEMINI_WON,
        BOTH_FAILED,
        CANCELLED,
        FINISHED
    }

    enum GeminiStartReason { DELAY_ELAPSED, DEFUDDLE_TERMINAL }

    interface Call {
        Result execute();
        void cancel();
    }

    interface DelayScheduler {
        Cancellable schedule(Runnable runnable, long delayMs);
    }

    interface Cancellable { void cancel(); }

    interface Listener {
        void onStateChanged(RequestState state);
        void onGeminiStarted(GeminiStartReason reason);
        void onCallFinished(Source source, Result result);
        void onWinner(Source source, String transcript);
        void onLoserCancellation(Source source, boolean requested);
        void onBothFailed(Result defuddle, Result gemini);
        void onCancelled();
    }

    static final class Result {
        private final ResultStatus status;
        private final String transcript;
        private final Throwable error;

        private Result(ResultStatus status, String transcript, Throwable error) {
            this.status = status;
            this.transcript = transcript;
            this.error = error;
        }

        static Result success(String transcript) {
            if (transcript == null || transcript.trim().isEmpty()) {
                return incomplete(new IllegalStateException("Transcript is empty"));
            }
            return new Result(ResultStatus.SUCCESS, transcript.trim(), null);
        }

        static Result failure(Throwable error) {
            return new Result(ResultStatus.FAILURE, null, error);
        }

        static Result incomplete(Throwable error) {
            return new Result(ResultStatus.INCOMPLETE, null, error);
        }

        static Result cancelled() {
            return new Result(ResultStatus.CANCELLED, null, null);
        }

        ResultStatus status() { return status; }
        String transcript() { return transcript; }
        Throwable error() { return error; }
        boolean isSuccess() { return status == ResultStatus.SUCCESS; }
        boolean isTerminalFailure() { return status != ResultStatus.SUCCESS; }
    }

    private final Object lock = new Object();
    private final ExecutorService executor;
    private final DelayScheduler scheduler;
    private final long geminiDelayMs;
    private final Listener listener;

    private Call defuddleCall;
    private Call geminiCall;
    private Future<?> defuddleFuture;
    private Future<?> geminiFuture;
    private Cancellable geminiDelayFuture;
    private Result defuddleResult;
    private Result geminiResult;
    private boolean geminiStarted;
    private boolean terminal;
    private RequestState state = RequestState.IDLE;

    TranscriptRaceCoordinator(ExecutorService executor,
                              DelayScheduler scheduler,
                              long geminiDelayMs,
                              Listener listener) {
        this.executor = executor;
        this.scheduler = scheduler;
        this.geminiDelayMs = geminiDelayMs;
        this.listener = listener;
    }

    void start(Call defuddle, Call gemini) {
        synchronized (lock) {
            if (state != RequestState.IDLE) {
                throw new IllegalStateException("Transcript race already started");
            }
            defuddleCall = defuddle;
            geminiCall = gemini;
            transitionLocked(RequestState.RUNNING_DEFUDDLE);
            defuddleFuture = executor.submit(() -> run(Source.DEFUDDLE, defuddle));
            if (gemini != null) {
                geminiDelayFuture = scheduler.schedule(
                        () -> startGemini(GeminiStartReason.DELAY_ELAPSED),
                        geminiDelayMs
                );
            }
        }
    }

    private void run(Source source, Call call) {
        Result result;
        try {
            result = call.execute();
            if (result == null) {
                result = Result.failure(new IllegalStateException("Call returned no result"));
            }
        } catch (Throwable error) {
            result = Result.failure(error);
        }
        handleResult(source, result);
    }

    private void startGemini(GeminiStartReason reason) {
        Call call;
        synchronized (lock) {
            if (terminal || geminiCall == null || geminiStarted) {
                return;
            }
            geminiStarted = true;
            cancelDelayLocked();
            transitionLocked(RequestState.RUNNING_RACE);
            call = geminiCall;
            listener.onGeminiStarted(reason);
            geminiFuture = executor.submit(() -> run(Source.GEMINI, call));
        }
    }

    private void handleResult(Source source, Result result) {
        Source winner = null;
        String transcript = null;
        Call loserCall = null;
        Future<?> loserFuture = null;
        Source loserSource = null;
        boolean startGeminiNow = false;
        boolean bothFailed = false;
        Result defuddleFailure = null;
        Result geminiFailure = null;

        synchronized (lock) {
            if (terminal) {
                return;
            }
            if (source == Source.DEFUDDLE) {
                defuddleResult = result;
            } else {
                geminiResult = result;
            }

            if (result.isSuccess()) {
                terminal = true;
                winner = source;
                transcript = result.transcript();
                cancelDelayLocked();
                if (source == Source.DEFUDDLE) {
                    transitionLocked(RequestState.DEFUDDLE_WON);
                    loserSource = Source.GEMINI;
                    loserCall = geminiCall;
                    loserFuture = geminiFuture;
                } else {
                    transitionLocked(RequestState.GEMINI_WON);
                    loserSource = Source.DEFUDDLE;
                    loserCall = defuddleCall;
                    loserFuture = defuddleFuture;
                }
            } else if (source == Source.DEFUDDLE && geminiCall != null && !geminiStarted) {
                startGeminiNow = true;
            } else if (allAvailableCallsFinishedLocked()) {
                terminal = true;
                cancelDelayLocked();
                transitionLocked(RequestState.BOTH_FAILED);
                bothFailed = true;
                defuddleFailure = defuddleResult;
                geminiFailure = geminiResult;
            }
        }

        listener.onCallFinished(source, result);
        if (startGeminiNow) {
            startGemini(GeminiStartReason.DEFUDDLE_TERMINAL);
        }
        if (winner != null) {
            boolean cancellationRequested = cancelCall(loserCall, loserFuture);
            listener.onLoserCancellation(loserSource, cancellationRequested);
            listener.onWinner(winner, transcript);
            finishTerminalState();
        } else if (bothFailed) {
            listener.onBothFailed(defuddleFailure, geminiFailure);
            finishTerminalState();
        }
    }

    private boolean allAvailableCallsFinishedLocked() {
        if (defuddleResult == null) {
            return false;
        }
        return geminiCall == null || (geminiStarted && geminiResult != null);
    }

    void cancel() {
        Call defuddle;
        Call gemini;
        Future<?> defuddleTask;
        Future<?> geminiTask;
        synchronized (lock) {
            if (terminal || state == RequestState.FINISHED) {
                return;
            }
            terminal = true;
            transitionLocked(RequestState.CANCELLED);
            cancelDelayLocked();
            defuddle = defuddleCall;
            gemini = geminiCall;
            defuddleTask = defuddleFuture;
            geminiTask = geminiFuture;
        }
        cancelCall(defuddle, defuddleTask);
        cancelCall(gemini, geminiTask);
        listener.onCancelled();
        finishTerminalState();
    }

    RequestState state() {
        synchronized (lock) {
            return state;
        }
    }

    private void finishTerminalState() {
        synchronized (lock) {
            transitionLocked(RequestState.FINISHED);
        }
    }

    private void cancelDelayLocked() {
        if (geminiDelayFuture != null) {
            geminiDelayFuture.cancel();
            geminiDelayFuture = null;
        }
    }

    private void transitionLocked(RequestState next) {
        state = next;
        listener.onStateChanged(next);
    }

    private static boolean cancelCall(Call call, Future<?> future) {
        boolean requested = true;
        if (call != null) {
            try {
                call.cancel();
            } catch (RuntimeException ignored) {
                // Losing-call cancellation must never change the winning result.
                requested = false;
            }
        }
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
        return requested;
    }
}
