package com.personal.youtubescriptcopy;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ApiKeyInputPolicyTest {
    @Test
    public void trimsValidKey() {
        assertEquals("AIza-test-key", ApiKeyInputPolicy.normalize("  AIza-test-key  "));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankKey() {
        ApiKeyInputPolicy.normalize("   ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPlaceholder() {
        ApiKeyInputPolicy.normalize("YOUR_GEMINI_API_KEY");
    }
}
