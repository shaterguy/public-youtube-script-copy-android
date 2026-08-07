package com.personal.youtubescriptcopy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GeminiTranscriptAssemblerTest {
    @Test
    public void removesContinuationMarkerAndCodeFence() {
        assertEquals("[00:00:00] 화자 1: 첫 문장", GeminiTranscriptAssembler.clean(
                "```text\n[00:00:00] 화자 1: 첫 문장\n" +
                        "[[CONTINUE_FROM=00:00:05]]\n```"
        ));
    }

    @Test
    public void recognizesAndRemovesCompletionMarker() {
        String response = "[00:00:00] 화자 1: 전체 발화\n" +
                "[[TRANSCRIPT_COMPLETE=00:00:05]]";

        org.junit.Assert.assertTrue(GeminiTranscriptAssembler.signalsComplete(response));
        assertEquals("[00:00:00] 화자 1: 전체 발화", GeminiTranscriptAssembler.clean(response));
    }

    @Test
    public void joinsContinuationWithoutDuplicatingMatchingLines() {
        GeminiTranscriptAssembler assembler = new GeminiTranscriptAssembler();
        assembler.append("[00:00:00] 화자 1: 첫 문장\n[00:00:04] 화자 2: 둘째 문장");
        assembler.append("[00:00:04] 화자 2: 둘째 문장\n[00:00:08] 화자 1: 마지막 문장");

        assertEquals("[00:00:00] 화자 1: 첫 문장\n" +
                        "[00:00:04] 화자 2: 둘째 문장\n" +
                        "[00:00:08] 화자 1: 마지막 문장",
                assembler.transcript());
        assertEquals("00:00:08", assembler.lastTimestamp());
    }

    @Test
    public void dropsRetimestampedHistoricalPrefixFromContinuation() {
        GeminiTranscriptAssembler assembler = new GeminiTranscriptAssembler();
        assembler.append("[00:19:33] 화자 3: 반복 A\n" +
                "[00:20:25] 화자 3: 반복 B\n" +
                "[00:21:23] 화자 3: 반복 C\n" +
                "[00:44:40] 화자 1: 현재 질문");
        assembler.append("[00:44:54] 화자 3: 반복 A\n" +
                "[00:45:49] 화자 3: 반복 B\n" +
                "[00:46:47] 화자 3: 반복 C\n" +
                "[00:47:37] 화자 2: 새 발화");

        assertEquals("[00:19:33] 화자 3: 반복 A\n" +
                        "[00:20:25] 화자 3: 반복 B\n" +
                        "[00:21:23] 화자 3: 반복 C\n" +
                        "[00:44:40] 화자 1: 현재 질문\n" +
                        "[00:47:37] 화자 2: 새 발화",
                assembler.transcript());
        assertEquals("00:47:37", assembler.lastTimestamp());
    }

    @Test
    public void keepsShortLegitimateRepeatedPhrases() {
        GeminiTranscriptAssembler assembler = new GeminiTranscriptAssembler();
        assembler.append("[00:00:00] 화자 1: 네");
        assembler.append("[00:00:04] 화자 1: 네\n[00:00:08] 화자 2: 다음 문장");

        assertEquals("[00:00:00] 화자 1: 네\n" +
                        "[00:00:04] 화자 1: 네\n" +
                        "[00:00:08] 화자 2: 다음 문장",
                assembler.transcript());
    }

    @Test
    public void acceptsMinuteSecondTimestampsFromUnexpectedModelFormatting() {
        GeminiTranscriptAssembler assembler = new GeminiTranscriptAssembler();
        assembler.append("**12:34** 화자 1: 문장");

        assertEquals("12:34", assembler.lastTimestamp());
    }

    @Test
    public void acceptsOnlyRealConfiguredKeys() {
        org.junit.Assert.assertFalse(GeminiInteractionsClient.isConfigured(""));
        org.junit.Assert.assertFalse(
                GeminiInteractionsClient.isConfigured("YOUR_GEMINI_API_KEY")
        );
        org.junit.Assert.assertTrue(GeminiInteractionsClient.isConfigured("AIza-example"));
    }

    @Test
    public void convertsTranscriptTimestampsToClipOffsets() {
        assertEquals(754L, GeminiInteractionsClient.timestampToSeconds("12:34"));
        assertEquals(5_025L, GeminiInteractionsClient.timestampToSeconds("01:23:45"));
        assertEquals(0L, GeminiInteractionsClient.timestampToSeconds("invalid"));
    }

    @Test
    public void requestUsesLowResolutionMinimalThinkingAndDefaultTemperature() {
        assertEquals("gemini-3.5-flash-lite", GeminiInteractionsClient.MODEL);
        assertEquals("MEDIA_RESOLUTION_LOW", GeminiInteractionsClient.MEDIA_RESOLUTION);
        assertEquals("MINIMAL", GeminiInteractionsClient.THINKING_LEVEL);
        org.junit.Assert.assertTrue(GeminiInteractionsClient.USES_DEFAULT_TEMPERATURE);
        assertEquals("https://youtube.com/watch?v=dQw4w9WgXcQ",
                new YoutubeUrlParser.VideoReference("dQw4w9WgXcQ").canonicalUrl());
    }
}
