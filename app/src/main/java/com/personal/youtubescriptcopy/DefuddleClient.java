package com.personal.youtubescriptcopy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class DefuddleClient {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private DefuddleClient() {
    }

    public static String fetch(YoutubeUrlParser.VideoReference video, String languageTag)
            throws IOException, HttpStatusException {
        return newRequest(video, languageTag).execute();
    }

    static Request newRequest(YoutubeUrlParser.VideoReference video, String languageTag) {
        return new Request(video, languageTag);
    }

    static String readUtf8(InputStream stream) throws IOException {
        try (InputStream input = stream;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder(32_768);
            char[] buffer = new char[8_192];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Request cancelled");
                }
                result.append(buffer, 0, count);
            }
            return result.toString();
        }
    }

    private static void drain(InputStream stream) {
        if (stream == null) {
            return;
        }
        try (InputStream input = stream) {
            byte[] buffer = new byte[1_024];
            while (input.read(buffer) != -1) {
                // Consume the error body so the HTTP connection can be released cleanly.
            }
        } catch (IOException ignored) {
            // The status code is the useful error in this path.
        }
    }

    public static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static final class Request {
        private final YoutubeUrlParser.VideoReference video;
        private final String languageTag;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<HttpURLConnection> activeConnection = new AtomicReference<>();

        private Request(YoutubeUrlParser.VideoReference video, String languageTag) {
            this.video = video;
            this.languageTag = languageTag;
        }

        String execute() throws IOException, HttpStatusException {
            if (cancelled.get()) {
                throw new NetworkRequestCancelledException();
            }
            HttpURLConnection connection =
                    (HttpURLConnection) new URL(video.defuddleUrl()).openConnection();
            if (!activeConnection.compareAndSet(null, connection)) {
                connection.disconnect();
                throw new IllegalStateException("Defuddle request is already running");
            }
            if (cancelled.get()) {
                disconnect(connection);
                throw new NetworkRequestCancelledException();
            }
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty(
                    "Accept", "text/markdown, text/plain;q=0.9, */*;q=0.1"
            );
            connection.setRequestProperty("User-Agent", "YouTubeScriptCopy/1.2 (Android)");
            if (languageTag != null && !languageTag.trim().isEmpty()) {
                connection.setRequestProperty("Accept-Language", languageTag);
            }

            try {
                int status = connection.getResponseCode();
                if (cancelled.get()) {
                    throw new NetworkRequestCancelledException();
                }
                if (status < 200 || status >= 300) {
                    drain(connection.getErrorStream());
                    throw new HttpStatusException(status);
                }
                String response = readUtf8(connection.getInputStream());
                if (cancelled.get()) {
                    throw new NetworkRequestCancelledException();
                }
                return response;
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

        boolean isCancelled() {
            return cancelled.get();
        }

        private void disconnect(HttpURLConnection connection) {
            if (connection != null) {
                activeConnection.compareAndSet(connection, null);
                connection.disconnect();
            }
        }
    }

    public static final class HttpStatusException extends IOException {
        private final int statusCode;

        public HttpStatusException(int statusCode) {
            super("Defuddle returned HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }
}
