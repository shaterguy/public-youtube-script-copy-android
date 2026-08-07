package com.personal.youtubescriptcopy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GeminiCompletionPolicyTest {
    @Test
    public void stopNearVideoEndCompletesWithoutCustomMarker() {
        assertEquals(GeminiCompletionPolicy.Decision.COMPLETE,
                GeminiCompletionPolicy.classify(
                        "[00:09:42] 화자 1: 마지막 발화", "STOP", 582L, 600L
                ));
    }

    @Test
    public void continuationMarkerNearVideoEndDoesNotStartAnotherRequest() {
        assertEquals(GeminiCompletionPolicy.Decision.COMPLETE,
                GeminiCompletionPolicy.classify(
                        "[00:09:35] 화자 1: 마지막 발화\n[[CONTINUE_FROM=00:09:35]]",
                        "STOP", 575L, 600L
                ));
    }

    @Test
    public void stopFarFromVideoEndRequestsContinuation() {
        assertEquals(GeminiCompletionPolicy.Decision.CONTINUE,
                GeminiCompletionPolicy.classify(
                        "[00:05:00] 화자 1: 중간 발화", "STOP", 300L, 600L
                ));
    }

    @Test
    public void prematureCompletionMarkerFarFromEndRequestsContinuation() {
        assertEquals(GeminiCompletionPolicy.Decision.CONTINUE,
                GeminiCompletionPolicy.classify(
                        "[00:48:19] 화자 2: 중간 발화\n" +
                                "[[TRANSCRIPT_COMPLETE=00:48:19]]",
                        "STOP", 2_899L, 4_914L
                ));
    }

    @Test
    public void completionMarkerNearVideoEndCompletes() {
        assertEquals(GeminiCompletionPolicy.Decision.COMPLETE,
                GeminiCompletionPolicy.classify(
                        "[01:21:20] 화자 2: 마지막 발화\n" +
                                "[[TRANSCRIPT_COMPLETE=01:21:20]]",
                        "STOP", 4_880L, 4_914L
                ));
    }

    @Test
    public void completionMarkerWithUnknownDurationPreservesFallback() {
        assertEquals(GeminiCompletionPolicy.Decision.COMPLETE,
                GeminiCompletionPolicy.classify(
                        "[00:00:05] 화자 1: 전체 발화\n" +
                                "[[TRANSCRIPT_COMPLETE=00:00:05]]",
                        "STOP", 5L, -1L
                ));
    }

    @Test
    public void markerWithoutTranscriptIsFailure() {
        assertEquals(GeminiCompletionPolicy.Decision.FAILURE,
                GeminiCompletionPolicy.classify(
                        "[[TRANSCRIPT_COMPLETE=00:00:05]]", "STOP", -1L, 600L
                ));
    }

    @Test
    public void continuationMarkerFarFromEndRequestsContinuation() {
        assertEquals(GeminiCompletionPolicy.Decision.CONTINUE,
                GeminiCompletionPolicy.classify(
                        "[00:02:00] 화자 1: 발화\n[[CONTINUE_FROM=00:02:00]]",
                        "STOP", 120L, 600L
                ));
    }

    @Test
    public void maxTokensNearEndCompletes() {
        assertEquals(GeminiCompletionPolicy.Decision.COMPLETE,
                GeminiCompletionPolicy.classify(
                        "[00:09:40] 화자 1: 마지막 발화", "MAX_TOKENS", 580L, 600L
                ));
    }

    @Test
    public void maxTokensFarFromEndRequestsContinuation() {
        assertEquals(GeminiCompletionPolicy.Decision.CONTINUE,
                GeminiCompletionPolicy.classify(
                        "[00:02:00] 화자 1: 발화", "MAX_TOKENS", 120L, 600L
                ));
    }

    @Test
    public void unknownDurationPreservesNonEmptyStopFallback() {
        assertEquals(GeminiCompletionPolicy.Decision.COMPLETE,
                GeminiCompletionPolicy.classify(
                        "[00:00:00] 화자 1: 전체 발화", "STOP", 0L, -1L
                ));
    }

    @Test
    public void emptyStopIsFailure() {
        assertEquals(GeminiCompletionPolicy.Decision.FAILURE,
                GeminiCompletionPolicy.classify("", "STOP", -1L, 600L));
    }

    @Test
    public void unsupportedFinishReasonIsFailure() {
        assertEquals(GeminiCompletionPolicy.Decision.FAILURE,
                GeminiCompletionPolicy.classify(
                        "[00:09:50] 화자 1: 발화", "SAFETY", 590L, 600L
                ));
    }

    @Test
    public void safetyFinishReasonCannotBeOverriddenByCompletionMarker() {
        assertEquals(GeminiCompletionPolicy.Decision.FAILURE,
                GeminiCompletionPolicy.classify(
                        "[00:00:00] 화자 1: 발화\n[[TRANSCRIPT_COMPLETE=00:00:05]]",
                        "SAFETY", 5L, 600L
                ));
    }

    @Test
    public void toleranceScalesFromThirtyToNinetySeconds() {
        assertEquals(30L, GeminiCompletionPolicy.endToleranceSeconds(600L));
        assertEquals(54L, GeminiCompletionPolicy.endToleranceSeconds(1_800L));
        assertEquals(90L, GeminiCompletionPolicy.endToleranceSeconds(10_800L));
    }

    @Test
    public void zeroTimestampOnlyCompletesForShortVideos() {
        assertTrue(GeminiCompletionPolicy.isNearVideoEnd(0L, 20L));
        assertFalse(GeminiCompletionPolicy.isNearVideoEnd(0L, 600L));
    }
}
