package com.personal.youtubescriptcopy;

import java.io.IOException;

/** Distinguishes intentional local cancellation from a user-visible network failure. */
final class NetworkRequestCancelledException extends IOException {
    NetworkRequestCancelledException() {
        super("Request cancelled");
    }

    NetworkRequestCancelledException(Throwable cause) {
        super("Request cancelled", cause);
    }
}
