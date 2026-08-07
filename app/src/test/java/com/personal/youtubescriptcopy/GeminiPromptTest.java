package com.personal.youtubescriptcopy;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GeminiPromptTest {
    @Test
    public void initialPromptRequiresVerbatimTimestampedTranscriptOnly() {
        String prompt = GeminiPrompt.initial();

        assertTrue(prompt.contains("요약, 번역, 의역"));
        assertTrue(prompt.contains("반복 발화"));
        assertTrue(prompt.contains("[HH:MM:SS] 화자: 발화"));
        assertTrue(prompt.contains("전사 본문만 출력"));
        assertTrue(prompt.contains("[[CONTINUE_FROM=HH:MM:SS]]"));
        assertTrue(prompt.contains("[[TRANSCRIPT_COMPLETE=HH:MM:SS]]"));
    }

    @Test
    public void continuationStartsAfterKnownPositionAndForbidsRepeats() {
        String prompt = GeminiPrompt.continuation("01:23:45");

        assertTrue(prompt.contains("01:23:45 다음"));
        assertTrue(prompt.contains("반복하지 않는다"));
        assertFalse(prompt.contains("요약하라"));
    }
}
