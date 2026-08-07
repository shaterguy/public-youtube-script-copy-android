package com.personal.youtubescriptcopy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps the complete transcript while dropping Defuddle frontmatter and page metadata. */
public final class DefuddleTranscriptParser {
    private static final Pattern TRANSCRIPT_HEADING = Pattern.compile(
            "(?im)^\\s*##\\s+Transcript\\s*$"
    );

    private DefuddleTranscriptParser() {
    }

    public static String extract(String markdown) throws MissingTranscriptException {
        if (markdown == null || markdown.trim().isEmpty()) {
            throw new MissingTranscriptException();
        }

        Matcher matcher = TRANSCRIPT_HEADING.matcher(markdown);
        if (!matcher.find()) {
            throw new MissingTranscriptException();
        }

        String transcript = markdown.substring(matcher.end()).trim();
        if (transcript.isEmpty()) {
            throw new MissingTranscriptException();
        }
        return transcript;
    }

    public static final class MissingTranscriptException extends Exception {
        public MissingTranscriptException() {
            super("Defuddle response did not contain a transcript");
        }
    }
}
