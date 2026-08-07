package com.personal.youtubescriptcopy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Joins continuation responses without dropping content or duplicating overlap. */
final class GeminiTranscriptAssembler {
    private static final int MAX_OVERLAP_LINES = 20;
    private static final int MIN_DUPLICATE_SEQUENCE_LINES = 2;
    private static final int MIN_SINGLE_DUPLICATE_CHARACTERS = 80;
    private static final Pattern CONTROL_MARKER = Pattern.compile(
            "(?im)^\\s*\\[\\[(?:CONTINUE_FROM|TRANSCRIPT_COMPLETE)=[^\\r\\n]+\\]\\]\\s*$"
    );
    private static final Pattern TIMESTAMP = Pattern.compile(
            "(\\d{1,2}:\\d{2}(?::\\d{2})?)"
    );
    private static final Pattern TIMESTAMP_PREFIX = Pattern.compile(
            "^\\s*(?:\\*\\*)?\\[?\\d{1,2}:\\d{2}(?::\\d{2})?\\]?" +
                    "(?:\\*\\*)?\\s*(?:[·:|\\-]\\s*)?"
    );

    private final List<String> lines = new ArrayList<>();

    void append(String rawChunk) {
        String cleaned = clean(rawChunk);
        if (cleaned.isEmpty()) {
            return;
        }

        List<String> incoming = new ArrayList<>(Arrays.asList(cleaned.split("\\R", -1)));
        int historicalDuplicate = findHistoricalDuplicatePrefix(incoming);
        if (historicalDuplicate > 0) {
            incoming = new ArrayList<>(incoming.subList(historicalDuplicate, incoming.size()));
        }
        if (incoming.isEmpty()) {
            return;
        }

        int overlap = findOverlap(incoming);
        for (int index = overlap; index < incoming.size(); index++) {
            lines.add(incoming.get(index));
        }
        trimBlankEdges(lines);
    }

    String transcript() {
        return String.join("\n", lines).trim();
    }

    String lastTimestamp() {
        Matcher matcher = TIMESTAMP.matcher(transcript());
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;
    }

    static String clean(String rawChunk) {
        if (rawChunk == null) {
            return "";
        }
        String cleaned = rawChunk.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("(?s)^```[^\\n]*\\n?", "");
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
        }
        return CONTROL_MARKER.matcher(cleaned).replaceAll("").trim();
    }

    static boolean requestsContinuation(String rawChunk) {
        return rawChunk != null && rawChunk.contains("[[CONTINUE_FROM=");
    }

    static boolean signalsComplete(String rawChunk) {
        return rawChunk != null && rawChunk.contains("[[TRANSCRIPT_COMPLETE=");
    }

    private int findHistoricalDuplicatePrefix(List<String> incoming) {
        if (lines.isEmpty() || incoming.isEmpty()) {
            return 0;
        }

        int best = 0;
        for (int start = 0; start < lines.size(); start++) {
            int count = 0;
            while (start + count < lines.size() && count < incoming.size()) {
                String existing = normalizeContent(lines.get(start + count));
                String candidate = normalizeContent(incoming.get(count));
                if (existing.isEmpty() || !existing.equals(candidate)) {
                    break;
                }
                count++;
            }
            best = Math.max(best, count);
        }

        if (best >= MIN_DUPLICATE_SEQUENCE_LINES) {
            return best;
        }
        if (best == 1
                && normalizeContent(incoming.get(0)).length() >= MIN_SINGLE_DUPLICATE_CHARACTERS) {
            return 1;
        }
        return 0;
    }

    private int findOverlap(List<String> incoming) {
        int maximum = Math.min(MAX_OVERLAP_LINES, Math.min(lines.size(), incoming.size()));
        for (int count = maximum; count > 0; count--) {
            boolean equal = true;
            for (int offset = 0; offset < count; offset++) {
                String existingLine = normalize(lines.get(lines.size() - count + offset));
                String incomingLine = normalize(incoming.get(offset));
                if (!existingLine.equals(incomingLine)) {
                    equal = false;
                    break;
                }
            }
            if (equal) {
                return count;
            }
        }
        return 0;
    }

    private static String normalize(String line) {
        return line.trim().replaceAll("\\s+", " ");
    }

    private static String normalizeContent(String line) {
        String withoutTimestamp = TIMESTAMP_PREFIX.matcher(line).replaceFirst("");
        return normalize(withoutTimestamp).toLowerCase(Locale.ROOT);
    }

    private static void trimBlankEdges(List<String> values) {
        while (!values.isEmpty() && values.get(0).trim().isEmpty()) {
            values.remove(0);
        }
        while (!values.isEmpty() && values.get(values.size() - 1).trim().isEmpty()) {
            values.remove(values.size() - 1);
        }
    }
}
