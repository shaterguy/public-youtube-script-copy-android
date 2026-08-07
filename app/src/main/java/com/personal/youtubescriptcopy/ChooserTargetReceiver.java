package com.personal.youtubescriptcopy;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class ChooserTargetReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        ComponentName component;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            component = intent.getParcelableExtra(
                    Intent.EXTRA_CHOSEN_COMPONENT,
                    ComponentName.class
            );
        } else {
            component = intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT);
        }
        if (component != null) {
            new UserPreferences(context).remember(component.getPackageName());
        }
    }
}
