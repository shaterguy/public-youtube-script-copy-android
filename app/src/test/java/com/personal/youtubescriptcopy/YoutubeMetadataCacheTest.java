package com.personal.youtubescriptcopy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class YoutubeMetadataCacheTest {
    @Test
    public void parsedMetadataRetainsDurationForCompletionChecks() throws Exception {
        String json = "{\"videoDetails\":{\"videoId\":\"dQw4w9WgXcQ\"," +
                "\"title\":\"Example\",\"lengthSeconds\":\"600\"}}";

        YoutubeMetadataClient.Metadata metadata =
                YoutubeMetadataClient.parseResponse(json, "dQw4w9WgXcQ");

        assertEquals(600L, metadata.durationSeconds());
        assertEquals("10:00", metadata.durationLabel());
    }
}
