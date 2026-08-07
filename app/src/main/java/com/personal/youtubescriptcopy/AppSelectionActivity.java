package com.personal.youtubescriptcopy;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.TransactionTooLargeException;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

public final class AppSelectionActivity extends Activity {
    private static final String TAG = "YouTubeScriptCopy";
    private static final int MAX_CLIPBOARD_READ_ATTEMPTS = 3;
    private static final long CLIPBOARD_RETRY_DELAY_MS = 120L;

    private UserPreferences preferences;
    private String transcriptPayload;
    private boolean dispatchFinished;
    private boolean clipboardReadScheduled;
    private int clipboardReadAttempts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new UserPreferences(this);
        dismissCompletionNotification();

        View transparentView = new View(this);
        transparentView.setBackgroundColor(Color.TRANSPARENT);
        setContentView(transparentView);
        overridePendingTransition(0, 0);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            scheduleDispatch();
        }
    }

    private void scheduleDispatch() {
        if (dispatchFinished || clipboardReadScheduled) {
            return;
        }
        clipboardReadScheduled = true;
        long delay = clipboardReadAttempts == 0 ? 0L : CLIPBOARD_RETRY_DELAY_MS;
        getWindow().getDecorView().postDelayed(() -> {
            clipboardReadScheduled = false;
            if (!hasWindowFocus() || dispatchFinished) {
                return;
            }
            initializeDispatch();
        }, delay);
    }

    private void initializeDispatch() {
        transcriptPayload = loadTranscriptFromClipboard();
        if (transcriptPayload == null || transcriptPayload.isEmpty()) {
            clipboardReadAttempts++;
            if (clipboardReadAttempts < MAX_CLIPBOARD_READ_ATTEMPTS) {
                scheduleDispatch();
                return;
            }
            dispatchFinished = true;
            Toast.makeText(this, R.string.error_no_share_payload, Toast.LENGTH_LONG).show();
            finishWithoutAnimation();
            return;
        }

        dispatchFinished = true;
        if (!preferences.launchModeConfigured()) {
            Intent configure = new Intent(this, ShareConfigurationActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(configure);
            finishWithoutAnimation();
            return;
        }

        if (preferences.launchMode() == UserPreferences.LaunchMode.REMEMBERED_APP) {
            ShareResult directResult = shareToPackage(preferences.rememberedPackageName());
            if (directResult == ShareResult.STARTED || directResult == ShareResult.TOO_LARGE) {
                finishWithoutAnimation();
                return;
            }
            preferences.clearRememberedApp();
        }

        ShareResult chooserResult = shareWithChooser();
        if (chooserResult == ShareResult.NO_TARGET) {
            Toast.makeText(this, R.string.error_no_share_apps, Toast.LENGTH_SHORT).show();
        } else if (chooserResult == ShareResult.FAILED) {
            Toast.makeText(this, R.string.error_share_selected_app, Toast.LENGTH_SHORT).show();
        }
        finishWithoutAnimation();
    }

    private String loadTranscriptFromClipboard() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            return null;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        return text == null ? null : text.toString();
    }

    private void dismissCompletionNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(TranscriptForegroundService.COMPLETION_NOTIFICATION_ID);
        }
    }

    private ShareResult shareToPackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return ShareResult.NO_TARGET;
        }
        String normalizedPackage = packageName.trim();
        Intent share = createShareIntent().setPackage(normalizedPackage);
        if (share.resolveActivity(getPackageManager()) == null) {
            return ShareResult.NO_TARGET;
        }
        Log.i(TAG, "share_transcript package=" + normalizedPackage);
        return startShareIntent(share);
    }

    private ShareResult shareWithChooser() {
        Intent share = createShareIntent();
        if (share.resolveActivity(getPackageManager()) == null) {
            return ShareResult.NO_TARGET;
        }
        Intent chooser = Intent.createChooser(
                share,
                getString(R.string.share_chooser_title)
        );
        return startShareIntent(chooser);
    }

    private Intent createShareIntent() {
        return new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, transcriptPayload);
    }

    private ShareResult startShareIntent(Intent intent) {
        try {
            startActivity(intent);
            return ShareResult.STARTED;
        } catch (RuntimeException error) {
            if (isTransactionTooLarge(error)) {
                Log.w(TAG, "share_transcript_too_large", error);
                Toast.makeText(this, R.string.error_share_too_large, Toast.LENGTH_LONG).show();
                return ShareResult.TOO_LARGE;
            }
            if (error instanceof ActivityNotFoundException || error instanceof SecurityException) {
                Log.w(TAG, "share_transcript_no_target", error);
                return ShareResult.NO_TARGET;
            }
            Log.w(TAG, "share_transcript_failed", error);
            return ShareResult.FAILED;
        }
    }

    private boolean isTransactionTooLarge(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TransactionTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void finishWithoutAnimation() {
        finish();
        overridePendingTransition(0, 0);
    }

    private enum ShareResult {
        STARTED,
        NO_TARGET,
        TOO_LARGE,
        FAILED
    }
}
