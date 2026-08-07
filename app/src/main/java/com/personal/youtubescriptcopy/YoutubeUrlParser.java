package com.personal.youtubescriptcopy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts a YouTube video id from text shared by YouTube or a browser. */
public final class YoutubeUrlParser {
    private static final Pattern VIDEO_URL = Pattern.compile(
            "(?i)(?<![A-Za-z0-9.-])(?:https?://)?(?:" +
                    "(?:www\\.|m\\.|music\\.)?youtube\\.com/" +
                    "(?:watch\\?(?:[^\\s#]*?&)?v=|shorts/|live/|embed/)" +
                    "|youtu\\.be/" +
                    ")([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])"
    );

    private YoutubeUrlParser() {
    }

    public static VideoReference find(String sharedText) {
        if (sharedText == null || sharedText.trim().isEmpty()) {
            return null;
        }
        Matcher matcher = VIDEO_URL.matcher(sharedText);
        if (!matcher.find()) {
            return null;
        }
        return new VideoReference(matcher.group(1));
    }

    public static final class VideoReference {
        private final String videoId;

        public VideoReference(String videoId) {
            this.videoId = videoId;
        }

        public String videoId() {
            return videoId;
        }

        public String canonicalUrl() {
            return "https://youtube.com/watch?v=" + videoId;
        }

        public String defuddleUrl() {
            return "https://defuddle.md/youtube.com/watch?v=" + videoId;
        }
    }
}
