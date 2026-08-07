package com.personal.youtubescriptcopy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ClipboardPayloadBuilderTest {
    @Test
    public void putsBriefVideoInfoBeforeTheWholeTranscript() {
        YoutubeUrlParser.VideoReference video = new YoutubeUrlParser.VideoReference("dQw4w9WgXcQ");
        YoutubeMetadataClient.Metadata metadata =
                new YoutubeMetadataClient.Metadata("Example", 213L, false);

        assertEquals("영상 제목: Example\n" +
                        "영상 주소: https://youtube.com/watch?v=dQw4w9WgXcQ\n" +
                        "영상 길이: 3:33\n\n" +
                        "[전체 스크립트]\nfirst\nlast",
                ClipboardPayloadBuilder.build(video, metadata, "first\nlast"));
    }
}
