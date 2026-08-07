package com.personal.youtubescriptcopy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class YoutubeMetadataClientTest {
    @Test
    public void parsesTitleDurationAndJsonEscapes() throws Exception {
        String json = "{\"videoDetails\":{\"videoId\":\"dQw4w9WgXcQ\"," +
                "\"title\":\"A \\\"quoted\\\" title \\uD55C\\uAE00\"," +
                "\"lengthSeconds\":\"3723\",\"isLiveContent\":false}}";
        YoutubeMetadataClient.Metadata metadata = YoutubeMetadataClient.parseResponse(
                json, "dQw4w9WgXcQ"
        );

        assertEquals("A \"quoted\" title 한글", metadata.title());
        assertEquals(3723L, metadata.durationSeconds());
        assertEquals("1:02:03", metadata.durationLabel());
    }

    @Test
    public void labelsOngoingLiveStream() throws Exception {
        String json = "{\"videoDetails\":{\"videoId\":\"dQw4w9WgXcQ\"," +
                "\"title\":\"Live now\",\"isLiveContent\":true}}";
        YoutubeMetadataClient.Metadata metadata = YoutubeMetadataClient.parseResponse(
                json, "dQw4w9WgXcQ"
        );
        assertTrue(metadata.isLive());
        assertEquals("라이브 진행 중", metadata.durationLabel());
    }
}
