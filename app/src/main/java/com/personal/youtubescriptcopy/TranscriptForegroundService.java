package com.personal.youtubescriptcopy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class TranscriptForegroundService extends Service {
    private static final String TAG = "YouTubeScriptCopy";
    private static final String ACTION_START =
            "com.personal.youtubescriptcopy.action.START";
    private static final String ACTION_CANCEL =
            "com.personal.youtubescriptcopy.action.CANCEL";
    private static final String EXTRA_VIDEO_ID = "video_id";
    private static final String EXTRA_LANGUAGE_TAG = "language_tag";
    private static final String EXTRA_FORCE_GEMINI = "force_gemini";
    private static final String PROGRESS_CHANNEL_ID = "transcript_progress_v1";
    private static final String COMPLETE_CHANNEL_ID = "transcript_complete_heads_up_v2";
    private static final int PROGRESS_NOTIFICATION_ID = 1201;
    static final int COMPLETION_NOTIFICATION_ID = 1202;
    private static final long GEMINI_START_DELAY_MS = 2_000L;
    private static final long PROGRESS_INTERVAL_MS = 5_000L;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong requestSerial = new AtomicLong();

    private NotificationManager notificationManager;
    private ActiveRequest activeRequest;
    private ScheduledFuture<?> progressFuture;
    private long serviceStartedAt;
    private volatile String progressStage = "자막 확인 중";
    private volatile int currentAttempt;
    private volatile int maximumAttempts;

    static void start(Context context,
                      YoutubeUrlParser.VideoReference video,
                      String languageTag,
                      boolean forceGemini) {
        Intent intent = new Intent(context, TranscriptForegroundService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_VIDEO_ID, video.videoId())
                .putExtra(EXTRA_LANGUAGE_TAG, languageTag)
                .putExtra(EXTRA_FORCE_GEMINI, forceGemini);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelActiveRequest();
            return START_NOT_STICKY;
        }
        if (intent == null || !ACTION_START.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (activeRequest != null) {
            return START_NOT_STICKY;
        }

        String videoId = intent.getStringExtra(EXTRA_VIDEO_ID);
        if (videoId == null || videoId.length() != 11) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        serviceStartedAt = SystemClock.elapsedRealtime();
        startForegroundNow();
        startProgressHeartbeat();

        String apiKey = new SecureApiKeyStore(this).load();
        if (!GeminiInteractionsClient.isConfigured(apiKey)) {
            failBeforeRequest(videoId, R.string.error_gemini_auth);
            return START_NOT_STICKY;
        }

        String languageTag = intent.getStringExtra(EXTRA_LANGUAGE_TAG);
        boolean forceGemini = isDebuggable()
                && intent.getBooleanExtra(EXTRA_FORCE_GEMINI, false);
        ActiveRequest request = new ActiveRequest(
                requestSerial.incrementAndGet(),
                new YoutubeUrlParser.VideoReference(videoId),
                languageTag == null ? Locale.getDefault().toLanguageTag() : languageTag,
                apiKey,
                forceGemini
        );
        activeRequest = request;
        request.log("service_started");
        request.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        ActiveRequest request = activeRequest;
        if (request != null) {
            failRequest(request, R.string.error_timeout);
        } else {
            stopSelf(startId);
        }
    }

    @Override
    public void onDestroy() {
        ActiveRequest request = activeRequest;
        activeRequest = null;
        if (request != null && !request.terminal.get()) {
            request.cancel();
            ProcessingGate.finish(request.video.videoId(), false);
        }
        stopProgressHeartbeat();
        mainHandler.removeCallbacksAndMessages(null);
        scheduler.shutdownNow();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean isDebuggable() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private void startForegroundNow() {
        Notification notification = buildProgressNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    PROGRESS_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
        } else {
            startForeground(PROGRESS_NOTIFICATION_ID, notification);
        }
    }

    private void startProgressHeartbeat() {
        stopProgressHeartbeat();
        updateProgressNotification();
        progressFuture = scheduler.scheduleAtFixedRate(
                this::updateProgressNotification,
                PROGRESS_INTERVAL_MS,
                PROGRESS_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void stopProgressHeartbeat() {
        if (progressFuture != null) {
            progressFuture.cancel(false);
            progressFuture = null;
        }
    }

    private void updateProgressNotification() {
        if (notificationManager != null) {
            notificationManager.notify(
                    PROGRESS_NOTIFICATION_ID,
                    buildProgressNotification()
            );
        }
    }

    private Notification buildProgressNotification() {
        long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - serviceStartedAt);
        StringBuilder text = new StringBuilder(progressStage);
        if (currentAttempt > 0) {
            text.append(' ').append(currentAttempt).append('/').append(maximumAttempts)
                    .append("회차");
        }
        text.append(" · ").append(formatElapsed(elapsed)).append(" 경과");

        Intent cancel = new Intent(this, TranscriptForegroundService.class)
                .setAction(ACTION_CANCEL);
        PendingIntent cancelPending = PendingIntent.getService(
                this,
                1201,
                cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return notificationBuilder(PROGRESS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_script_copy)
                .setContentTitle(getString(R.string.notification_progress_title))
                .setContentText(text.toString())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setProgress(0, 0, true)
                .addAction(0, getString(R.string.action_cancel), cancelPending)
                .build();
    }

    private void postCompletionNotification() {
        Intent dismiss = new Intent(this, DismissNotificationReceiver.class)
                .putExtra(
                        DismissNotificationReceiver.EXTRA_NOTIFICATION_ID,
                        COMPLETION_NOTIFICATION_ID
                );
        PendingIntent dismissPending = PendingIntent.getBroadcast(
                this,
                1202,
                dismiss,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent open = new Intent(this, AppSelectionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                this,
                1203,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = notificationBuilder(COMPLETE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_script_copy)
                .setContentTitle(getString(R.string.notification_complete_title))
                .setContentText(getString(R.string.notification_complete_body))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(openPending)
                .addAction(0, getString(R.string.action_confirm), dismissPending)
                .addAction(0, getString(R.string.action_open_app), openPending)
                .build();
        if (notificationManager != null) {
            notificationManager.notify(COMPLETION_NOTIFICATION_ID, notification);
        }
    }

    private void postFailureNotification(int messageRes) {
        Intent settings = new Intent(this, SettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent settingsPending = PendingIntent.getActivity(
                this,
                1204,
                settings,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification notification = notificationBuilder(COMPLETE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_script_copy)
                .setContentTitle(getString(R.string.notification_failure_title))
                .setContentText(getString(messageRes))
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ERROR)
                .setContentIntent(settingsPending)
                .build();
        if (notificationManager != null) {
            notificationManager.notify(COMPLETION_NOTIFICATION_ID, notification);
        }
    }

    private Notification.Builder notificationBuilder(String channelId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, channelId);
        }
        Notification.Builder builder = new Notification.Builder(this);
        if (COMPLETE_CHANNEL_ID.equals(channelId)) {
            return builder
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
        }
        return builder.setPriority(Notification.PRIORITY_LOW);
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || notificationManager == null) {
            return;
        }

        NotificationChannel progress = new NotificationChannel(
                PROGRESS_CHANNEL_ID,
                getString(R.string.notification_progress_channel),
                NotificationManager.IMPORTANCE_LOW
        );
        progress.setSound(null, null);
        progress.enableVibration(false);
        progress.setShowBadge(false);
        progress.setDescription("스크립트 추출 진행 상황");

        NotificationChannel complete = new NotificationChannel(
                COMPLETE_CHANNEL_ID,
                getString(R.string.notification_complete_channel),
                NotificationManager.IMPORTANCE_HIGH
        );
        complete.enableVibration(true);
        complete.setDescription("스크립트 추출 완료 및 실패 알림");

        notificationManager.createNotificationChannel(progress);
        notificationManager.createNotificationChannel(complete);
    }

    private void succeedRequest(ActiveRequest request, String payload) {
        mainHandler.post(() -> {
            if (request != activeRequest || !request.terminal.compareAndSet(false, true)) {
                return;
            }
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            try {
                if (clipboard == null) {
                    throw new IllegalStateException("Clipboard service unavailable");
                }
                clipboard.setPrimaryClip(
                        ClipData.newPlainText("YouTube transcript", payload)
                );
                request.log("clipboard_written");
            } catch (RuntimeException error) {
                request.terminal.set(false);
                failRequest(request, R.string.error_extract);
                return;
            }

            ProcessingGate.finish(request.video.videoId(), true);
            request.cancelNetworkOnly();
            finishForegroundWork();
            postCompletionNotification();
            request.log("finished_success");
        });
    }

    private void failRequest(ActiveRequest request, int messageRes) {
        mainHandler.post(() -> {
            if (request != activeRequest || !request.terminal.compareAndSet(false, true)) {
                return;
            }
            ProcessingGate.finish(request.video.videoId(), false);
            request.cancelNetworkOnly();
            finishForegroundWork();
            postFailureNotification(messageRes);
            request.log("finished_failure");
        });
    }

    private void failBeforeRequest(String videoId, int messageRes) {
        ProcessingGate.finish(videoId, false);
        finishForegroundWork();
        postFailureNotification(messageRes);
    }

    private void cancelActiveRequest() {
        ActiveRequest request = activeRequest;
        if (request != null && request.terminal.compareAndSet(false, true)) {
            request.cancel();
            ProcessingGate.finish(request.video.videoId(), false);
        }
        finishForegroundWork();
    }

    private void finishForegroundWork() {
        stopProgressHeartbeat();
        activeRequest = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private String formatElapsed(long elapsedMs) {
        long totalSeconds = elapsedMs / 1_000L;
        return String.format(
                Locale.getDefault(),
                "%02d분 %02d초",
                totalSeconds / 60L,
                totalSeconds % 60L
        );
    }

    private final class ActiveRequest {
        private final long serial;
        private final YoutubeUrlParser.VideoReference video;
        private final String languageTag;
        private final String apiKey;
        private final boolean forceGemini;
        private final long startedAt = SystemClock.elapsedRealtime();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean finalizationStarted = new AtomicBoolean();

        private final YoutubeMetadataClient.Request metadataRequest;
        private final DefuddleClient.Request defuddleRequest;
        private final GeminiInteractionsClient.Request geminiRequest;
        private TranscriptRaceCoordinator race;
        private Future<YoutubeMetadataClient.Metadata> metadataFuture;

        private ActiveRequest(long serial,
                              YoutubeUrlParser.VideoReference video,
                              String languageTag,
                              String apiKey,
                              boolean forceGemini) {
            this.serial = serial;
            this.video = video;
            this.languageTag = languageTag;
            this.apiKey = apiKey;
            this.forceGemini = forceGemini;
            metadataRequest = YoutubeMetadataClient.newRequest(video);
            defuddleRequest = forceGemini ? null : DefuddleClient.newRequest(video, languageTag);
            geminiRequest = GeminiInteractionsClient.newRequest(
                    video,
                    apiKey,
                    new GeminiInteractionsClient.AttemptListener() {
                        @Override
                        public void onAttemptStarted(int attempt, int maximum) {
                            currentAttempt = attempt;
                            maximumAttempts = maximum;
                            progressStage = "Gemini 전사";
                            updateProgressNotification();
                            if (isDebuggable()) {
                                log("gemini_attempt_" + attempt + "_of_" + maximum);
                            }
                        }

                        @Override
                        public void onAttemptFinished(
                                int attempt,
                                String finishReason,
                                GeminiCompletionPolicy.Decision decision) {
                            if (isDebuggable()) {
                                log("gemini_response_" + attempt + "_"
                                        + safeLogToken(finishReason) + "_"
                                        + decision.name().toLowerCase(Locale.ROOT));
                            }
                        }
                    }
            );
        }

        private void start() {
            metadataFuture = executor.submit(() -> {
                log("metadata_started");
                try {
                    YoutubeMetadataClient.Metadata metadata = metadataRequest.execute();
                    log("metadata_completed");
                    return metadata;
                } catch (IOException error) {
                    if (!(error instanceof NetworkRequestCancelledException)) {
                        log("metadata_failed");
                    }
                    throw error;
                }
            });

            TranscriptRaceCoordinator.Call defuddleCall = forceGemini
                    ? immediateFailure(new IOException("Forced Gemini debug path"))
                    : realDefuddleCall();
            TranscriptRaceCoordinator.Call geminiCall = realGeminiCall();

            race = new TranscriptRaceCoordinator(
                    executor,
                    (runnable, delayMs) -> {
                        Future<?> scheduled = scheduler.schedule(
                                runnable,
                                delayMs,
                                TimeUnit.MILLISECONDS
                        );
                        return () -> scheduled.cancel(false);
                    },
                    forceGemini ? 0L : GEMINI_START_DELAY_MS,
                    new TranscriptRaceCoordinator.Listener() {
                        @Override
                        public void onStateChanged(TranscriptRaceCoordinator.RequestState state) {
                            if (isDebuggable()) {
                                log("race_state_" + state.name().toLowerCase(Locale.ROOT));
                            }
                        }

                        @Override
                        public void onGeminiStarted(
                                TranscriptRaceCoordinator.GeminiStartReason reason) {
                            progressStage = "Gemini 전사";
                            currentAttempt = 1;
                            maximumAttempts = GeminiInteractionsClient.MAX_REQUESTS;
                            updateProgressNotification();
                            log("gemini_started_" + reason.name().toLowerCase(Locale.ROOT));
                        }

                        @Override
                        public void onCallFinished(
                                TranscriptRaceCoordinator.Source source,
                                TranscriptRaceCoordinator.Result result) {
                            // Winner and both-failed callbacks own terminal UI.
                        }

                        @Override
                        public void onLoserCancellation(
                                TranscriptRaceCoordinator.Source source,
                                boolean requested) {
                            log("loser_cancel_" + source.name().toLowerCase(Locale.ROOT)
                                    + (requested ? "_requested" : "_failed"));
                        }

                        @Override
                        public void onWinner(
                                TranscriptRaceCoordinator.Source source,
                                String transcript) {
                            progressStage = "결과 정리 중";
                            currentAttempt = 0;
                            maximumAttempts = 0;
                            updateProgressNotification();
                            log("winner_" + source.name().toLowerCase(Locale.ROOT));
                            finishWithWinner(transcript);
                        }

                        @Override
                        public void onBothFailed(
                                TranscriptRaceCoordinator.Result defuddle,
                                TranscriptRaceCoordinator.Result gemini) {
                            failRequest(
                                    ActiveRequest.this,
                                    chooseFailureMessage(defuddle, gemini)
                            );
                        }

                        @Override
                        public void onCancelled() {
                            log("request_cancelled");
                        }
                    }
            );
            race.start(defuddleCall, geminiCall);
        }

        private TranscriptRaceCoordinator.Call realDefuddleCall() {
            return new TranscriptRaceCoordinator.Call() {
                @Override
                public TranscriptRaceCoordinator.Result execute() {
                    log("defuddle_started");
                    try {
                        String markdown = defuddleRequest.execute();
                        String transcript = DefuddleTranscriptParser.extract(markdown);
                        log("defuddle_completed");
                        return TranscriptRaceCoordinator.Result.success(transcript);
                    } catch (NetworkRequestCancelledException error) {
                        log("defuddle_cancelled");
                        return TranscriptRaceCoordinator.Result.cancelled();
                    } catch (DefuddleTranscriptParser.MissingTranscriptException error) {
                        log("defuddle_no_transcript");
                        return TranscriptRaceCoordinator.Result.incomplete(error);
                    } catch (Throwable error) {
                        log("defuddle_failed");
                        return TranscriptRaceCoordinator.Result.failure(error);
                    }
                }

                @Override
                public void cancel() {
                    defuddleRequest.cancel();
                }
            };
        }

        private TranscriptRaceCoordinator.Call realGeminiCall() {
            return new TranscriptRaceCoordinator.Call() {
                @Override
                public TranscriptRaceCoordinator.Result execute() {
                    try {
                        String transcript = geminiRequest.execute();
                        log("gemini_completed");
                        return TranscriptRaceCoordinator.Result.success(transcript);
                    } catch (NetworkRequestCancelledException error) {
                        log("gemini_cancelled");
                        return TranscriptRaceCoordinator.Result.cancelled();
                    } catch (GeminiInteractionsClient.IncompleteTranscriptException error) {
                        log("gemini_incomplete");
                        return TranscriptRaceCoordinator.Result.incomplete(error);
                    } catch (Throwable error) {
                        log("gemini_failed");
                        return TranscriptRaceCoordinator.Result.failure(error);
                    }
                }

                @Override
                public void cancel() {
                    geminiRequest.cancel();
                }
            };
        }

        private TranscriptRaceCoordinator.Call immediateFailure(Throwable error) {
            return new TranscriptRaceCoordinator.Call() {
                @Override
                public TranscriptRaceCoordinator.Result execute() {
                    return TranscriptRaceCoordinator.Result.failure(error);
                }

                @Override
                public void cancel() {
                }
            };
        }

        private void finishWithWinner(String transcript) {
            if (!finalizationStarted.compareAndSet(false, true)
                    || cancelled.get()
                    || terminal.get()) {
                return;
            }
            try {
                executor.submit(() -> {
                    try {
                        YoutubeMetadataClient.Metadata metadata = metadataFuture.get();
                        if (cancelled.get() || terminal.get()) {
                            return;
                        }
                        String payload = ClipboardPayloadBuilder.build(
                                video,
                                metadata,
                                transcript
                        );
                        succeedRequest(this, payload);
                    } catch (CancellationException error) {
                        // Intentional cancellation.
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    } catch (ExecutionException error) {
                        Throwable cause = error.getCause() == null
                                ? error : error.getCause();
                        if (!(cause instanceof NetworkRequestCancelledException)) {
                            failRequest(
                                    this,
                                    isTimeout(cause)
                                            ? R.string.error_timeout
                                            : R.string.error_network
                            );
                        }
                    }
                });
            } catch (RejectedExecutionException ignored) {
                // Service teardown won the race.
            }
        }

        private void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            cancelNetworkOnly();
        }

        private void cancelNetworkOnly() {
            if (race != null) {
                race.cancel();
            }
            metadataRequest.cancel();
            if (metadataFuture != null) {
                metadataFuture.cancel(true);
            }
            if (defuddleRequest != null) {
                defuddleRequest.cancel();
            }
            geminiRequest.cancel();
        }

        private String safeLogToken(String value) {
            if (value == null || value.isEmpty()) {
                return "missing";
            }
            return value.replaceAll("[^A-Za-z0-9_]", "_")
                    .toLowerCase(Locale.ROOT);
        }

        private void log(String stage) {
            long elapsed = SystemClock.elapsedRealtime() - startedAt;
            String message = "request=" + serial
                    + " stage=" + stage
                    + " elapsedMs=" + elapsed;
            if (isDebuggable()) {
                Log.d(TAG, message);
            } else if (stage.startsWith("winner_")
                    || "clipboard_written".equals(stage)
                    || "finished_success".equals(stage)
                    || "finished_failure".equals(stage)) {
                Log.i(TAG, message);
            }
        }
    }

    private int chooseFailureMessage(
            TranscriptRaceCoordinator.Result defuddle,
            TranscriptRaceCoordinator.Result gemini) {
        Throwable geminiError = gemini == null ? null : gemini.error();
        if (geminiError instanceof GeminiInteractionsClient.ApiException) {
            int status = ((GeminiInteractionsClient.ApiException) geminiError).statusCode();
            if (status == 401 || status == 403) {
                return R.string.error_gemini_auth;
            }
            if (status == 429) {
                return R.string.error_gemini_quota;
            }
            if (status == 400 || status == 404 || status == 422) {
                return R.string.error_video_unavailable;
            }
        }
        if (geminiError instanceof GeminiInteractionsClient.IncompleteTranscriptException
                || (gemini != null
                && gemini.status()
                == TranscriptRaceCoordinator.ResultStatus.INCOMPLETE)) {
            return R.string.error_incomplete_transcript;
        }

        Throwable defuddleError = defuddle == null ? null : defuddle.error();
        if (isTimeout(geminiError) || isTimeout(defuddleError)) {
            return R.string.error_timeout;
        }
        if (gemini == null
                && (defuddleError
                instanceof DefuddleTranscriptParser.MissingTranscriptException
                || (defuddleError instanceof DefuddleClient.HttpStatusException
                && ((DefuddleClient.HttpStatusException) defuddleError).statusCode()
                == 404))) {
            return R.string.error_no_transcript;
        }
        if (geminiError instanceof IOException || defuddleError instanceof IOException) {
            return R.string.error_network;
        }
        return R.string.error_extract;
    }

    private boolean isTimeout(Throwable error) {
        return DefuddleClient.isTimeout(error)
                || GeminiInteractionsClient.isTimeout(error);
    }
}
