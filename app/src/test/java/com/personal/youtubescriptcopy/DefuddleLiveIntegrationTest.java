package com.personal.youtubescriptcopy;

import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DefuddleLiveIntegrationTest {
    @Test
    public void fetchesCompleteTranscriptAndMetadataFromLiveServices() throws Exception {
        Assume.assumeTrue(
                "Set RUN_LIVE_INTEGRATION_TESTS=true to call live YouTube and Defuddle services",
                Boolean.parseBoolean(System.getenv("RUN_LIVE_INTEGRATION_TESTS"))
        );

        YoutubeUrlParser.VideoReference video = new YoutubeUrlParser.VideoReference("dQw4w9WgXcQ");
        YoutubeMetadataClient.Metadata metadata = YoutubeMetadataClient.fetch(video);
        String response = DefuddleClient.fetch(video, "en");
        String transcript = DefuddleTranscriptParser.extract(response);
        String payload = ClipboardPayloadBuilder.build(video, metadata, transcript);

        assertTrue("Expected a substantial transcript", transcript.length() > 500);
        assertTrue("Expected timestamps from Defuddle", transcript.contains("**0:"));
        assertTrue(payload.startsWith("영상 제목: "));
        assertTrue(payload.contains("\n영상 길이: 3:33\n\n[전체 스크립트]\n"));
        assertFalse("Frontmatter must not reach the clipboard", payload.contains("word_count:"));
    }
}
