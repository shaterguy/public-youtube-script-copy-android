package com.personal.youtubescriptcopy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads public YouTube player metadata without an API key or user account. */
public final class YoutubeMetadataClient {
    private static final String PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final Pattern VIDEO_ID = Pattern.compile("\\\"videoId\\\":\\\"([^\\\"]+)\\\"");
    private static final Pattern TITLE = Pattern.compile(
            "\\\"title\\\":\\\"((?:\\\\.|[^\\\"\\\\])*)\\\""
    );
    private static final Pattern LENGTH = Pattern.compile("\\\"lengthSeconds\\\":\\\"(\\d+)\\\"");
    private static final Pattern LIVE = Pattern.compile("\\\"isLive(?:Content)?\\\":true");
    private static final ConcurrentHashMap<String, Metadata> CACHE = new ConcurrentHashMap<>();

    private YoutubeMetadataClient() {
    }

    public static Metadata fetch(YoutubeUrlParser.VideoReference video) throws IOException {
        Metadata cached = cached(video.videoId());
        return cached == null ? newRequest(video).execute() : cached;
    }

    static Metadata cached(String videoId) {
        return videoId == null ? null : CACHE.get(videoId);
    }

    static Request newRequest(YoutubeUrlParser.VideoReference video) {
        return new Request(video);
    }

    static Metadata parseResponse(String json, String expectedVideoId) throws IOException {
        int detailsStart = json.indexOf("\"videoDetails\"");
        if (detailsStart < 0) {
            throw new IOException("YouTube player response has no video details");
        }
        String details = json.substring(detailsStart);

        Matcher idMatcher = VIDEO_ID.matcher(details);
        if (!idMatcher.find() || !expectedVideoId.equals(idMatcher.group(1))) {
            throw new IOException("YouTube player response video id mismatch");
        }

        Matcher titleMatcher = TITLE.matcher(details);
        if (!titleMatcher.find()) {
            throw new IOException("YouTube player response has no title");
        }
        String title = decodeJsonString(titleMatcher.group(1))
                .replace('\n', ' ').replace('\r', ' ').trim();

        Matcher lengthMatcher = LENGTH.matcher(details);
        if (lengthMatcher.find()) {
            try {
                return new Metadata(title, Long.parseLong(lengthMatcher.group(1)), false);
            } catch (NumberFormatException error) {
                throw new IOException("Invalid video duration", error);
            }
        }
        if (LIVE.matcher(details).find()) {
            return new Metadata(title, -1L, true);
        }
        throw new IOException("YouTube player response has no duration");
    }

    static String decodeJsonString(String value) throws IOException {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\') {
                decoded.append(current);
                continue;
            }
            if (++index >= value.length()) {
                throw new IOException("Invalid JSON string escape");
            }
            char escaped = value.charAt(index);
            switch (escaped) {
                case '"': decoded.append('"'); break;
                case '\\': decoded.append('\\'); break;
                case '/': decoded.append('/'); break;
                case 'b': decoded.append('\b'); break;
                case 'f': decoded.append('\f'); break;
                case 'n': decoded.append('\n'); break;
                case 'r': decoded.append('\r'); break;
                case 't': decoded.append('\t'); break;
                case 'u':
                    if (index + 4 >= value.length()) {
                        throw new IOException("Invalid JSON unicode escape");
                    }
                    try {
                        decoded.append((char) Integer.parseInt(value.substring(index + 1, index + 5), 16));
                    } catch (NumberFormatException error) {
                        throw new IOException("Invalid JSON unicode escape", error);
                    }
                    index += 4;
                    break;
                default:
                    throw new IOException("Unsupported JSON escape");
            }
        }
        return decoded.toString();
    }

    static final class Request {
        private final YoutubeUrlParser.VideoReference video;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<HttpURLConnection> activeConnection = new AtomicReference<>();

        private Request(YoutubeUrlParser.VideoReference video) {
            this.video = video;
        }

        Metadata execute() throws IOException {
            Metadata cached = YoutubeMetadataClient.cached(video.videoId());
            if (cached != null) {
                return cached;
            }
            if (cancelled.get()) {
                throw new NetworkRequestCancelledException();
            }
            HttpURLConnection connection = (HttpURLConnection) new URL(PLAYER_URL).openConnection();
            if (!activeConnection.compareAndSet(null, connection)) {
                connection.disconnect();
                throw new IllegalStateException("Metadata request is already running");
            }
            if (cancelled.get()) {
                disconnect(connection);
                throw new NetworkRequestCancelledException();
            }
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");

            String json = "{\"context\":{\"client\":{\"clientName\":\"IOS\"," +
                    "\"clientVersion\":\"20.10.3\"}},\"videoId\":\"" +
                    video.videoId() + "\"}";
            byte[] requestBody = json.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBody.length);

            try {
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(requestBody);
                }
                int status = connection.getResponseCode();
                if (cancelled.get()) {
                    throw new NetworkRequestCancelledException();
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("YouTube player returned HTTP " + status);
                }
                String response = DefuddleClient.readUtf8(connection.getInputStream());
                if (cancelled.get()) {
                    throw new NetworkRequestCancelledException();
                }
                Metadata metadata = parseResponse(response, video.videoId());
                CACHE.put(video.videoId(), metadata);
                return metadata;
            } catch (IOException error) {
                if (cancelled.get() && !(error instanceof NetworkRequestCancelledException)) {
                    throw new NetworkRequestCancelledException(error);
                }
                throw error;
            } finally {
                disconnect(connection);
            }
        }

        void cancel() {
            cancelled.set(true);
            disconnect(activeConnection.get());
        }

        private void disconnect(HttpURLConnection connection) {
            if (connection != null) {
                activeConnection.compareAndSet(connection, null);
                connection.disconnect();
            }
        }
    }

    public static final class Metadata {
        private final String title;
        private final long durationSeconds;
        private final boolean live;

        public Metadata(String title, long durationSeconds, boolean live) {
            this.title = title;
            this.durationSeconds = durationSeconds;
            this.live = live;
        }

        public String title() {
            return title;
        }

        public long durationSeconds() {
            return durationSeconds;
        }

        public boolean isLive() {
            return live;
        }

        public String durationLabel() {
            if (live && durationSeconds < 0) {
                return "라이브 진행 중";
            }
            long hours = durationSeconds / 3_600L;
            long minutes = (durationSeconds % 3_600L) / 60L;
            long seconds = durationSeconds % 60L;
            if (hours > 0) {
                return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
            }
            return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
        }
    }
}
