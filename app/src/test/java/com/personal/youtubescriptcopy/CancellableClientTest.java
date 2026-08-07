package com.personal.youtubescriptcopy;

import org.junit.Test;

import static org.junit.Assert.assertThrows;

public class CancellableClientTest {
    private final YoutubeUrlParser.VideoReference video =
            new YoutubeUrlParser.VideoReference("dQw4w9WgXcQ");

    @Test
    public void defuddleCancellationIsNotReportedAsNetworkFailure() {
        DefuddleClient.Request request = DefuddleClient.newRequest(video, "ko-KR");
        request.cancel();
        assertThrows(NetworkRequestCancelledException.class, request::execute);
    }

    @Test
    public void metadataCancellationStopsBeforeOpeningConnection() {
        YoutubeMetadataClient.Request request = YoutubeMetadataClient.newRequest(video);
        request.cancel();
        assertThrows(NetworkRequestCancelledException.class, request::execute);
    }

    @Test
    public void geminiCancellationStopsBeforeOpeningConnection() {
        GeminiInteractionsClient.Request request = GeminiInteractionsClient.newRequest(
                video, "configured-test-key", (attempt, maximum) -> { }
        );
        request.cancel();
        assertThrows(NetworkRequestCancelledException.class, request::execute);
    }
}
