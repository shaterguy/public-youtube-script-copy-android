package com.personal.youtubescriptcopy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class DefuddleTranscriptParserTest {
    @Test
    public void removesMetadataAndKeepsEntireTranscript() throws Exception {
        String response = "---\n" +
                "title: Example\n" +
                "site: YouTube\n" +
                "---\n\n" +
                "![](https://youtube.com/watch?v=dQw4w9WgXcQ)\n\n" +
                "## Transcript\n\n" +
                "**0:00** · 첫 문장\n\n" +
                "**1:00** · 마지막 문장\n";

        assertEquals("**0:00** · 첫 문장\n\n**1:00** · 마지막 문장",
                DefuddleTranscriptParser.extract(response));
    }

    @Test
    public void supportsWindowsLineEndings() throws Exception {
        assertEquals("full text", DefuddleTranscriptParser.extract(
                "---\r\ntitle: x\r\n---\r\n\r\n## Transcript\r\n\r\nfull text\r\n"
        ));
    }

    @Test
    public void rejectsDescriptionOnlyResponse() {
        assertThrows(DefuddleTranscriptParser.MissingTranscriptException.class,
                () -> DefuddleTranscriptParser.extract("---\ntitle: no captions\n---\nDescription"));
    }
}
