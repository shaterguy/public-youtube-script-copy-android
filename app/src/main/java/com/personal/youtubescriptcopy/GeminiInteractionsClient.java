package com.personal.youtubescriptcopy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Minimal cancellable REST client for Gemini video transcription. */
public final class GeminiInteractionsClient {
    static final String MODEL = "gemini-3.5-flash-lite";
    static final String MEDIA_RESOLUTION = "MEDIA_RESOLUTION_LOW";
    static final String THINKING_LEVEL = "MINIMAL";
    static final boolean USES_DEFAULT_TEMPERATURE = true;
    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                    MODEL + ":generateContent";
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 300_000;
    static final int MAX_REQUESTS = 4;
    private static final int MAX_OUTPUT_TOKENS = 65_536;
    private static final long OVERLAP_SECONDS = 3L;

    interface AttemptListener {
        void onAttemptStarted(int attempt, int maximum);

        default void onAttemptFinished(int attempt,
                                       String finishReason,
                                       GeminiCompletionPolicy.Decision decision) { }
    }

    private GeminiInteractionsClient() {
    }

    public static boolean isConfigured(String apiKey) {
        if (apiKey == null) {
            return false;
        }
        String trimmed = apiKey.trim();
        return !trimmed.isEmpty() && !trimmed.equals("YOUR_GEMINI_API_KEY");
    }

    public static String transcribe(YoutubeUrlParser.VideoReference video, String apiKey)
            throws IOException, ApiException, IncompleteTranscriptException {
        return newRequest(video, apiKey, (attempt, maximum) -> { }).execute();
    }

    static Request newRequest(YoutubeUrlParser.VideoReference video,
                              String apiKey,
                              AttemptListener attemptListener) {
        return new Request(video, apiKey, attemptListener);
    }

    static JSONObject buildRequest(YoutubeUrlParser.VideoReference video,
                                   String lastTimestamp) {
        try {
            JSONObject videoPart = new JSONObject()
                    .put("file_data", new JSONObject().put(
                            "file_uri", video.canonicalUrl()
                    ));
            if (lastTimestamp != null) {
                long startSeconds = Math.max(
                        0L, timestampToSeconds(lastTimestamp) - OVERLAP_SECONDS
                );
                videoPart.put("video_metadata", new JSONObject().put(
                        "start_offset", startSeconds + "s"
                ));
            }

            String prompt = lastTimestamp == null
                    ? GeminiPrompt.initial() : GeminiPrompt.continuation(lastTimestamp);
            JSONArray parts = new JSONArray()
                    .put(videoPart)
                    .put(new JSONObject().put("text", prompt));
            JSONObject generationConfig = new JSONObject()
                    .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                    .put("mediaResolution", MEDIA_RESOLUTION)
                    .put("thinkingConfig", new JSONObject().put(
                            "thinkingLevel", THINKING_LEVEL
                    ));

            return new JSONObject()
                    .put("contents", new JSONArray().put(
                            new JSONObject().put("role", "user").put("parts", parts)
                    ))
                    .put("generationConfig", generationConfig);
        } catch (JSONException error) {
            throw new IllegalStateException("Could not create Gemini request", error);
        }
    }

