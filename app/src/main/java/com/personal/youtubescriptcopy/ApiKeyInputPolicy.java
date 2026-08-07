package com.personal.youtubescriptcopy;

/** Pure validation used by setup UI and unit tests. */
final class ApiKeyInputPolicy {
    private ApiKeyInputPolicy() {
    }

    static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!GeminiInteractionsClient.isConfigured(value)) {
            throw new IllegalArgumentException("Gemini API key is required");
        }
        return value;
    }
}
