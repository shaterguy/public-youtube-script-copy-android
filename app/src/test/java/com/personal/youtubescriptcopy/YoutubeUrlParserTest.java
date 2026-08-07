package com.personal.youtubescriptcopy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class YoutubeUrlParserTest {
    private static final String ID = "dQw4w9WgXcQ";

    @Test
    public void parsesSupportedShareFormatsAndNoise() {
        assertId("https://www.youtube.com/watch?v=" + ID);
        assertId("영상 제목\nhttps://youtu.be/" + ID + "?si=abc\n설명");
        assertId("공유: https://www.youtube.com/shorts/" + ID + "?feature=share");
        assertId("지금 보기 https://www.youtube.com/live/" + ID + "?si=abc");
        assertId("https://m.youtube.com/watch?si=x&v=" + ID + "&t=42s");
        assertId("youtube.com/watch?v=" + ID);
    }

    @Test
    public void rejectsNonYoutubeAndMalformedIds() {
        assertNull(YoutubeUrlParser.find("https://example.com/watch?v=" + ID));
        assertNull(YoutubeUrlParser.find("https://youtube.com/watch?v=too-short"));
        assertNull(YoutubeUrlParser.find("https://youtube.com.evil.test/watch?v=" + ID));
        assertNull(YoutubeUrlParser.find("영상 제목만 있음"));
    }

    @Test
    public void canonicalizesEveryFormatForDefuddle() {
        YoutubeUrlParser.VideoReference video = YoutubeUrlParser.find(
                "https://youtube.com/live/" + ID
        );
        assertEquals("https://youtube.com/watch?v=" + ID, video.canonicalUrl());
        assertEquals("https://defuddle.md/youtube.com/watch?v=" + ID, video.defuddleUrl());
    }

    private static void assertId(String text) {
        assertEquals(ID, YoutubeUrlParser.find(text).videoId());
    }
}
