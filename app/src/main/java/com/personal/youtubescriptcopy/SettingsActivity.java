package com.personal.youtubescriptcopy;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.security.GeneralSecurityException;
import java.util.Locale;

public final class SettingsActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 3001;
    private static final String EXTRA_PENDING_VIDEO_ID = "pending_video_id";
    private static final String EXTRA_PENDING_FORCE_GEMINI = "pending_force_gemini";

    private SecureApiKeyStore keyStore;
    private EditText apiKeyInput;
    private String pendingVideoId;
    private boolean pendingForceGemini;
    private boolean waitingForNotificationSettings;

    static Intent forPendingShare(Context context, String videoId, boolean forceGemini) {
        return new Intent(context, SettingsActivity.class)
                .putExtra(EXTRA_PENDING_VIDEO_ID, videoId)
                .putExtra(EXTRA_PENDING_FORCE_GEMINI, forceGemini)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        keyStore = new SecureApiKeyStore(this);
        pendingVideoId = getIntent().getStringExtra(EXTRA_PENDING_VIDEO_ID);
        pendingForceGemini = getIntent().getBooleanExtra(
                EXTRA_PENDING_FORCE_GEMINI,
                false
        );
        showSettingsUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForNotificationSettings && notificationsAllowed()) {
            waitingForNotificationSettings = false;
            startPendingExtraction();
        }
    }

    private void showSettingsUi() {
        int padding = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText(R.string.settings_title);
        title.setTextSize(22f);
        root.addView(title);

        TextView explanation = new TextView(this);
        explanation.setText(
                "API 키는 APK에 포함하지 않고 Android Keystore로 암호화해 이 기기에만 저장합니다."
        );
        explanation.setPadding(0, dp(16), 0, dp(12));
        root.addView(explanation);

        TextView status = new TextView(this);
        status.setText(keyStore.hasKey()
                ? "저장된 API 키가 있습니다. 새 값을 입력하면 교체됩니다."
                : "최초 사용을 위해 Gemini API 키를 입력해 주세요.");
        root.addView(status);

        apiKeyInput = new EditText(this);
        apiKeyInput.setHint("Gemini API 키");
        apiKeyInput.setSingleLine(true);
        apiKeyInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        root.addView(apiKeyInput);

        Button save = new Button(this);
        save.setText("API 키 저장 및 계속");
        save.setOnClickListener(view -> saveAndContinue());
        root.addView(save);

        Button delete = new Button(this);
        delete.setText("저장된 API 키 삭제");
        delete.setOnClickListener(view -> {
            keyStore.clear();
            apiKeyInput.setText("");
            Toast.makeText(this, R.string.api_key_deleted, Toast.LENGTH_SHORT).show();
            showSettingsUi();
        });
        root.addView(delete);

        Button appChoice = new Button(this);
        appChoice.setText("기본 앱 및 선택 방식 변경");
        appChoice.setOnClickListener(view -> {
            Intent intent = new Intent(this, ShareConfigurationActivity.class)
                    .putExtra(ShareConfigurationActivity.EXTRA_CONFIGURE_ONLY, true);
            startActivity(intent);
        });
        root.addView(appChoice);

        if (!notificationsAllowed()) {
            TextView notificationExplanation = new TextView(this);
            notificationExplanation.setText(
                    "백그라운드 진행 상황과 완료 버튼을 보려면 알림 권한이 필요합니다."
            );
            notificationExplanation.setPadding(0, dp(12), 0, dp(4));
            root.addView(notificationExplanation);

            Button notificationSettings = new Button(this);
            notificationSettings.setText("알림 권한 설정 열기");
            notificationSettings.setOnClickListener(view -> openNotificationSettings());
            root.addView(notificationSettings);
        }

        setContentView(root);
    }

    private void saveAndContinue() {
        String entered = apiKeyInput.getText().toString();
        if (!entered.trim().isEmpty()) {
            try {
                keyStore.save(entered);
                apiKeyInput.setText("");
                Toast.makeText(this, R.string.api_key_saved, Toast.LENGTH_SHORT).show();
            } catch (GeneralSecurityException | IllegalArgumentException error) {
                Toast.makeText(this, R.string.api_key_required, Toast.LENGTH_SHORT).show();
                return;
            }
        } else if (!keyStore.hasKey()) {
            Toast.makeText(this, R.string.api_key_required, Toast.LENGTH_SHORT).show();
            return;
        }

        requestNotificationsOrStart();
    }

    private void requestNotificationsOrStart() {
        if (notificationsAllowed()) {
            startPendingExtraction();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS) {
            return;
        }
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startPendingExtraction();
        } else {
            Toast.makeText(
                    this,
                    R.string.notification_permission_required,
                    Toast.LENGTH_LONG
            ).show();
            showSettingsUi();
        }
    }

    private void openNotificationSettings() {
        waitingForNotificationSettings = true;
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                .setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private boolean notificationsAllowed() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startPendingExtraction() {
        if (pendingVideoId == null || pendingVideoId.length() != 11) {
            showSettingsUi();
            return;
        }

        YoutubeUrlParser.VideoReference video =
                new YoutubeUrlParser.VideoReference(pendingVideoId);
        ProcessingGate.StartResult gateResult = ProcessingGate.tryStart(video.videoId());
        if (gateResult == ProcessingGate.StartResult.SAME_REQUEST) {
            finish();
            return;
        }
        if (gateResult == ProcessingGate.StartResult.BUSY) {
            Toast.makeText(this, R.string.error_already_processing, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            TranscriptForegroundService.start(
                    this,
                    video,
                    Locale.getDefault().toLanguageTag(),
                    pendingForceGemini
            );
            Toast.makeText(this, R.string.extracting_started, Toast.LENGTH_LONG).show();
            pendingVideoId = null;
            finish();
        } catch (RuntimeException error) {
            ProcessingGate.finish(video.videoId(), false);
            Toast.makeText(this, R.string.error_extract, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
