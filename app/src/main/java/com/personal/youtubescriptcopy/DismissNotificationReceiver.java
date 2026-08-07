package com.personal.youtubescriptcopy;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class DismissNotificationReceiver extends BroadcastReceiver {
    static final String EXTRA_NOTIFICATION_ID = "notification_id";

    @Override
    public void onReceive(Context context, Intent intent) {
        int id = intent == null ? TranscriptForegroundService.COMPLETION_NOTIFICATION_ID
                : intent.getIntExtra(
                        EXTRA_NOTIFICATION_ID,
                        TranscriptForegroundService.COMPLETION_NOTIFICATION_ID
                );
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(id);
        }
    }
}
