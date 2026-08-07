package com.personal.youtubescriptcopy;

import android.content.Context;
import android.content.SharedPreferences;

final class UserPreferences {
    enum LaunchMode { ALWAYS_ASK, REMEMBERED_APP }

    private static final String PREFERENCES = "user_preferences_v1";
    private static final String KEY_MODE = "launch_mode";
    private static final String KEY_PACKAGE = "launch_package";
    private static final String KEY_COMPONENT_LEGACY = "launch_component";

    private final SharedPreferences preferences;

    UserPreferences(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
        migrateLegacyComponent();
    }

    LaunchMode launchMode() {
        String raw = preferences.getString(KEY_MODE, LaunchMode.ALWAYS_ASK.name());
        try {
            return LaunchMode.valueOf(raw);
        } catch (IllegalArgumentException error) {
            return LaunchMode.ALWAYS_ASK;
        }
    }

    boolean launchModeConfigured() {
        return preferences.contains(KEY_MODE);
    }

    String rememberedPackageName() {
        String packageName = preferences.getString(KEY_PACKAGE, null);
        if (packageName == null) {
            return null;
        }
        packageName = packageName.trim();
        return packageName.isEmpty() ? null : packageName;
    }

    void remember(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            clearRememberedApp();
            return;
        }
        preferences.edit()
                .putString(KEY_MODE, LaunchMode.REMEMBERED_APP.name())
                .putString(KEY_PACKAGE, packageName.trim())
                .remove(KEY_COMPONENT_LEGACY)
                .apply();
    }

    void alwaysAsk() {
        preferences.edit()
                .putString(KEY_MODE, LaunchMode.ALWAYS_ASK.name())
                .remove(KEY_PACKAGE)
                .remove(KEY_COMPONENT_LEGACY)
                .apply();
    }

    void clearRememberedApp() {
        alwaysAsk();
    }

    private void migrateLegacyComponent() {
        if (preferences.contains(KEY_PACKAGE)) {
            if (preferences.contains(KEY_COMPONENT_LEGACY)) {
                preferences.edit().remove(KEY_COMPONENT_LEGACY).apply();
            }
            return;
        }

        String legacyComponent = preferences.getString(KEY_COMPONENT_LEGACY, null);
        if (legacyComponent == null) {
            return;
        }

        String packageName = legacyPackageName(legacyComponent);
        if (LaunchMode.REMEMBERED_APP.name().equals(
                preferences.getString(KEY_MODE, LaunchMode.ALWAYS_ASK.name()))
                && packageName != null) {
            preferences.edit()
                    .putString(KEY_MODE, LaunchMode.REMEMBERED_APP.name())
                    .putString(KEY_PACKAGE, packageName)
                    .remove(KEY_COMPONENT_LEGACY)
                    .apply();
            return;
        }

        preferences.edit()
                .putString(KEY_MODE, LaunchMode.ALWAYS_ASK.name())
                .remove(KEY_COMPONENT_LEGACY)
                .apply();
    }

    static String legacyPackageName(String flattenedComponent) {
        if (flattenedComponent == null) {
            return null;
        }
        String trimmed = flattenedComponent.trim();
        int separator = trimmed.indexOf('/');
        if (separator <= 0) {
            return null;
        }
        String packageName = trimmed.substring(0, separator).trim();
        return packageName.isEmpty() ? null : packageName;
    }
}
