package com.personal.youtubescriptcopy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

/** Main-thread-only Toast adapter for the pure progress heartbeat. */
final class GeminiProgressToastController {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ProgressHeartbeat heartbeat;
    private final AtomicBoolean destroyed = new AtomicBoolean();

    GeminiProgressToastController(Context context) {
        Context appContext = context.getApplicationContext();
        ToastDisplay display = new ToastDisplay(appContext);
        heartbeat = new ProgressHeartbeat(
                SystemClock::elapsedRealtime,
                (runnable, delayMs) -> {
                    handler.postDelayed(runnable, delayMs);
                    return () -> handler.removeCallbacks(runnable);
                },
                display,
                ProgressHeartbeat.DEFAULT_INTERVAL_MS
        );
    }

    void start(long requestGeneration) {
        handler.post(() -> {
            if (!destroyed.get()) {
                heartbeat.start(requestGeneration);
            }
        });
    }

    void updateAttempt(long requestGeneration, int attempt, int maximum) {
        handler.post(() -> {
            if (!destroyed.get()) {
                heartbeat.updateAttempt(requestGeneration, attempt, maximum);
            }
        });
    }

    void stop(long requestGeneration, ProgressHeartbeat.State terminalState) {
        handler.post(() -> {
            if (!destroyed.get()) {
                heartbeat.stop(requestGeneration, terminalState);
            }
        });
    }

    void destroy() {
        destroyed.set(true);
        handler.removeCallbacksAndMessages(null);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            heartbeat.destroy();
        } else {
            handler.post(heartbeat::destroy);
        }
    }

    private static final class ToastDisplay implements ProgressHeartbeat.Display {
        private final Context context;
        private Toast current;

        private ToastDisplay(Context context) {
            this.context = context;
        }

        @Override
        public void show(String text) {
            cancel();
            current = Toast.makeText(context, text, Toast.LENGTH_SHORT);
            current.show();
        }

        @Override
        public void cancel() {
            if (current != null) {
                current.cancel();
                current = null;
            }
        }
    }
}
