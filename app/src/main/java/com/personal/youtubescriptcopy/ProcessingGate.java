package com.personal.youtubescriptcopy;

import android.os.SystemClock;

/** Process-local guard against duplicate shares and duplicate ChatGPT launches. */
final class ProcessingGate {
    private static final long DUPLICATE_WINDOW_MS = 4_000L;
    private static String activeVideoId;
    private static String lastCompletedVideoId;
    private static long lastCompletedAt;

    private ProcessingGate() {
    }

    static synchronized StartResult tryStart(String videoId) {
        long now = SystemClock.elapsedRealtime();
        if (activeVideoId != null) {
            return activeVideoId.equals(videoId) ? StartResult.SAME_REQUEST : StartResult.BUSY;
        }
        if (videoId.equals(lastCompletedVideoId) && now - lastCompletedAt < DUPLICATE_WINDOW_MS) {
            return StartResult.SAME_REQUEST;
        }
        activeVideoId = videoId;
        return StartResult.STARTED;
    }

    static synchronized void finish(String videoId, boolean completed) {
        if (!videoId.equals(activeVideoId)) {
            return;
        }
        activeVideoId = null;
        if (completed) {
            lastCompletedVideoId = videoId;
            lastCompletedAt = SystemClock.elapsedRealtime();
        }
    }

    enum StartResult {
        STARTED,
        SAME_REQUEST,
        BUSY
    }
}
