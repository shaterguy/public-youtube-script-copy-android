package com.personal.youtubescriptcopy;

/** Decides whether a Gemini response is complete, resumable, or invalid. */
final class GeminiCompletionPolicy {
    static final long MIN_END_TOLERANCE_SECONDS = 30L;
    static final long MAX_END_TOLERANCE_SECONDS = 90L;
    static final double END_TOLERANCE_RATIO = 0.03d;

    enum Decision { COMPLETE, CONTINUE, FAILURE }

    private GeminiCompletionPolicy() {
    }

    static Decision classify(String rawText,
                             String finishReason,
                             long lastTimestampSeconds,
                             long durationSeconds) {
        if (!"STOP".equals(finishReason) && !"MAX_TOKENS".equals(finishReason)) {
            return Decision.FAILURE;
        }

        String cleaned = GeminiTranscriptAssembler.clean(rawText);
        if (cleaned.isEmpty()) {
            return Decision.FAILURE;
        }

        boolean nearVideoEnd = isNearVideoEnd(lastTimestampSeconds, durationSeconds);
        if (GeminiTranscriptAssembler.signalsComplete(rawText)) {
            // The model's explicit marker is advisory. When the video duration is known, reject
            // premature completion markers and continue until the assembled transcript reaches
            // the same duration-aware end tolerance used for ordinary STOP responses.
            return durationSeconds > 0L && !nearVideoEnd
                    ? Decision.CONTINUE : Decision.COMPLETE;
        }
        if (nearVideoEnd) {
            return Decision.COMPLETE;
        }
        if (GeminiTranscriptAssembler.requestsContinuation(rawText)) {
            return Decision.CONTINUE;
        }
        if ("MAX_TOKENS".equals(finishReason)) {
            return Decision.CONTINUE;
        }

        // With a known duration, STOP only means the model stopped naturally. Continue until the
        // assembled transcript reaches the end-of-video tolerance. If duration lookup failed,
        // preserve the previous safe fallback and accept a non-empty STOP response.
        return durationSeconds > 0L ? Decision.CONTINUE : Decision.COMPLETE;
    }

    static long endToleranceSeconds(long durationSeconds) {
        if (durationSeconds <= 0L) {
            return 0L;
        }
        long proportional = (long) Math.ceil(durationSeconds * END_TOLERANCE_RATIO);
        return Math.min(
                MAX_END_TOLERANCE_SECONDS,
                Math.max(MIN_END_TOLERANCE_SECONDS, proportional)
        );
    }

    static boolean isNearVideoEnd(long lastTimestampSeconds, long durationSeconds) {
        if (durationSeconds <= 0L || lastTimestampSeconds < 0L) {
            return false;
        }
        if (lastTimestampSeconds == 0L && durationSeconds > MIN_END_TOLERANCE_SECONDS) {
            return false;
        }
        return lastTimestampSeconds + endToleranceSeconds(durationSeconds) >= durationSeconds;
    }
}
