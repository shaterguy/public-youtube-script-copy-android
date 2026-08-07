package com.personal.youtubescriptcopy;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores only ciphertext in app-private preferences; the AES key remains in Android Keystore. */
final class SecureApiKeyStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "youtube_script_copy_gemini_api_key_v1";
    private static final String PREFERENCES = "secure_credentials_v1";
    private static final String VALUE_CIPHERTEXT = "gemini_api_key_ciphertext";
    private static final String VALUE_IV = "gemini_api_key_iv";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SharedPreferences preferences;

    SecureApiKeyStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES,
                Context.MODE_PRIVATE
        );
    }

    synchronized void save(String rawApiKey) throws GeneralSecurityException {
        String apiKey = ApiKeyInputPolicy.normalize(rawApiKey);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
        preferences.edit()
                .putString(VALUE_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(VALUE_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .commit();
    }

    synchronized String load() {
        String encryptedValue = preferences.getString(VALUE_CIPHERTEXT, null);
        String ivValue = preferences.getString(VALUE_IV, null);
        if (encryptedValue == null || ivValue == null) {
            return null;
        }
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            SecretKey key = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
            if (key == null) {
                clearCiphertext();
                return null;
            }

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            byte[] iv = Base64.decode(ivValue, Base64.NO_WRAP);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] plaintext = cipher.doFinal(
                    Base64.decode(encryptedValue, Base64.NO_WRAP)
            );
            String value = new String(plaintext, StandardCharsets.UTF_8);
            return GeminiInteractionsClient.isConfigured(value) ? value : null;
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException error) {
            clearCiphertext();
            return null;
        }
    }

    synchronized boolean hasKey() {
        return load() != null;
    }

    synchronized void clear() {
        clearCiphertext();
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS);
            }
        } catch (GeneralSecurityException | java.io.IOException ignored) {
            // Ciphertext is already gone; a stale non-exportable key is harmless.
        }
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            SecretKey current = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
            if (current != null) {
                return current;
            }

            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    KEYSTORE
            );
            generator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
            )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return generator.generateKey();
        } catch (java.io.IOException error) {
            throw new GeneralSecurityException("Could not load Android Keystore", error);
        }
    }

    private void clearCiphertext() {
        preferences.edit()
                .remove(VALUE_CIPHERTEXT)
                .remove(VALUE_IV)
                .commit();
    }
}
