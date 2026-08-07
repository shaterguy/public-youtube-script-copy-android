package com.personal.youtubescriptcopy;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import java.util.Locale;

/** Receives a YouTube share, delegates durable work to the foreground service, and exits. */
public final class ShareReceiverActivity extends Activity {
    static final String EXTRA_FORCE_GEMINI = "force_gemini";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())
                || !"text/plain".equals(intent.getType())) {
            failAndFinish(R.string.error_no_youtube_url);
            return;
        }

        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        YoutubeUrlParser.VideoReference video = YoutubeUrlParser.find(
                shared == null ? null : shared.toString()
        );
        if (video == null) {
            failAndFinish(R.string.error_no_youtube_url);
            return;
        }

        boolean forceGemini = isDebuggable()
                && intent.getBooleanExtra(EXTRA_FORCE_GEMINI, false);
        SecureApiKeyStore keyStore = new SecureApiKeyStore(this);
        String apiKey = keyStore.load();
        if (!GeminiInteractionsClient.isConfigured(apiKey) || !notificationsAllowed()) {
            Intent settings = SettingsActivity.forPendingShare(this, video.videoId(), forceGemini);
            startActivity(settings);
            finish();
            return;
        }

        startExtraction(video, forceGemini);
    }

    private boolean isDebuggable() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private boolean notificationsAllowed() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startExtraction(YoutubeUrlParser.VideoReference video, boolean forceGemini) {
        ProcessingGate.StartResult gateResult = ProcessingGate.tryStart(video.videoId());
        if (gateResult == ProcessingGate.StartResult.SAME_REQUEST) {
            finish();
            return;
        }
        if (gateResult == ProcessingGate.StartResult.BUSY) {
            failAndFinish(R.string.error_already_processing);
            return;
        }

        try {
            TranscriptForegroundService.start(
                    this,
                    video,
                    Locale.getDefault().toLanguageTag(),
                    forceGemini
            );
            Toast.makeText(
                    getApplicationContext(),
                    R.string.extracting_started,
                    Toast.LENGTH_LONG
            ).show();
        } catch (RuntimeException error) {
            ProcessingGate.finish(video.videoId(), false);
            Toast.makeText(
                    getApplicationContext(),
                    R.string.error_extract,
                    Toast.LENGTH_SHORT
            ).show();
        }
        finish();
    }

    private void failAndFinish(int messageRes) {
        Toast.makeText(getApplicationContext(), messageRes, Toast.LENGTH_SHORT).show();
        finish();
    }
}
