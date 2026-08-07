package com.personal.youtubescriptcopy;

public final class ClipboardPayloadBuilder {
    private ClipboardPayloadBuilder() {
    }

    public static String build(YoutubeUrlParser.VideoReference video,
                               YoutubeMetadataClient.Metadata metadata,
                               String transcript) {
        return "영상 제목: " + metadata.title() + '\n' +
                "영상 주소: " + video.canonicalUrl() + '\n' +
                "영상 길이: " + metadata.durationLabel() + "\n\n" +
                "[전체 스크립트]\n" + transcript;
    }
}