    static long timestampToSeconds(String timestamp) {
        if (timestamp == null || timestamp.trim().isEmpty()) {
            return 0L;
        }
        String[] pieces = timestamp.trim().split(":");
        try {
            if (pieces.length == 2) {
                return Long.parseLong(pieces[0]) * 60L + Long.parseLong(pieces[1]);
            }
            if (pieces.length == 3) {
                return Long.parseLong(pieces[0]) * 3_600L
                        + Long.parseLong(pieces[1]) * 60L
                        + Long.parseLong(pieces[2]);
            }
        } catch (NumberFormatException ignored) {
            return 0L;
        }
        return 0L;
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
        private final String apiKey;
        private final AttemptListener attemptListener;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<HttpURLConnection> activeConnection = new AtomicReference<>();

        private Request(YoutubeUrlParser.VideoReference video,
                        String apiKey,
                        AttemptListener attemptListener) {
            this.video = video;
            this.apiKey = apiKey;
            this.attemptListener = attemptListener;
        }

        String execute() throws IOException, ApiException, IncompleteTranscriptException {
            if (!isConfigured(apiKey)) {
                throw new ApiException(401, "Gemini API key is not configured");
            }

            long durationSeconds = resolveDurationSeconds();
            checkCancelled();
            GeminiTranscriptAssembler assembler = new GeminiTranscriptAssembler();
            String previousTimestamp = null;

            for (int attempt = 0; attempt < MAX_REQUESTS; attempt++) {
                checkCancelled();
                attemptListener.onAttemptStarted(attempt + 1, MAX_REQUESTS);
                Generation response = post(buildRequest(video, previousTimestamp), apiKey.trim());
                assembler.append(response.text);

                String currentTimestamp = assembler.lastTimestamp();
                long lastTimestampSeconds = currentTimestamp == null
                        ? -1L : timestampToSeconds(currentTimestamp);
                GeminiCompletionPolicy.Decision decision = GeminiCompletionPolicy.classify(
                        response.text,
                        response.finishReason,
                        lastTimestampSeconds,
                        durationSeconds
                );
                attemptListener.onAttemptFinished(
                        attempt + 1, response.finishReason, decision
                );

                if (decision == GeminiCompletionPolicy.Decision.COMPLETE) {
                    String transcript = assembler.transcript();
                    if (transcript.isEmpty()) {
                        throw new ApiException(422, "Gemini returned an empty transcript");
                    }
                    return transcript;
                }
                if (decision == GeminiCompletionPolicy.Decision.FAILURE) {
                    throw new ApiException(
                            422, "Gemini generation ended with " + response.finishReason
                    );
                }

                if (currentTimestamp == null || currentTimestamp.equals(previousTimestamp)) {
                    throw new IncompleteTranscriptException();
                }
                previousTimestamp = currentTimestamp;
            }

            throw new IncompleteTranscriptException();
        }

        void cancel() {
            cancelled.set(true);
            HttpURLConnection connection = activeConnection.getAndSet(null);
            if (connection != null) {
                connection.disconnect();
            }
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        private long resolveDurationSeconds() {
            try {
                YoutubeMetadataClient.Metadata metadata = YoutubeMetadataClient.fetch(video);
                return metadata.isLive() ? -1L : metadata.durationSeconds();
            } catch (IOException ignored) {
                // The Activity owns the user-visible metadata failure. Completion falls back to
                // the previous STOP behavior if duration cannot be resolved in this call.
                return -1L;
            }
        }

        private Generation post(JSONObject request, String trimmedApiKey)
                throws IOException, ApiException {
            checkCancelled();
            HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            if (!activeConnection.compareAndSet(null, connection)) {
                connection.disconnect();
                throw new IllegalStateException("Gemini request is already running");
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
            connection.setRequestProperty("x-goog-api-key", trimmedApiKey);

            try {
                byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }

                int statusCode = connection.getResponseCode();
                checkCancelled();
                String responseBody = readUtf8(statusCode >= 200 && statusCode < 300
                        ? connection.getInputStream() : connection.getErrorStream());
                checkCancelled();
                if (statusCode < 200 || statusCode >= 300) {
                    throw new ApiException(statusCode, extractErrorMessage(responseBody));
                }
                return parseGeneration(responseBody);
            } catch (IOException error) {
                if (cancelled.get() && !(error instanceof NetworkRequestCancelledException)) {
                    throw new NetworkRequestCancelledException(error);
                }
                throw error;
            } finally {
                disconnect(connection);
            }
        }

        private void checkCancelled() throws NetworkRequestCancelledException {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new NetworkRequestCancelledException();
            }
        }

        private void disconnect(HttpURLConnection connection) {
            if (connection != null) {
                activeConnection.compareAndSet(connection, null);
                connection.disconnect();
            }
        }
    }

    private static Generation parseGeneration(String responseBody) throws ApiException {
        try {
            JSONObject root = new JSONObject(responseBody);
            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                JSONObject promptFeedback = root.optJSONObject("promptFeedback");
                String reason = promptFeedback == null
                        ? "Gemini returned no candidates" : promptFeedback.toString();
                throw new ApiException(422, reason);
            }

            JSONObject candidate = candidates.optJSONObject(0);
            if (candidate == null) {
                throw new ApiException(422, "Gemini returned an invalid candidate");
            }

            StringBuilder text = new StringBuilder();
            JSONObject content = candidate.optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            if (parts != null) {
                for (int index = 0; index < parts.length(); index++) {
                    JSONObject part = parts.optJSONObject(index);
                    if (part == null || part.optBoolean("thought", false)) {
                        continue;
                    }
                    String value = part.optString("text");
                    if (!value.isEmpty()) {
                        if (text.length() > 0) {
                            text.append('\n');
                        }
                        text.append(value);
                    }
                }
            }
            return new Generation(candidate.optString("finishReason"), text.toString());
        } catch (JSONException error) {
            throw new ApiException(502, "Invalid Gemini response", error);
        }
    }

    private static String extractErrorMessage(String responseBody) {
        try {
            JSONObject root = new JSONObject(responseBody);
            JSONObject error = root.optJSONObject("error");
            return error == null ? "Gemini request failed" : error.optString(
                    "message", "Gemini request failed"
            );
        } catch (JSONException ignored) {
            return "Gemini request failed";
        }
    }

    private static String readUtf8(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream input = stream;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder(32_768);
            char[] buffer = new char[8_192];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new NetworkRequestCancelledException();
                }
                result.append(buffer, 0, count);
            }
            return result.toString();
        }
    }

    private static final class Generation {
        private final String finishReason;
        private final String text;

        private Generation(String finishReason, String text) {
            this.finishReason = finishReason;
            this.text = text;
        }
    }

    public static final class ApiException extends IOException {
        private final int statusCode;

        ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        ApiException(int statusCode, String message, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    public static final class IncompleteTranscriptException extends Exception {
        IncompleteTranscriptException() {
            super("Gemini could not finish the transcript after automatic continuations");
        }
    }
}
