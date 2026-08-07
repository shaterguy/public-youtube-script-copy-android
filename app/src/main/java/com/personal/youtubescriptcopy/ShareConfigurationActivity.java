package com.personal.youtubescriptcopy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.TransactionTooLargeException;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ShareConfigurationActivity extends Activity {
    private static final String TAG = "YouTubeScriptCopy";
    private static final int MAX_CLIPBOARD_READ_ATTEMPTS = 3;
    private static final long CLIPBOARD_RETRY_DELAY_MS = 120L;
    static final String EXTRA_CONFIGURE_ONLY = "configure_only";

    private UserPreferences preferences;
    private RadioButton rememberButton;
    private RadioButton alwaysAskButton;
    private Button continueButton;
    private boolean configureOnly;
    private String transcriptPayload;
    private boolean initialized;
    private boolean clipboardReadScheduled;
    private int clipboardReadAttempts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new UserPreferences(this);
        configureOnly = getIntent().getBooleanExtra(EXTRA_CONFIGURE_ONLY, false);

        if (configureOnly) {
            initialized = true;
            showSelectionUi();
        } else {
            showLoadingUi();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !configureOnly) {
            scheduleInitialization();
        }
    }

    private void showLoadingUi() {
        int padding = dp(24);
        TextView loading = new TextView(this);
        loading.setText("공유할 스크립트를 준비하고 있습니다.");
        loading.setTextSize(18f);
        loading.setPadding(padding, padding, padding, padding);
        setContentView(loading);
    }

    private void scheduleInitialization() {
        if (initialized || clipboardReadScheduled) {
            return;
        }
        clipboardReadScheduled = true;
        long delay = clipboardReadAttempts == 0 ? 0L : CLIPBOARD_RETRY_DELAY_MS;
        getWindow().getDecorView().postDelayed(() -> {
            clipboardReadScheduled = false;
            if (!hasWindowFocus() || initialized) {
                return;
            }
            transcriptPayload = loadTranscriptFromClipboard();
            if (transcriptPayload == null || transcriptPayload.isEmpty()) {
                clipboardReadAttempts++;
                if (clipboardReadAttempts < MAX_CLIPBOARD_READ_ATTEMPTS) {
                    scheduleInitialization();
                    return;
                }
                initialized = true;
                Toast.makeText(this, R.string.error_no_share_payload, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            initialized = true;
            showSelectionUi();
        }, delay);
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

    private void showSelectionUi() {
        int padding = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText(R.string.app_picker_title);
        title.setTextSize(22f);
        root.addView(title);

        TextView description = new TextView(this);
        description.setText(
                "완료 알림에서 앱 공유를 눌렀을 때 전체 스크립트를 보낼 방식을 선택합니다."
        );
        description.setPadding(0, dp(16), 0, dp(12));
        root.addView(description);

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);

        rememberButton = new RadioButton(this);
        rememberButton.setId(View.generateViewId());
        rememberButton.setText("선택한 앱을 기억");
        group.addView(rememberButton);

        alwaysAskButton = new RadioButton(this);
        alwaysAskButton.setId(View.generateViewId());
        alwaysAskButton.setText("사용할 때마다 선택");
        group.addView(alwaysAskButton);

        if (preferences.launchMode() == UserPreferences.LaunchMode.REMEMBERED_APP
                && preferences.rememberedPackageName() != null) {
            rememberButton.setChecked(true);
        } else {
            alwaysAskButton.setChecked(true);
        }
        root.addView(group);

        continueButton = new Button(this);
        continueButton.setOnClickListener(view -> continueSelection());
        root.addView(continueButton);

        group.setOnCheckedChangeListener((radioGroup, checkedId) -> updateContinueLabel());
        updateContinueLabel();
        setContentView(root);
    }

    private void updateContinueLabel() {
        if (continueButton == null) {
            return;
        }
        if (alwaysAskButton != null && alwaysAskButton.isChecked()) {
            continueButton.setText(configureOnly
                    ? "사용할 때마다 선택으로 저장"
                    : "공유 앱 선택");
        } else {
            continueButton.setText(preferences.rememberedPackageName() == null
                    ? "공유 앱 선택"
                    : "공유 앱 변경");
        }
    }

    private void continueSelection() {
        if (alwaysAskButton.isChecked()) {
            preferences.alwaysAsk();
            if (configureOnly) {
                Toast.makeText(this, R.string.share_mode_saved, Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            finishAfterShare(shareWithChooser());
            return;
        }
        showAppList();
    }

    private void showAppList() {
        List<ShareableApp> apps = loadShareableApps();
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.error_no_share_apps, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[apps.size()];
        for (int index = 0; index < apps.size(); index++) {
            labels[index] = apps.get(index).label;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_picker_title)
                .setItems(labels, (dialog, position) -> selectApp(apps.get(position)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private List<ShareableApp> loadShareableApps() {
        PackageManager packageManager = getPackageManager();
        Intent query = new Intent(Intent.ACTION_SEND).setType("text/plain");
        List<ResolveInfo> resolved;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            resolved = packageManager.queryIntentActivities(
                    query,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY)
            );
        } else {
            resolved = packageManager.queryIntentActivities(
                    query,
                    PackageManager.MATCH_DEFAULT_ONLY
            );
        }

        Map<String, ShareableApp> uniquePackages = new LinkedHashMap<>();
        for (ResolveInfo info : resolved) {
            ActivityInfo activity = info.activityInfo;
            if (activity == null
                    || activity.packageName == null
                    || getPackageName().equals(activity.packageName)) {
                continue;
            }
            CharSequence loadedLabel = info.loadLabel(packageManager);
            String label = loadedLabel == null
                    ? activity.packageName : loadedLabel.toString().trim();
            if (label.isEmpty()) {
                label = activity.packageName;
            }
            uniquePackages.putIfAbsent(
                    activity.packageName,
                    new ShareableApp(label, activity.packageName)
            );
        }

        List<ShareableApp> apps = new ArrayList<>(uniquePackages.values());
        Collator collator = Collator.getInstance(Locale.getDefault());
        apps.sort((left, right) -> collator.compare(left.label, right.label));
        return apps;
    }

    private void selectApp(ShareableApp app) {
        preferences.remember(app.packageName);
        if (configureOnly) {
            Toast.makeText(this, R.string.share_app_saved, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        finishAfterShare(shareToPackage(app.packageName));
    }

    private ShareResult shareToPackage(String packageName) {
        Intent share = createShareIntent().setPackage(packageName);
        if (share.resolveActivity(getPackageManager()) == null) {
            return ShareResult.NO_TARGET;
        }
        return startShareIntent(share);
    }

    private ShareResult shareWithChooser() {
        Intent share = createShareIntent();
        if (share.resolveActivity(getPackageManager()) == null) {
            return ShareResult.NO_TARGET;
        }
        return startShareIntent(Intent.createChooser(
                share,
                getString(R.string.share_chooser_title)
        ));
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

    private void finishAfterShare(ShareResult result) {
        if (result == ShareResult.STARTED || result == ShareResult.TOO_LARGE) {
            finish();
        } else if (result == ShareResult.NO_TARGET) {
            Toast.makeText(this, R.string.error_no_share_apps, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.error_share_selected_app, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private enum ShareResult {
        STARTED,
        NO_TARGET,
        TOO_LARGE,
        FAILED
    }

    private static final class ShareableApp {
        private final String label;
        private final String packageName;

        private ShareableApp(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }
}
